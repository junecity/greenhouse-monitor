package studio.junecity.greenhouse.streaming

import android.content.Context
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.VideoTrack
import studio.junecity.greenhouse.cloud.AuthenticatedCloudGateway
import studio.junecity.greenhouse.cloud.CloudApiConfig
import studio.junecity.greenhouse.cloud.CloudDeviceRole
import studio.junecity.greenhouse.cloud.CloudJsonCodec
import studio.junecity.greenhouse.settings.CloudEnrollment

/** One greenhouse channel and stable device route across two real camera/decoder sessions. */
internal class ReopenCameraChannel(private val context: Context) : AutoCloseable {
    val enrollment = CloudEnrollment("viewer-device", "test-house", CloudDeviceRole.ViewerClient)
    val creates = AtomicInteger()
    val lateAnswers = AtomicInteger()
    val firstDelete = CountDownLatch(1)
    val releaseDelete = CountDownLatch(1)
    private val executor = Executors.newSingleThreadExecutor()
    private var master: LibWebRtcPeer? = null
    private var savedAnswer: String? = null
    private var replayed = false
    private var masterSocket: Socket? = null
    private var viewerSocket: Socket? = null

    val api = AuthenticatedCloudGateway(
        CloudApiConfig("https://fixture.invalid", enrollment.deviceId), { "fixture" },
        client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val body = when (request.method) {
                "POST" -> {
                    check(request.url.encodedPath == "/v1/greenhouses/${enrollment.greenhouseId}/stream-sessions")
                    val round = creates.incrementAndGet()
                    val session = JSONObject().put("id", "session-$round")
                        .put("greenhouseId", enrollment.greenhouseId).put("serverDeviceId", "server-device")
                        .put("viewerDeviceId", enrollment.deviceId).put("signalingChannelArn", "test-channel")
                        .put("region", "us-east-1").put("iceServers", JSONArray())
                        .put("expiresAtEpochMs", System.currentTimeMillis() + 60_000)
                        .put("signalingWebSocketUrl", "wss://fixture.invalid/viewer")
                    executor.execute {
                        master?.close()
                        master = LibWebRtcPeer(context, WebRtcRole.Master, signalingTransport(), object : WebRtcPeerEvents {
                            override fun onConnected() = Unit
                            override fun onDisconnected(reason: String) = Unit
                            override fun onRemoteVideo(track: VideoTrack) = Unit
                        }).also { it.connect(CloudJsonCodec.streamingSession(session).copy(
                            peerClientId = enrollment.deviceId, signalingWebSocketUrl = "wss://fixture.invalid/master"
                        )) }
                    }
                    session.toString()
                }
                "DELETE" -> {
                    if (request.url.encodedPath.endsWith("session-1")) {
                        firstDelete.countDown()
                        check(releaseDelete.await(90, TimeUnit.SECONDS))
                    }
                    ""
                }
                else -> error("Unexpected fixture route")
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(if (body.isEmpty()) 204 else 201)
                .message("Fixture").body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
    )

    fun signalingTransport() = KinesisWebSocketSignaling(object : WebSocket.Factory {
        override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket =
            Socket(request, listener).also { socket ->
                executor.execute {
                    if (socket.isMaster) masterSocket = socket else viewerSocket = socket
                    listener.onOpen(socket, Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                        .code(101).message("Switching protocols").build())
                }
            }
    })

    private inner class Socket(val req: Request, val listener: WebSocketListener) : WebSocket {
        val isMaster = req.url.encodedPath == "/master"
        @Volatile var closed = false
        override fun request() = req
        override fun queueSize() = 0L
        override fun send(text: String): Boolean {
            if (closed) return false
            executor.execute {
                if (closed) return@execute
                val packet = JSONObject(text)
                val type = packet.getString("action")
                val event = JSONObject().put("messageType", type)
                    .put("messagePayload", packet.getString("messagePayload"))
                if (!isMaster) event.put("senderClientId", enrollment.deviceId)
                if (creates.get() == 1 && type == "SDP_ANSWER") savedAnswer = event.toString()
                if (creates.get() == 2 && !replayed && type == "SDP_OFFER") {
                    replayed = true
                    savedAnswer?.let { old -> listener.onMessage(this, old); lateAnswers.incrementAndGet() }
                }
                val dest = if (isMaster) viewerSocket else masterSocket
                if (dest != null && !dest.closed) dest.listener.onMessage(dest, event.toString())
            }
            return true
        }
        override fun send(bytes: ByteString) = false
        override fun close(code: Int, reason: String?): Boolean { closed = true; return true }
        override fun cancel() { closed = true }
    }

    override fun close() {
        releaseDelete.countDown()
        executor.submit { master?.close() }.get(10, TimeUnit.SECONDS)
        executor.shutdown()
    }
}
