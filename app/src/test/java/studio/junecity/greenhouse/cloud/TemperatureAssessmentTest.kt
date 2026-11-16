package studio.junecity.greenhouse.cloud

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import studio.junecity.greenhouse.model.GoveeSensor

class TemperatureAssessmentTest {
    private val now = 1_800_000_089_000L
    private val govee = GoveeSensor("h5179-1", "H5179", "West bench", 40.0, 75.0, true, now - 1_000)
    private val shelly = govee.copy(id = "shelly:01", name = "East door", temperatureF = 107.3)
    private fun assess(vararg sensors: GoveeSensor) = assessTemperature(sensors.toList(), 45.0, 100.0, now)

    @Test fun mixedSensorsReportTheLargestDepartureWithItsLimitAndOriginalTime() {
        val high = requireNotNull(assess(govee, shelly).breach)
        assertEquals(TemperatureBreach("East door", 107.3, 100.0, TemperatureBreach.Direction.HIGH, now - 1000), high)
        val low = requireNotNull(assess(govee.copy(temperatureF = 29.4, humidityPercent = null), shelly).breach)
        assertEquals(TemperatureBreach("West bench", 29.4, 45.0, TemperatureBreach.Direction.LOW, now - 1000), low)
    }

    @Test fun staleOfflineInvalidAndFutureSamplesDoNotTriggerOrEstablishRecovery() {
        for (sensor in listOf(govee.copy(online = false), govee.copy(updatedAtEpochMs = now - 600_001),
                govee.copy(updatedAtEpochMs = now + 1), govee.copy(temperatureF = null), govee.copy(temperatureF = Double.NaN))) {
            assertNull(assess(sensor).breach)
            assertFalse(assess(sensor).evaluated)
            assertFalse(assess(shelly.copy(temperatureF = 70.0), sensor).evaluated)
        }
        assertFalse(assess().evaluated)
        assertTrue(assess(govee.copy(temperatureF = 45.0), shelly.copy(temperatureF = 100.0)).evaluated)
        assertNull(assess(govee.copy(temperatureF = 45.0), shelly.copy(temperatureF = 100.0)).breach)
        assertTrue(assess(govee, shelly.copy(online = false)).evaluated)
    }

    @Test fun eachOffenderMustRecoverButUnrelatedMissingSensorsCannotHoldAnIncidentOpen() {
        val absent = govee.copy(id = "ess:03", online = false)
        val opened = assess(govee, shelly, absent)
        assertEquals(setOf(govee.id, shelly.id), opened.outstandingSensorIds)
        val firstRecovered = assessTemperature(listOf(govee.copy(temperatureF = 60.0), shelly.copy(online = false), absent),
            45.0, 100.0, now, opened.outstandingSensorIds)
        assertFalse(firstRecovered.evaluated)
        assertEquals(setOf(shelly.id), firstRecovered.outstandingSensorIds)
        val bothRecovered = assessTemperature(listOf(govee.copy(online = false), shelly.copy(temperatureF = 70.0), absent),
            45.0, 100.0, now, firstRecovered.outstandingSensorIds)
        assertTrue(bothRecovered.evaluated)
        assertNull(bothRecovered.breach)
        assertTrue(bothRecovered.outstandingSensorIds.isEmpty())
    }

    @Test fun goveeBetweenBackgroundPollsIsConnectedButDoesNotSupplyFreshEvidence() {
        val previous = govee.copy(updatedAtEpochMs = now - 15 * 60_000L)
        assertTrue(assess(previous).sensorConnectionHealthy)
        assertFalse(assess(previous).evaluated)
        assertFalse(assess(previous.copy(online = false)).sensorConnectionHealthy)
        assertFalse(assess(previous.copy(updatedAtEpochMs = now - 31 * 60_000L)).sensorConnectionHealthy)
        assertFalse(assess(shelly.copy(updatedAtEpochMs = previous.updatedAtEpochMs)).sensorConnectionHealthy)
        assertFalse(assessTemperature(listOf(govee), 80.0, 50.0, now).evaluated)
    }

    @Test fun reporterSerializesMeasuredBreachAndDistinguishesClearFromUnavailable() = runBlocking {
        val sent = mutableListOf<JSONObject>()
        val transport = OkHttpClient.Builder().addInterceptor { chain ->
            val body = Buffer().also { requireNotNull(chain.request().body).writeTo(it) }
            sent.add(JSONObject(body.readUtf8()))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body("{}".toResponseBody()).build()
        }.build()
        val api = AuthenticatedCloudGateway(CloudApiConfig("https://fixture.invalid", "server-01"),
            deviceSecret = "fixture", client = transport)
        val reporter = HeartbeatReporter(api) { now }
        for (assessment in listOf(assess(govee), assess(govee.copy(temperatureF = 55.0)), assess())) {
            reporter.report(MonitoringHealth("server-01", "house-1", assessment.sensorConnectionHealthy,
                assessment.breach != null, temperatureEvaluated = assessment.evaluated, temperatureBreach = assessment.breach))
        }
        val evidence = sent.first().getJSONObject("temperatureBreach")
        assertEquals("West bench", evidence.getString("sensorName"))
        assertEquals("LOW", evidence.getString("direction"))
        assertEquals(40.0, evidence.getDouble("temperatureF"), 0.0)
        assertEquals(45.0, evidence.getDouble("limitF"), 0.0)
        assertEquals((now - 1000) / 1000, evidence.getLong("measuredAt"))
        assertEquals(listOf(true, false, false), sent.map { it.getBoolean("criticalTemperature") })
        assertEquals(listOf(true, true, false), sent.map { it.getBoolean("temperatureEvaluated") })
        assertTrue(sent.drop(1).all { it.isNull("temperatureBreach") })
    }

    @Test fun onlyAnExplicitVerifiedCompleteInboxMayRetireTemperatureNotifications() = runBlocking {
        val responses = listOf(
            """{"alerts":[],"criticalIncidentsVerified":true}""",
            """{"alerts":[],"criticalIncidentsVerified":true,"complete":false}""",
            """{"alerts":[],"criticalIncidentsVerified":true,"complete":true}""",
            """{"criticalIncidentsVerified":true,"complete":true}"""
        ).iterator()
        val transport = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200)
                .message("OK").body(responses.next().toResponseBody()).build()
        }.build()
        val api = AuthenticatedCloudGateway(CloudApiConfig("https://fixture.invalid", "client-01"),
            CloudTokenProvider { "fixture" }, client = transport)
        assertFalse(api.pendingAlerts(true).criticalIncidentsVerified)
        assertFalse(api.pendingAlerts(true).complete)
        assertTrue(api.pendingAlerts(true).criticalIncidentsVerified)
        assertTrue(runCatching { api.pendingAlerts(true) }.isFailure)
    }

    @Test fun duplicateTemperatureEventsCollapseOnlyWithinOneServerAndGreenhouse() {
        val first = MonitoringAlert("a", "house1", MonitoringAlertType.CriticalTemperature, MonitoringAlertPriority.Critical,
            "temperature", 1000, true, "server1")
        val latest = first.copy(id = "b", occurredAtEpochMs = 1200)
        val independent = first.copy(id = "other", serverId = "server2", greenhouseId = "house2")
        assertEquals(setOf(latest, independent), currentAlerts(listOf(latest, independent, first), null).toSet())
    }
}
