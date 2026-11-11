package studio.junecity.greenhouse.streaming

import android.util.Log
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
        val statusCode: String? = null,
        val sessionId: String? = null
    )

    fun outgoingDescription(description: SignalingDescription, recipientClientId: String?, sessionId: String? = null): String = envelope(
        when (description.type.lowercase()) { "offer" -> "SDP_OFFER"; "answer" -> "SDP_ANSWER"; else -> error("Unsupported SDP type") },
        JSONObject().put("sessionId", sessionId).put("type", description.type.lowercase()).put("sdp", description.sdp).toString(),
        recipientClientId
    )

    fun outgoingCandidate(candidate: SignalingIceCandidate, recipientClientId: String?, sessionId: String? = null): String = envelope(
        "ICE_CANDIDATE",
        JSONObject().put("sessionId", sessionId).put("candidate", candidate.candidate).put("sdpMid", candidate.sdpMid).put("sdpMLineIndex", candidate.sdpMLineIndex).toString(),
        recipientClientId
    )

    fun incoming(text: String): Incoming = runCatching {
        // Kinesis can send empty text frames between signaling events. These are not
        // malformed SDP and must not tear down an otherwise healthy camera session.
        if (text.isBlank()) return@runCatching Incoming("EMPTY")
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
        if (type !in setOf("SDP_OFFER", "SDP_ANSWER", "ICE_CANDIDATE")) return@runCatching Incoming(type, sender)
        val payload = JSONObject(String(Base64.getDecoder().decode(envelope.getString("messagePayload"))))
        val sessionId = if (payload.has("sessionId")) payload.getString("sessionId") else null
        when (type) {
            "SDP_OFFER", "SDP_ANSWER" -> Incoming(type, sender, description = SignalingDescription(payload.getString("type"), payload.getString("sdp")), sessionId = sessionId)
            "ICE_CANDIDATE" -> Incoming(type, sender, candidate = SignalingIceCandidate(payload.optString("sdpMid").takeIf { it.isNotBlank() && it != "null" }, payload.getInt("sdpMLineIndex"), payload.getString("candidate")), sessionId = sessionId)
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
    private val client: WebSocket.Factory = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()
) : KinesisSignalingTransport {
    @Volatile private var socket: WebSocket? = null
    private lateinit var sessionId: String
    private var recipientClientId: String? = null
    private val closed = AtomicBoolean(false)

    override fun connect(session: RemoteStreamingSession, listener: KinesisSignalingTransport.Listener) {
        val url = requireNotNull(session.signalingWebSocketUrl) { "Backend did not issue a signaling URL" }
        require(url.startsWith("wss://")) { "Kinesis signaling must use WSS" }
        check(socket == null) { "Signaling transport already connected" }
        sessionId = session.id
        recipientClientId = session.peerClientId
        socket = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (closed.get()) return
                Log.i(TAG, "Kinesis signaling socket opened as ${if (recipientClientId == null) "viewer" else "master"}")
                listener.onConnected()
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (closed.get()) return
                runCatching { KinesisSignalingCodec.incoming(text) }
                    .onSuccess { message ->
                        if (message.type == "EMPTY") return@onSuccess
                        Log.d(TAG, "Kinesis signaling received ${message.type}")
                        // Kinesis reuses the greenhouse channel and device routing ID across views.
                        // A delayed offer/answer from a closed view must never configure its successor.
                        if (message.description != null || message.candidate != null) {
                            if (message.sessionId != null && message.sessionId != sessionId) return@onSuccess
                            if (recipientClientId != null && message.senderClientId != recipientClientId) return@onSuccess
                            if (message.type == "SDP_OFFER" && recipientClientId == null) return@onSuccess
                            if (message.type == "SDP_ANSWER" && recipientClientId != null) return@onSuccess
                        }
                        if (message.type == "STATUS_RESPONSE" && message.statusSuccess == false) {
                            Log.w(TAG, "Kinesis signaling rejected a message: ${message.statusCode ?: "unknown"}")
                            listener.onDisconnected("signaling_rejected_${message.statusCode ?: "unknown"}")
                            return@onSuccess
                        }
                        if (message.type == "GO_AWAY" || message.type == "RECONNECT_ICE_SERVER") {
                            listener.onDisconnected("signaling_refresh_required")
                            return@onSuccess
                        }
                        message.description?.let(listener::onDescription)
                        message.candidate?.let(listener::onIceCandidate)
                    }
                    .onFailure {
                        // Parser exceptions can contain signaling payloads. Keep logs redacted.
                        Log.w(TAG, "Kinesis signaling returned an invalid message")
                        listener.onDisconnected("invalid_signaling_message")
                    }
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
                if (!closed.get()) listener.onDisconnected("signaling_closed")
            }
            override fun onFailure(webSocket: WebSocket, error: Throwable, response: Response?) {
                Log.w(TAG, "Kinesis signaling socket failed with HTTP ${response?.code ?: "network"}", error)
                if (!closed.get()) listener.onDisconnected("signaling_failure_${response?.code ?: "network"}")
            }
        })
    }

    override fun sendDescription(description: SignalingDescription) {
        check(socket?.send(KinesisSignalingCodec.outgoingDescription(description, recipientClientId, sessionId)) == true) { "Signaling socket is unavailable" }
    }

    override fun sendIceCandidate(candidate: SignalingIceCandidate) {
        check(socket?.send(KinesisSignalingCodec.outgoingCandidate(candidate, recipientClientId, sessionId)) == true) { "Signaling socket is unavailable" }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        socket?.close(1000, "viewer_closed")
        socket = null
    }

    private companion object { const val TAG = "GreenhouseWebRtc" }
}

