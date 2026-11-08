package studio.junecity.greenhouse.streaming

import android.content.Context
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
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import studio.junecity.greenhouse.cloud.RemoteStreamingSession
import java.util.concurrent.atomic.AtomicBoolean

enum class WebRtcRole { Master, Viewer }

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
    private val events: WebRtcPeerEvents
) : WebRtcPeer, KinesisSignalingTransport.Listener {
    private val appContext = context.applicationContext
    private val egl = EglBase.create()
    private val factory: PeerConnectionFactory
    private var peer: PeerConnection? = null
    private var capturer: CameraVideoCapturer? = null
    private var textureHelper: SurfaceTextureHelper? = null
    private var videoSource: VideoSource? = null
    private var localTrack: VideoTrack? = null
    private val closed = AtomicBoolean(false)

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
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }
        peer = factory.createPeerConnection(configuration, peerObserver()) ?: error("Unable to create WebRTC peer")
        if (role == WebRtcRole.Master) attachCameraTrack()
        signaling.connect(session, this)
    }

    override fun onConnected() {
        if (role == WebRtcRole.Master) createOffer()
    }

    override fun onDescription(description: SignalingDescription) {
        val remote = SessionDescription(SessionDescription.Type.fromCanonicalForm(description.type), description.sdp)
        peer?.setRemoteDescription(observer(onSuccess = {
            if (remote.type == SessionDescription.Type.OFFER) createAnswer()
        }), remote)
    }

    override fun onIceCandidate(candidate: SignalingIceCandidate) {
        peer?.addIceCandidate(IceCandidate(candidate.sdpMid, candidate.sdpMLineIndex, candidate.candidate))
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
            capturer?.startCapture(640, 360, 15)
        }
        localTrack = factory.createVideoTrack("greenhouse-video", videoSource).also { track ->
            peer?.addTrack(track, listOf("greenhouse-stream"))
        }
    }

    private fun peerObserver() = object : PeerConnection.Observer {
        override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
            when (state) {
                PeerConnection.IceConnectionState.CONNECTED, PeerConnection.IceConnectionState.COMPLETED -> events.onConnected()
                PeerConnection.IceConnectionState.DISCONNECTED, PeerConnection.IceConnectionState.FAILED, PeerConnection.IceConnectionState.CLOSED -> events.onDisconnected(state.name.lowercase())
                else -> Unit
            }
        }
        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) = Unit
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
        override fun onCreateFailure(error: String) = events.onDisconnected("sdp_create_failed")
        override fun onSetFailure(error: String) = events.onDisconnected("sdp_set_failed")
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
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
}

