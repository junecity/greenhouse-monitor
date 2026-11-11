package studio.junecity.greenhouse.streaming

import android.Manifest
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import studio.junecity.greenhouse.cloud.CloudJsonCodec
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.webrtc.VideoTrack
import org.webrtc.PeerConnection
import java.util.concurrent.CopyOnWriteArrayList
import studio.junecity.greenhouse.cloud.RemoteStreamingSession

@RunWith(AndroidJUnit4::class)
class LibWebRtcPeerInstrumentedTest {
    @get:Rule
    val cameraPermission: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.CAMERA)

    private val peers = mutableListOf<LibWebRtcPeer>()

    @After
    fun closePeers() = peers.forEach(LibWebRtcPeer::close)

    @Test
    fun masterCameraDeliversRemoteVideoToViewer() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        repeat(2) { round ->
            exerciseLocalSession(context, round)
        }
    }

    private fun exerciseLocalSession(context: Context, round: Int) {
        val previewFrames = CountDownLatch(3)
        val localOutput = VideoFrameOutput()
        localOutput.attach { frame -> if (frame.buffer.width > 0 && frame.buffer.height > 0) previewFrames.countDown() }
        val masterTransport = LoopbackSignaling()
        val viewerTransport = LoopbackSignaling()
        masterTransport.remote = viewerTransport
        viewerTransport.remote = masterTransport
        val masterConnected = CountDownLatch(1)
        val viewerConnected = CountDownLatch(1)
        val viewerTrack = CountDownLatch(1)
        val viewerFrames = CountDownLatch(3)

        val master = LibWebRtcPeer(context, WebRtcRole.Master, masterTransport, events(masterConnected), localPreview = localOutput)
        val viewer = LibWebRtcPeer(context, WebRtcRole.Viewer, viewerTransport, object : WebRtcPeerEvents {
            override fun onConnected() = viewerConnected.countDown()
            override fun onDisconnected(reason: String) = Unit
            override fun onRemoteVideo(track: VideoTrack) {
                viewerTrack.countDown()
                track.addSink { frame ->
                    if (frame.buffer.width > 0 && frame.buffer.height > 0) viewerFrames.countDown()
                }
            }
        })
        peers += master
        peers += viewer

        viewer.connect(session(peerClientId = null))
        // Match production: Viewer joins first; the Server only sees it on a later poll.
        Thread.sleep(5_500L)
        master.connect(session(peerClientId = "viewer-device"))

        assertTrue("Master ICE did not connect", masterConnected.await(20, TimeUnit.SECONDS))
        assertTrue("Viewer ICE did not connect", viewerConnected.await(20, TimeUnit.SECONDS))
        assertTrue("Viewer did not receive the camera video track", viewerTrack.await(10, TimeUnit.SECONDS))
        assertTrue("Viewer did not decode camera frames in session $round", viewerFrames.await(15, TimeUnit.SECONDS))
        assertTrue("Server preview stopped receiving frames in session $round", previewFrames.await(5, TimeUnit.SECONDS))
        closePeers()
        peers.clear()
    }

    /** Opt-in real AWS signaling/media test. Short-lived URLs are supplied out of source control. */
    @Test fun liveKinesisDeliversDecodedCameraFrames() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveCloudMedia") == "true")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val fixture = File(context.filesDir, "live-stream-test.json")
        val config = JSONObject(fixture.readText())
        fixture.delete()
        var previousMaster: LibWebRtcPeer? = null
        repeat(2) { round ->
            previousMaster = exerciseAwsSession(context, config, round, previousMaster)
        }
    }

    private fun exerciseAwsSession(
        context: Context, config: JSONObject, round: Int, previousMaster: LibWebRtcPeer?
    ): LibWebRtcPeer {
        val frames = CountDownLatch(3)
        val masterConnected = CountDownLatch(1)
        val viewerConnected = CountDownLatch(1)
        val failures = CopyOnWriteArrayList<String>()
        val policy = if (InstrumentationRegistry.getArguments().getString("relayOnly") == "true")
            PeerConnection.IceTransportsType.RELAY else PeerConnection.IceTransportsType.ALL
        val master = LibWebRtcPeer(context, WebRtcRole.Master, KinesisWebSocketSignaling(), object : WebRtcPeerEvents {
            override fun onConnected() = masterConnected.countDown()
            override fun onDisconnected(reason: String) { failures += "master:$reason" }
            override fun onRemoteVideo(track: VideoTrack) = Unit
        }, iceTransportPolicy = policy)
        val viewer = LibWebRtcPeer(context, WebRtcRole.Viewer, KinesisWebSocketSignaling(), object : WebRtcPeerEvents {
            override fun onConnected() = viewerConnected.countDown()
            override fun onDisconnected(reason: String) { failures += "viewer:$reason" }
            override fun onRemoteVideo(track: VideoTrack) {
                track.addSink { frame -> if (frame.buffer.width > 0 && frame.buffer.height > 0) frames.countDown() }
            }
        }, iceTransportPolicy = policy)
        peers += master
        peers += viewer
        val viewId = "diagnostic-view-$round"
        viewer.connect(CloudJsonCodec.streamingSession(config.getJSONObject("viewer")).copy(id = viewId))
        Thread.sleep(5_500)
        // The reopened viewer has already sent offers on the same device route.
        previousMaster?.close()
        master.connect(CloudJsonCodec.streamingSession(config.getJSONObject("master")).copy(id = viewId))
        assertTrue("Live AWS master ICE failed", masterConnected.await(45, TimeUnit.SECONDS))
        assertTrue("Live AWS viewer ICE failed", viewerConnected.await(15, TimeUnit.SECONDS))
        assertTrue("Live AWS viewer decoded no frames", frames.await(20, TimeUnit.SECONDS))
        assertTrue("Unexpected streaming failures in view $round: $failures", failures.isEmpty())
        viewer.close()
        return master
    }

    private fun events(connected: CountDownLatch) = object : WebRtcPeerEvents {
        override fun onConnected() = connected.countDown()
        override fun onDisconnected(reason: String) = Unit
        override fun onRemoteVideo(track: VideoTrack) = Unit
    }

    private fun session(peerClientId: String?) = RemoteStreamingSession(
        id = "instrumentation-session",
        greenhouseId = "instrumentation-greenhouse",
        serverDeviceId = "server-device",
        viewerDeviceId = "viewer-device",
        signalingChannelArn = "loopback",
        region = "us-east-1",
        iceServers = emptyList(),
        expiresAtEpochMs = System.currentTimeMillis() + 60_000L,
        signalingWebSocketUrl = "wss://loopback.invalid",
        peerClientId = peerClientId
    )

    private class LoopbackSignaling : KinesisSignalingTransport {
        lateinit var remote: LoopbackSignaling
        private var listener: KinesisSignalingTransport.Listener? = null

        override fun connect(session: RemoteStreamingSession, listener: KinesisSignalingTransport.Listener) {
            this.listener = listener
            listener.onConnected()
        }

        override fun sendDescription(description: SignalingDescription) {
            remote.listener?.onDescription(description)
        }

        override fun sendIceCandidate(candidate: SignalingIceCandidate) {
            remote.listener?.onIceCandidate(candidate)
        }

        override fun close() = Unit
    }
}
