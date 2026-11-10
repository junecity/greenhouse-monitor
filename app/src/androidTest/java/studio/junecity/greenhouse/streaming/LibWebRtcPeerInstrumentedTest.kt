package studio.junecity.greenhouse.streaming

import android.Manifest
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.webrtc.VideoTrack
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
        val masterTransport = LoopbackSignaling()
        val viewerTransport = LoopbackSignaling()
        masterTransport.remote = viewerTransport
        viewerTransport.remote = masterTransport
        val masterConnected = CountDownLatch(1)
        val viewerConnected = CountDownLatch(1)
        val viewerTrack = CountDownLatch(1)
        val viewerFrames = CountDownLatch(3)

        val master = LibWebRtcPeer(context, WebRtcRole.Master, masterTransport, events(masterConnected))
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
        assertTrue("Viewer did not decode camera frames", viewerFrames.await(15, TimeUnit.SECONDS))
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
