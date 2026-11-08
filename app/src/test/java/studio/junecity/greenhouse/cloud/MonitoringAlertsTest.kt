package studio.junecity.greenhouse.cloud

import kotlinx.coroutines.runBlocking
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
    }

    @Test fun alertPollingBacksOffButReturnsToOneMinute() {
        assertEquals(60_000L, CloudAlertPollingPolicy.delayMs(0))
        assertEquals(30_000L, CloudAlertPollingPolicy.delayMs(1))
        assertEquals(60_000L, CloudAlertPollingPolicy.delayMs(2))
        assertEquals(120_000L, CloudAlertPollingPolicy.delayMs(9))
    }

    private class RecordingGateway : GreenhouseCloudGateway {
        var heartbeat: GreenhouseHeartbeat? = null
        override suspend fun reportHeartbeat(heartbeat: GreenhouseHeartbeat) { this.heartbeat = heartbeat }
        override suspend fun createPairingChallenge(serverDeviceId: String) = error("unused")
        override suspend fun claimPairingChallenge(displayCode: String, viewerDeviceId: String) = error("unused")
        override suspend fun entitlement() = error("unused")
        override suspend fun createRemoteStreamingSession(greenhouseId: String, viewerDeviceId: String) = error("unused")
        override suspend fun endRemoteStreamingSession(sessionId: String) = Unit
    }
}
