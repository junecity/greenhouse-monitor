package studio.junecity.greenhouse.streaming

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.webrtc.AudioTrack
import org.webrtc.Camera1Enumerator
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraEnumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import studio.junecity.greenhouse.cloud.RemoteStreamingSession
import studio.junecity.greenhouse.camera.CameraStreamProfile
import studio.junecity.greenhouse.model.CameraStreamQuality
import java.util.concurrent.atomic.AtomicBoolean

enum class WebRtcRole { Master, Viewer }
internal fun WebRtcRole.initiatesOffer() = this == WebRtcRole.Viewer
internal fun WebRtcRole.receivesRemoteVideo() = this == WebRtcRole.Viewer

/** Holds trickle ICE until the peer has a remote SDP to attach it to. */
internal class RemoteIceCandidateBuffer<T> {
    private val pending = mutableListOf<T>()
    private var remoteDescriptionReady = false

    @Synchronized
    fun receive(candidate: T): List<T> = if (remoteDescriptionReady) listOf(candidate) else {
        pending += candidate
        emptyList()
    }

    @Synchronized
    fun remoteDescriptionApplied(): List<T> {
        remoteDescriptionReady = true
        return pending.toList().also { pending.clear() }
    }
}

data class SignalingDescription(val type: String, val sdp: String)
data class SignalingIceCandidate(val sdpMid: String?, val sdpMLineIndex: Int, val candidate: String)

interface KinesisSignalingTransport : AutoCloseable {
    interface Listener {
        fun onConnected()
        fun onDescription(description: SignalingDescription)
        fun onIceCandidate(candidate: SignalingIceCandidate)
        fun onDisconnected(reason: String)
    }

    fun connect(session: RemoteStreamingSession, listener: Listener)
    fun sendDescription(description: SignalingDescription)
    fun sendIceCandidate(candidate: SignalingIceCandidate)
    override fun close()
}

interface WebRtcPeerEvents {
    fun onConnected()
    fun onDisconnected(reason: String)
    fun onRemoteVideo(track: VideoTrack)
}

/**
 * Owns libwebrtc resources for exactly one on-demand viewing session. AWS signaling is
 * injected so no AWS key or customer identity is stored in this media layer.
 */
