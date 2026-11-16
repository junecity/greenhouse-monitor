package studio.junecity.greenhouse.cloud

import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MonitoringAlertsTest {
    @Test fun criticalTemperatureIsIndependentAndAcknowledged() {
        assertEquals(MonitoringAlertPriority.Critical, MonitoringAlertPolicy.priority(MonitoringAlertType.CriticalTemperature))
        assertTrue(MonitoringAlertPolicy.bypassesOrdinaryNotificationPreference(MonitoringAlertType.CriticalTemperature))
        assertTrue(MonitoringAlertPolicy.requiresAcknowledgement(MonitoringAlertType.CriticalTemperature))
    }

    @Test fun informationRespectsOrdinaryPreference() {
        assertEquals(MonitoringAlertPriority.Normal, MonitoringAlertPolicy.priority(MonitoringAlertType.Information))
        assertFalse(MonitoringAlertPolicy.bypassesOrdinaryNotificationPreference(MonitoringAlertType.Information))
        assertFalse(MonitoringAlertPolicy.requiresAcknowledgement(MonitoringAlertType.Information))
    }

    @Test fun reporterUsesTrustedClockAndHealthState() = runBlocking {
        val gateway = RecordingGateway()
        HeartbeatReporter(gateway) { 42L }.report(MonitoringHealth("server-1", "greenhouse-1", false, true))
        assertEquals(GreenhouseHeartbeat("server-1", "greenhouse-1", 42L, false, true), gateway.heartbeat)
    }

    @Test fun ordinaryInstallRequiresTheExplicitMatchingClientCommand() {
        val modes = listOf("MANAGED", "SELF_UPDATE", "CONFIRM_ON_SERVER", "MANUAL", "UNKNOWN")
        assertFalse(ServerUpdateCommand(82, "SELF_UPDATE").authorizes("SELF_UPDATE"))
        for (server in modes) {
            assertEquals(server == "MANAGED", ServerUpdateCommand(82).authorizes(server))
            for (requested in modes) assertEquals(
                requested == server && server in listOf("MANAGED", "SELF_UPDATE", "CONFIRM_ON_SERVER"),
                ServerUpdateCommand(82, requested, "fixture-order").authorizes(server)
            )
        }
    }

    @Test fun cloudAlertCodecPreservesSafetyFields() {
        val alert = CloudJsonCodec.alert(JSONObject("""{
            "id":"1700000000-aaaaaaaa",
            "greenhouseId":"greenhouse-1",
            "type":"SERVER_OFFLINE",
            "priority":"HIGH",
            "message":"Greenhouse server is offline",
            "createdAtEpochMs":1700000000000,
            "requiresAcknowledgement":true
        }"""))

        assertEquals(MonitoringAlertType.ServerOffline, alert.type)
        assertEquals(MonitoringAlertPriority.High, alert.priority)
        assertEquals(1_700_000_000_000L, alert.occurredAtEpochMs)
        assertTrue(alert.requiresAcknowledgement)
        assertEquals(null, alert.resolvedAtEpochMs)
    }

    @Test fun viewingPhoneRequestsUnacknowledgedRecoveredAlarms() = runBlocking {
        val paths = mutableListOf<String?>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val query = chain.request().url.queryParameter("includeRecovered")
            paths.add(query)
            val alert = JSONObject().put("id", "night-1").put("type", "CRITICAL_TEMPERATURE")
                .put("priority", "CRITICAL").put("message", "Low reading followed by recovery.")
                .put("createdAtEpochMs", 1000L).put("resolvedAtEpochMs", 2000L)
            val response = JSONObject().put("criticalIncidentsVerified", true).put("offlineIncidentsVerified", true)
                .put("alerts", if (query == "true") org.json.JSONArray().put(alert) else org.json.JSONArray())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(response.toString().toResponseBody("application/json".toMediaType())).build()
        }.build()
        val api = AuthenticatedCloudGateway(CloudApiConfig("https://fixture.invalid", "client-01"),
            CloudTokenProvider { "fixture" }, client = client)
        assertEquals(2000L, api.pendingAlerts(true).alerts.single().resolvedAtEpochMs)
        assertTrue(api.pendingAlerts(false).alerts.isEmpty())
        assertEquals(listOf("true", null), paths)
    }

    @Test fun alertPollingBacksOffButReturnsToOneMinute() {
        assertEquals(60_000L, CloudAlertPollingPolicy.delayMs(0))
        assertEquals(30_000L, CloudAlertPollingPolicy.delayMs(1))
        assertEquals(60_000L, CloudAlertPollingPolicy.delayMs(2))
        assertEquals(120_000L, CloudAlertPollingPolicy.delayMs(9))
    }

    @Test fun gatewayDeliversIndependentSensorIncidentsOnTheSameServerAtTheSameTemperature() = runBlocking {
        val template = JSONObject().put("greenhouseId", "house-A").put("deviceId", "server-A")
            .put("type", "CRITICAL_TEMPERATURE").put("priority", "CRITICAL")
            .put("message", "Measured 89°F above 82°F.").put("requiresAcknowledgement", true)
        fun entry(id: String, sensor: String, time: Long) =
            JSONObject(template.toString()).put("id", id).put("sensorId", sensor).put("createdAtEpochMs", time)
        val list = org.json.JSONArray().put(entry("a1", "bench", 1000)).put(entry("b1", "roof", 1000))
        val result = JSONObject().put("alerts", list).put("complete", true)
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(result.toString().toResponseBody("application/json".toMediaType())).build()
        }.build()
        val api = AuthenticatedCloudGateway(CloudApiConfig("https://fixture.invalid", "client-01"),
            CloudTokenProvider { "fixture" }, client = client)
        for (verified in listOf(true, false)) {
            result.put("criticalIncidentsVerified", verified)
            assertEquals(mapOf("a1" to "bench", "b1" to "roof"),
                api.pendingAlerts(true).alerts.associate { it.id to it.sensorId })
        }
        list.put(entry("a2", "bench", 2000))
        assertEquals(setOf("a2", "b1"), api.pendingAlerts(true).alerts.map { it.id }.toSet())
        result.put("criticalIncidentsVerified", true)
        assertEquals(setOf("a1", "a2", "b1"), api.pendingAlerts(true).alerts.map { it.id }.toSet())
    }

    @Test fun accountDeviceCodecIncludesRevocationStateAndCreationTime() {
        val value = CloudJsonCodec.accountDevice(JSONObject("""{
            "id":"viewer-12345678",
            "tenantId":"tenant-12345678",
            "role":"VIEWER_CLIENT",
            "enabled":false,
            "greenhouseId":"greenhouse-12345678",
            "createdAtEpochMs":123000
        }"""))

        assertEquals(CloudDeviceRole.ViewerClient, value.device.role)
        assertFalse(value.device.enabled)
        assertEquals(123_000L, value.createdAtEpochMs)
    }

    private class RecordingGateway : GreenhouseCloudGateway {
        var heartbeat: GreenhouseHeartbeat? = null
        override suspend fun reportHeartbeat(heartbeat: GreenhouseHeartbeat): HeartbeatReply { this.heartbeat = heartbeat; return HeartbeatReply() }
        override suspend fun createPairingChallenge(serverDeviceId: String) = error("unused")
        override suspend fun claimPairingChallenge(displayCode: String, viewerDeviceId: String) = error("unused")
        override suspend fun entitlement() = error("unused")
        override suspend fun createRemoteStreamingSession(greenhouseId: String, viewerDeviceId: String) = error("unused")
        override suspend fun endRemoteStreamingSession(sessionId: String) = Unit
    }
}
