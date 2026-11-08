package studio.junecity.greenhouse.cloud

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class CloudApiConfig(val baseUrl: String, val deviceId: String) {
    init {
        require(baseUrl.startsWith("https://")) { "Cloud API must use HTTPS" }
        require(deviceId.matches(Regex("[A-Za-z0-9][A-Za-z0-9._:-]{7,79}"))) { "Invalid cloud device ID" }
    }
}

fun interface CloudTokenProvider { suspend fun accessToken(): String }

class CloudApiException(val statusCode: Int, message: String) : IllegalStateException(message)

class AuthenticatedCloudGateway(
    private val config: CloudApiConfig,
    private val tokens: CloudTokenProvider,
    private val client: OkHttpClient = OkHttpClient()
) : GreenhouseCloudGateway {
    override suspend fun createPairingChallenge(serverDeviceId: String) =
        CloudJsonCodec.pairing(request("POST", "/v1/devices/${segment(serverDeviceId)}/pairing-challenges"))

    override suspend fun claimPairingChallenge(displayCode: String, viewerDeviceId: String) = CloudJsonCodec.device(
        request("POST", "/v1/pairing-challenges/claim", JSONObject().put("pairingCode", displayCode).put("viewerDeviceId", viewerDeviceId))
    )

    override suspend fun entitlement() = CloudJsonCodec.entitlement(request("GET", "/v1/entitlement"))

    override suspend fun createRemoteStreamingSession(greenhouseId: String, viewerDeviceId: String) = CloudJsonCodec.streamingSession(
        request("POST", "/v1/greenhouses/${segment(greenhouseId)}/stream-sessions", JSONObject().put("requestId", UUID.randomUUID().toString()))
    )

    suspend fun pendingRemoteStreamingSession(serverDeviceId: String): RemoteStreamingSession? =
        requestOrNull("GET", "/v1/devices/${segment(serverDeviceId)}/stream-sessions")?.let(CloudJsonCodec::streamingSession)

    override suspend fun endRemoteStreamingSession(sessionId: String) {
        requestOrNull("DELETE", "/v1/stream-sessions/${segment(sessionId)}")
    }

    override suspend fun reportHeartbeat(heartbeat: GreenhouseHeartbeat) {
        requestOrNull(
            "POST", "/v1/devices/${segment(heartbeat.serverDeviceId)}/heartbeat",
            JSONObject().put("greenhouseId", heartbeat.greenhouseId)
                .put("recordedAtEpochMs", heartbeat.recordedAtEpochMs)
                .put("sensorConnectionHealthy", heartbeat.sensorConnectionHealthy)
                .put("criticalTemperature", heartbeat.criticalTemperature)
        )
    }

    private suspend fun request(method: String, path: String, body: JSONObject? = null): JSONObject =
        requestOrNull(method, path, body) ?: JSONObject()

    private suspend fun requestOrNull(method: String, path: String, body: JSONObject? = null): JSONObject? = withContext(Dispatchers.IO) {
        val token = tokens.accessToken().takeIf(String::isNotBlank) ?: error("Cloud access token unavailable")
        val builder = Request.Builder().url(config.baseUrl.trimEnd('/') + path)
            .header("Authorization", "Bearer $token")
            .header("x-greenhouse-device-id", config.deviceId)
            .header("Accept", "application/json")
        val payload = body?.toString()?.toRequestBody(JSON) ?: EMPTY
        when (method) {
            "GET" -> builder.get()
            "POST" -> builder.post(payload)
            "DELETE" -> builder.delete(payload)
            else -> error("Unsupported HTTP method")
        }
        client.newCall(builder.build()).execute().use { response ->
            val content = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val reason = runCatching { JSONObject(content).optString("error") }.getOrNull().orEmpty()
                throw CloudApiException(response.code, reason.ifBlank { "Cloud request failed" })
            }
            if (response.code == 204 || content.isBlank()) null else JSONObject(content)
        }
    }

    private fun segment(value: String): String {
        require(value.matches(Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,79}"))) { "Invalid identifier" }
        return value
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
        val EMPTY = ByteArray(0).toRequestBody(null)
    }
}

object CloudJsonCodec {
    fun pairing(json: JSONObject) = PairingChallenge(
        json.getString("challengeId"), json.getString("displayCode"), json.getString("serverDeviceId"),
        json.getLong("expiresAt") * 1000
    )

    fun device(json: JSONObject) = RegisteredDevice(
        json.getString("id"), json.optString("tenantId"),
        CloudDeviceRole.valueOf(if (json.getString("role") == "GREENHOUSE_SERVER") "GreenhouseServer" else "ViewerClient"),
        json.optBoolean("enabled", true)
    )

    fun entitlement(json: JSONObject): EntitlementSnapshot {
        val features = json.optJSONArray("features") ?: JSONArray()
        return EntitlementSnapshot(
            json.getString("accountId"), AccountPlan.valueOf(json.getString("plan").titleCase()),
            EntitlementState.valueOf(json.getString("state").titleCase()),
            when (json.getString("source")) {
                "GOOGLE_PLAY_VERIFIED" -> EntitlementSource.GooglePlayBackendVerification
                "COMPLIMENTARY_BACKEND_GRANT" -> EntitlementSource.BackendComplimentaryGrant
                else -> EntitlementSource.None
            },
            (0 until features.length()).mapNotNull { if (features.getString(it) == "REMOTE_LIVE_VIDEO") EntitlementFeature.RemoteLiveVideo else null }.toSet(),
            json.optLongOrNull("expiresAt")?.times(1000), json.getLong("verifiedAt") * 1000
        )
    }

    fun streamingSession(json: JSONObject): RemoteStreamingSession {
        val ice = json.getJSONArray("iceServers")
        return RemoteStreamingSession(
            json.getString("id"), json.getString("greenhouseId"), json.getString("serverDeviceId"), json.getString("viewerDeviceId"),
            json.getString("signalingChannelArn"), json.getString("region"),
            (0 until ice.length()).map { index ->
                val item = ice.getJSONObject(index); val urls = item.getJSONArray("urls")
                IceServer((0 until urls.length()).map(urls::getString), item.optString("username").ifBlank { null }, item.optString("credential").ifBlank { null })
            },
            json.getLong("expiresAtEpochMs"), json.getString("signalingWebSocketUrl"), json.optNullableString("peerClientId")
        )
    }

    private fun String.titleCase() = lowercase().replaceFirstChar(Char::uppercase)
    private fun JSONObject.optLongOrNull(name: String) = if (has(name) && !isNull(name)) getLong(name) else null
    private fun JSONObject.optNullableString(name: String) = if (has(name) && !isNull(name)) getString(name).ifBlank { null } else null
}
