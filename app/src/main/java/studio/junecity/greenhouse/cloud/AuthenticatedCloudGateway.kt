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
    private val tokens: CloudTokenProvider? = null,
    private val deviceSecret: String? = null,
    private val client: OkHttpClient = OkHttpClient()
) : GreenhouseCloudGateway {
    init { require((tokens == null) != (deviceSecret == null)) { "Exactly one cloud credential is required" } }

    suspend fun bootstrap(): CloudAccount {
        val json = request("POST", "/v1/bootstrap")
        return CloudAccount(json.getString("accountId"), json.getString("tenantId"))
    }

    suspend fun registerServerDevice(greenhouseId: String): RegisteredDevice = CloudJsonCodec.device(
        request(
            "POST", "/v1/devices",
            JSONObject().put("deviceId", config.deviceId).put("greenhouseId", greenhouseId).put("role", "GREENHOUSE_SERVER")
        )
    )

    suspend fun registerClientDevice(): RegisteredDevice = CloudJsonCodec.device(
        request("POST", "/v1/devices", JSONObject().put("deviceId", config.deviceId).put("role", "VIEWER_CLIENT"))
    )

    suspend fun currentDevice(): RegisteredDevice = CloudJsonCodec.device(request("GET", "/v1/devices/current"))

    suspend fun devices(): List<AccountDevice> {
        val json = request("GET", "/v1/devices")
        val values = json.optJSONArray("devices") ?: JSONArray()
        return (0 until values.length()).map { CloudJsonCodec.accountDevice(values.getJSONObject(it)) }
    }

    suspend fun setDeviceEnabled(deviceId: String, enabled: Boolean): RegisteredDevice = CloudJsonCodec.device(
        request("POST", "/v1/devices/${segment(deviceId)}/state", JSONObject().put("enabled", enabled))
    )

    suspend fun setDeviceConnected(deviceId: String, connected: Boolean): RegisteredDevice = CloudJsonCodec.device(
        request("POST", "/v1/devices/${segment(deviceId)}/connection", JSONObject().put("connected", connected))
    )

    suspend fun createServerInvitation(
        greenhouseId: String,
        greenhouseName: String,
        replaceServerDeviceId: String? = null
    ): ServerPairingInvitation {
        val body = JSONObject().put("greenhouseId", greenhouseId).put("greenhouseName", greenhouseName)
        replaceServerDeviceId?.let { body.put("replaceServerDeviceId", it) }
        return CloudJsonCodec.serverInvitation(request("POST", "/v1/server-pairing/invitations", body))
    }

    suspend fun pendingServerPairings(): List<ServerPairingRequest> {
        val values = request("GET", "/v1/server-pairing/requests").optJSONArray("requests") ?: JSONArray()
        return (0 until values.length()).map { CloudJsonCodec.serverPairingRequest(values.getJSONObject(it)) }
    }

    suspend fun approveServerPairing(requestId: String) {
        request("POST", "/v1/server-pairing/requests/${segment(requestId)}/approve")
    }

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

    suspend fun refreshRemoteStreamingConnection(sessionId: String): RemoteStreamingSession =
        CloudJsonCodec.streamingSession(request("POST", "/v1/stream-sessions/${segment(sessionId)}/connection"))

    suspend fun renewRemoteStreamingLease(sessionId: String): Long =
        request("POST", "/v1/stream-sessions/${segment(sessionId)}/lease").getLong("authorizationExpiresAtEpochMs")

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
                .put("batteryPercent", heartbeat.batteryPercent)
                .put("charging", heartbeat.charging)
                .put("networkType", heartbeat.networkType)
                .put("wifiSignalStrength", heartbeat.wifiSignalStrength)
                .put("sensorCount", heartbeat.sensorCount)
                .put("sensorProvider", heartbeat.sensorProvider)
                .put("cameraStatus", heartbeat.cameraStatus)
                .put("lastSensorSyncEpochMs", heartbeat.lastSensorSyncEpochMs)
                .put("lastDataUploadEpochMs", heartbeat.lastDataUploadEpochMs)
                .put("appVersion", heartbeat.appVersion)
        )
    }

    suspend fun pendingAlerts(): List<MonitoringAlert> {
        val json = request("GET", "/v1/alerts")
        val values = json.optJSONArray("alerts") ?: JSONArray()
        return (0 until values.length()).map { CloudJsonCodec.alert(values.getJSONObject(it)) }
    }

    suspend fun acknowledgeAlert(alertId: String) {
        requestOrNull("POST", "/v1/alerts/${segment(alertId)}/acknowledge")
    }

    suspend fun uploadGreenhouseBackup(greenhouseId: String, backup: JSONObject) {
        val path = "/v1/greenhouses/${segment(greenhouseId)}/backup"
        uploadSensorSnapshot(backup) { payload -> requestOrNull("POST", path, JSONObject().put("backup", payload)) }
    }

    suspend fun greenhouseBackup(greenhouseId: String): JSONObject? =
        requestOrNull("GET", "/v1/greenhouses/${segment(greenhouseId)}/backup")?.optJSONObject("backup")

    private suspend fun request(method: String, path: String, body: JSONObject? = null): JSONObject =
        requestOrNull(method, path, body) ?: JSONObject()

    private suspend fun requestOrNull(method: String, path: String, body: JSONObject? = null): JSONObject? = withContext(Dispatchers.IO) {
        val authenticatedPath = if (deviceSecret != null) "/v1/device" + path.removePrefix("/v1") else path
        val builder = Request.Builder().url(config.baseUrl.trimEnd('/') + authenticatedPath)
            .header("x-greenhouse-device-id", config.deviceId)
            .header("Accept", "application/json")
        if (deviceSecret != null) builder.header("x-greenhouse-device-secret", deviceSecret)
        else {
            val token = requireNotNull(tokens).accessToken().takeIf(String::isNotBlank) ?: error("Cloud access token unavailable")
            builder.header("Authorization", "Bearer $token")
        }
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

class ServerPairingGateway(
    private val baseUrl: String,
    private val client: OkHttpClient = OkHttpClient()
) {
    init { require(baseUrl.startsWith("https://")) }

    suspend fun requestPairing(pairingCode: String, serverDeviceId: String, deviceSecret: String): ServerPairingStatus =
        call("/v1/public-server-pairing/requests", JSONObject()
            .put("pairingCode", pairingCode).put("serverDeviceId", serverDeviceId).put("deviceSecret", deviceSecret))

    suspend fun status(requestId: String, serverDeviceId: String, deviceSecret: String): ServerPairingStatus =
        call("/v1/public-server-pairing/status", JSONObject()
            .put("requestId", requestId).put("serverDeviceId", serverDeviceId).put("deviceSecret", deviceSecret))

    private suspend fun call(path: String, body: JSONObject): ServerPairingStatus = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(baseUrl.trimEnd('/') + path)
            .header("Accept", "application/json")
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        client.newCall(request).execute().use { response ->
            val content = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val reason = runCatching { JSONObject(content).optString("error") }.getOrNull().orEmpty()
                throw CloudApiException(response.code, reason.ifBlank { "Server pairing request failed" })
            }
            CloudJsonCodec.serverPairingStatus(JSONObject(content))
        }
    }
}

