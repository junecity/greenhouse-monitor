package studio.junecity.greenhouse.streaming

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import studio.junecity.greenhouse.cloud.RemoteStreamingSession
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

object KinesisSignalingCodec {
    data class Incoming(val type: String, val description: SignalingDescription? = null, val candidate: SignalingIceCandidate? = null)

    fun outgoingDescription(description: SignalingDescription, recipientClientId: String?): String = envelope(
        when (description.type.lowercase()) { "offer" -> "SDP_OFFER"; "answer" -> "SDP_ANSWER"; else -> error("Unsupported SDP type") },
        JSONObject().put("type", description.type.lowercase()).put("sdp", description.sdp).toString(),
        recipientClientId
    )

    fun outgoingCandidate(candidate: SignalingIceCandidate, recipientClientId: String?): String = envelope(
        "ICE_CANDIDATE",
        JSONObject().put("candidate", candidate.candidate).put("sdpMid", candidate.sdpMid).put("sdpMLineIndex", candidate.sdpMLineIndex).toString(),
        recipientClientId
    )

    fun incoming(text: String): Incoming = runCatching {
        val envelope = JSONObject(text)
        val type = envelope.getString("messageType")
        val payload = JSONObject(String(Base64.getDecoder().decode(envelope.getString("messagePayload"))))
        when (type) {
            "SDP_OFFER", "SDP_ANSWER" -> Incoming(type, description = SignalingDescription(payload.getString("type"), payload.getString("sdp")))
            "ICE_CANDIDATE" -> Incoming(type, candidate = SignalingIceCandidate(payload.optString("sdpMid").takeIf { it.isNotBlank() && it != "null" }, payload.getInt("sdpMLineIndex"), payload.getString("candidate")))
            else -> Incoming(type)
        }
    }.getOrElse { throw IllegalArgumentException("Invalid Kinesis signaling message", it) }

    private fun envelope(action: String, payload: String, recipientClientId: String?) = JSONObject()
        .put("action", action)
        .put("recipientClientId", recipientClientId ?: "")
        .put("messagePayload", Base64.getEncoder().encodeToString(payload.toByteArray()))
        .toString()
}

/** Connects to a backend-issued, short-lived SigV4 Kinesis signaling URL. */
class KinesisWebSocketSignaling(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()
) : KinesisSignalingTransport {
    private var listener: KinesisSignalingTransport.Listener? = null
    private var socket: WebSocket? = null
    private var recipientClientId: String? = null
    private val closed = AtomicBoolean(false)

    override fun connect(session: RemoteStreamingSession, listener: KinesisSignalingTransport.Listener) {
        val url = requireNotNull(session.signalingWebSocketUrl) { "Backend did not issue a signaling URL" }
        require(url.startsWith("wss://")) { "Kinesis signaling must use WSS" }
        check(socket == null) { "Signaling transport already connected" }
        this.listener = listener
        recipientClientId = session.peerClientId
        socket = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) = listener.onConnected()
            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching { KinesisSignalingCodec.incoming(text) }
                    .onSuccess { message ->
                        message.description?.let(listener::onDescription)
                        message.candidate?.let(listener::onIceCandidate)
                    }
                    .onFailure { listener.onDisconnected("invalid_signaling_message") }
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
                if (!closed.get()) listener.onDisconnected("signaling_closed")
            }
            override fun onFailure(webSocket: WebSocket, error: Throwable, response: Response?) {
                if (!closed.get()) listener.onDisconnected("signaling_failure")
            }
        })
    }

    override fun sendDescription(description: SignalingDescription) {
        check(socket?.send(KinesisSignalingCodec.outgoingDescription(description, recipientClientId)) == true) { "Signaling socket is unavailable" }
    }

    override fun sendIceCandidate(candidate: SignalingIceCandidate) {
        check(socket?.send(KinesisSignalingCodec.outgoingCandidate(candidate, recipientClientId)) == true) { "Signaling socket is unavailable" }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        socket?.close(1000, "viewer_closed")
        socket = null
        listener = null
    }
}

