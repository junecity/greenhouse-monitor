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
    data class Incoming(
        val type: String,
        val senderClientId: String? = null,
        val description: SignalingDescription? = null,
        val candidate: SignalingIceCandidate? = null,
        val statusSuccess: Boolean? = null,
        val statusCode: String? = null
    )

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
        val sender = envelope.optString("senderClientId").takeIf { it.isNotBlank() && it != "null" }
        if (type == "STATUS_RESPONSE") {
            val status = envelope.optJSONObject("statusResponse") ?: JSONObject()
            return@runCatching Incoming(
                type = type,
                senderClientId = sender,
                statusSuccess = status.optBoolean("success", false),
                statusCode = status.optString("statusCode").takeIf(String::isNotBlank)
            )
        }
        val payload = JSONObject(String(Base64.getDecoder().decode(envelope.getString("messagePayload"))))
        when (type) {
            "SDP_OFFER", "SDP_ANSWER" -> Incoming(type, sender, description = SignalingDescription(payload.getString("type"), payload.getString("sdp")))
            "ICE_CANDIDATE" -> Incoming(type, sender, candidate = SignalingIceCandidate(payload.optString("sdpMid").takeIf { it.isNotBlank() && it != "null" }, payload.getInt("sdpMLineIndex"), payload.getString("candidate")))
            else -> Incoming(type, sender)
        }
    }.getOrElse { throw IllegalArgumentException("Invalid Kinesis signaling message", it) }

    private fun envelope(action: String, payload: String, recipientClientId: String?): String {
        val envelope = JSONObject()
            .put("action", action)
            .put("messagePayload", Base64.getEncoder().encodeToString(payload.toByteArray()))
        // Kinesis single-master signaling requires this field for messages from MASTER and
        // requires it to be absent for messages from VIEWER. An empty string is not equivalent.
        if (!recipientClientId.isNullOrBlank()) envelope.put("recipientClientId", recipientClientId)
        return envelope.toString()
    }
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
                        if (recipientClientId == null && message.senderClientId != null) recipientClientId = message.senderClientId
                        if (message.type == "STATUS_RESPONSE" && message.statusSuccess == false) {
                            listener.onDisconnected("signaling_rejected_${message.statusCode ?: "unknown"}")
                            return@onSuccess
                        }
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