object CloudJsonCodec {
    fun accountDevice(json: JSONObject) = AccountDevice(
        device(json), json.getLong("createdAtEpochMs")
    )

    fun alert(json: JSONObject) = MonitoringAlert(
        id = json.getString("id"),
        greenhouseId = json.optString("greenhouseId"),
        type = when (json.getString("type")) {
            "CRITICAL_TEMPERATURE" -> MonitoringAlertType.CriticalTemperature
            "SERVER_OFFLINE" -> MonitoringAlertType.ServerOffline
            "SENSOR_COMMUNICATION_FAILURE" -> MonitoringAlertType.SensorCommunicationFailure
            else -> MonitoringAlertType.Information
        },
        priority = when (json.getString("priority")) {
            "CRITICAL" -> MonitoringAlertPriority.Critical
            "HIGH" -> MonitoringAlertPriority.High
            else -> MonitoringAlertPriority.Normal
        },
        message = json.getString("message"),
        occurredAtEpochMs = json.getLong("createdAtEpochMs"),
        requiresAcknowledgement = json.optBoolean("requiresAcknowledgement"),
    )

    fun pairing(json: JSONObject) = PairingChallenge(
        json.getString("challengeId"), json.getString("displayCode"), json.getString("serverDeviceId"),
        json.getLong("expiresAt") * 1000
    )

    fun serverInvitation(json: JSONObject) = ServerPairingInvitation(
        json.getString("invitationId"), json.getString("displayCode"), json.getString("greenhouseId"),
        json.getString("greenhouseName"), json.getLong("expiresAt") * 1000
    )

    fun serverPairingRequest(json: JSONObject) = ServerPairingRequest(
        json.getString("requestId"), json.getString("serverDeviceId"), json.getString("greenhouseId"),
        json.getString("greenhouseName"), json.optNullableString("replaceServerDeviceId"), json.getString("state"),
        json.getLong("createdAtEpochMs"), json.getLong("expiresAtEpochMs")
    )

    fun serverPairingStatus(json: JSONObject): ServerPairingStatus {
        val device = if (json.optString("state") == "APPROVED") device(json) else null
        return ServerPairingStatus(json.getString("requestId"), json.getString("state"), json.getLong("expiresAt") * 1000, device)
    }

    fun device(json: JSONObject) = RegisteredDevice(
        json.getString("id"), json.optString("tenantId"),
        CloudDeviceRole.valueOf(if (json.getString("role") == "GREENHOUSE_SERVER") "GreenhouseServer" else "ViewerClient"),
        json.optBoolean("enabled", true), json.optNullableString("greenhouseId"),
        json.optBoolean("online", false), json.optLongOrNull("lastSeenAtEpochMs"),
        json.optBoolean("communicationSuspended", false), json.optNullableString("clientRole")
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
            json.getLong("expiresAtEpochMs"), json.getString("signalingWebSocketUrl"), json.optNullableString("peerClientId"),
            json.optLongOrNull("authorizationExpiresAtEpochMs")
        )
    }

    private fun String.titleCase() = lowercase().replaceFirstChar(Char::uppercase)
    private fun JSONObject.optLongOrNull(name: String) = if (has(name) && !isNull(name)) getLong(name) else null
    private fun JSONObject.optNullableString(name: String) = if (has(name) && !isNull(name)) getString(name).ifBlank { null } else null
}

/** Keep current readings and settings deliverable if verbose history exceeds the API size limit. */
internal suspend fun uploadSensorSnapshot(backup: JSONObject, send: suspend (JSONObject) -> Unit) {
    var payload = backup
    while (true) {
        try {
            send(payload)
            return
        } catch (error: CloudApiException) {
            val history = payload.optJSONArray("history")
            if (error.statusCode != 413 || history == null || history.length() == 0) throw error
            val recent = org.json.JSONArray()
            for (index in (history.length() + 1) / 2 until history.length()) recent.put(history.get(index))
            payload = JSONObject().apply {
                payload.keys().forEach { key -> put(key, if (key == "history") recent else payload.get(key)) }
            }
        }
    }
}