class LibWebRtcPeer(
    context: Context,
    private val role: WebRtcRole,
    private val signaling: KinesisSignalingTransport,
    private val events: WebRtcPeerEvents,
    private val captureProfile: CameraStreamProfile = CameraStreamProfile.from(CameraStreamQuality.Balanced)
) : WebRtcPeer, KinesisSignalingTransport.Listener {
    private val appContext = context.applicationContext
    private val egl = EglBase.create()
    private val factory: PeerConnectionFactory
    private var peer: PeerConnection? = null
    private var capturer: CameraVideoCapturer? = null
    private var textureHelper: SurfaceTextureHelper? = null
    private var videoSource: VideoSource? = null
    private var localTrack: VideoTrack? = null
    private val remoteIceCandidates = RemoteIceCandidateBuffer<SignalingIceCandidate>()
    private val closed = AtomicBoolean(false)
    private val answerReceived = AtomicBoolean(false)
    private val signalingScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(appContext).createInitializationOptions())
        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
            .createPeerConnectionFactory()
    }

    override fun connect(session: RemoteStreamingSession) {
        check(!closed.get()) { "WebRTC peer is closed" }
        val ice = session.iceServers.map { server ->
            PeerConnection.IceServer.builder(server.urls)
                .setUsername(server.username ?: "")
                .setPassword(server.credential ?: "")
                .createIceServer()
        }
        val configuration = PeerConnection.RTCConfiguration(ice).apply {
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            keyType = PeerConnection.KeyType.ECDSA
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.ENABLED
        }
        peer = factory.createPeerConnection(configuration, peerObserver()) ?: error("Unable to create WebRTC peer")
        if (role.receivesRemoteVideo()) {
            peer?.addTransceiver(
                MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
                RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY)
            ) ?: error("Unable to create receive-only video transceiver")
        }
        if (role == WebRtcRole.Master) attachCameraTrack()
        signaling.connect(session, this)
    }

    override fun onConnected() {
        if (role.initiatesOffer()) {
            createOffer()
            // The server polls for requests and can join after the initial offer was sent.
            // Repeat the same offer (including gathered candidates) until an answer arrives.
            signalingScope.launch {
                while (isActive && !answerReceived.get() && !closed.get()) {
                    delay(3_000L)
                    if (!answerReceived.get() && !closed.get()) {
                        peer?.localDescription?.let { description ->
                            runCatching { signaling.sendDescription(SignalingDescription(description.type.canonicalForm(), description.description)) }
                                .onFailure { events.onDisconnected("offer_retry_failed") }
                        }
                    }
                }
            }
        }
    }

    override fun onDescription(description: SignalingDescription) {
        if (closed.get()) return
        if (role == WebRtcRole.Viewer && description.type.equals("answer", ignoreCase = true)) {
            if (!answerReceived.compareAndSet(false, true)) return
        }
        val remote = SessionDescription(SessionDescription.Type.fromCanonicalForm(description.type), description.sdp)
        peer?.setRemoteDescription(observer(onSuccess = {
            remoteIceCandidates.remoteDescriptionApplied().forEach(::addRemoteIceCandidate)
            if (remote.type == SessionDescription.Type.OFFER) createAnswer()
        }), remote)
    }

    override fun onIceCandidate(candidate: SignalingIceCandidate) {
        remoteIceCandidates.receive(candidate).forEach(::addRemoteIceCandidate)
    }

    private fun addRemoteIceCandidate(candidate: SignalingIceCandidate) {
        val added = peer?.addIceCandidate(IceCandidate(candidate.sdpMid, candidate.sdpMLineIndex, candidate.candidate)) == true
        if (!added) events.onDisconnected("ice_candidate_rejected")
    }

    override fun onDisconnected(reason: String) = events.onDisconnected(reason)

    private fun createOffer() {
        peer?.createOffer(observer(onDescription = ::setAndSendLocalDescription), MediaConstraints())
    }

    private fun createAnswer() {
        peer?.createAnswer(observer(onDescription = ::setAndSendLocalDescription), MediaConstraints())
    }

    private fun setAndSendLocalDescription(description: SessionDescription) {
        peer?.setLocalDescription(observer(onSuccess = {
            signaling.sendDescription(SignalingDescription(description.type.canonicalForm(), description.description))
        }), description)
    }

    private fun attachCameraTrack() {
        val enumerator: CameraEnumerator = if (Camera2Enumerator.isSupported(appContext)) Camera2Enumerator(appContext) else Camera1Enumerator(false)
        val deviceName = enumerator.deviceNames.firstOrNull(enumerator::isFrontFacing)
            ?: enumerator.deviceNames.firstOrNull()
            ?: error("No camera available for WebRTC")
        capturer = enumerator.createCapturer(deviceName, null) ?: error("Unable to open camera")
        textureHelper = SurfaceTextureHelper.create("GreenhouseWebRtcCapture", egl.eglBaseContext)
        videoSource = factory.createVideoSource(false).also { source ->
            capturer?.initialize(textureHelper, appContext, source.capturerObserver)
            capturer?.startCapture(captureProfile.width, captureProfile.height, captureProfile.framesPerSecond)
        }
        localTrack = factory.createVideoTrack("greenhouse-video", videoSource).also { track ->
            peer?.addTrack(track, listOf("greenhouse-stream"))
        }
    }

    private fun peerObserver() = object : PeerConnection.Observer {
        override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
            Log.i(TAG, "${role.name} ICE state ${state.name}")
            when (state) {
                PeerConnection.IceConnectionState.CONNECTED, PeerConnection.IceConnectionState.COMPLETED -> events.onConnected()
                PeerConnection.IceConnectionState.DISCONNECTED, PeerConnection.IceConnectionState.FAILED, PeerConnection.IceConnectionState.CLOSED -> events.onDisconnected(state.name.lowercase())
                else -> Unit
            }
        }
        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
            Log.d(TAG, "${role.name} ICE gathering ${state.name}")
        }
        override fun onIceCandidate(candidate: IceCandidate) = signaling.sendIceCandidate(SignalingIceCandidate(candidate.sdpMid, candidate.sdpMLineIndex, candidate.sdp))
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
        override fun onAddStream(stream: MediaStream) = Unit
        override fun onRemoveStream(stream: MediaStream) = Unit
        override fun onDataChannel(channel: DataChannel) = Unit
        override fun onRenegotiationNeeded() = Unit
        override fun onAddTrack(receiver: RtpReceiver, mediaStreams: Array<out MediaStream>) {
            (receiver.track() as? VideoTrack)?.let(events::onRemoteVideo)
        }
        override fun onTrack(transceiver: org.webrtc.RtpTransceiver) {
            (transceiver.receiver.track() as? VideoTrack)?.let(events::onRemoteVideo)
        }
    }

    private fun observer(onSuccess: () -> Unit = {}, onDescription: (SessionDescription) -> Unit = {}) = object : SdpObserver {
        override fun onCreateSuccess(description: SessionDescription) = onDescription(description)
        override fun onSetSuccess() = onSuccess()
        override fun onCreateFailure(error: String) {
            Log.w(TAG, "${role.name} SDP creation failed: $error")
            events.onDisconnected("sdp_create_failed")
        }
        override fun onSetFailure(error: String) {
            Log.w(TAG, "${role.name} SDP application failed: $error")
            events.onDisconnected("sdp_set_failed")
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        signalingScope.cancel()
        signaling.close()
        runCatching { capturer?.stopCapture() }
        capturer?.dispose()
        localTrack?.dispose()
        videoSource?.dispose()
        textureHelper?.dispose()
        peer?.close()
        peer?.dispose()
        factory.dispose()
        egl.release()
        capturer = null
        peer = null
    }

    private companion object { const val TAG = "GreenhouseWebRtc" }
}

