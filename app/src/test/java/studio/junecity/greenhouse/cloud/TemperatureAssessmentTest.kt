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
        assertTrue(assess(govee.copy(temperatureF = 45.1), shelly.copy(temperatureF = 99.9)).evaluated)
        assertNull(assess(govee.copy(temperatureF = 45.1), shelly.copy(temperatureF = 99.9)).breach)
        assertTrue(assess(govee, shelly.copy(online = false)).evaluated)
    }

    @Test fun humidityUsesFreshRelativeHumidityIndependentlyOfTemperatureAndIncludesTheLimits() {
        val low = govee.copy(temperatureF = null, humidityPercent = 30.0)
        val high = shelly.copy(humidityPercent = 85.0)
        val invalid = listOf(govee.copy(id = "old", updatedAtEpochMs = now - 600_001),
            govee.copy(id = "future", updatedAtEpochMs = now + 1), govee.copy(id = "missing", humidityPercent = null),
            govee.copy(id = "invalid", humidityPercent = 102.0), govee.copy(id = "offline", online = false))
        val all = listOf(low, high, high.copy(humidityPercent = 50.0, updatedAtEpochMs = now - 2_000)) + invalid
        val observations = assessHumidity(all, 30.0, 85.0, true, now)
        assertEquals(listOf(govee.id, shelly.id), observations.map { it.sensorId })
        assertEquals(listOf(TemperatureBreach.Direction.LOW, TemperatureBreach.Direction.HIGH), observations.map { it.direction })
        assertTrue(observations.all { it.type == MonitoringAlertType.CriticalHumidity && it.measuredAtEpochMs == now - 1_000 })
        assertTrue(assessHumidity(all, 30.0, 85.0, false, now).isEmpty())
        assertTrue(assessHumidity(all, 85.0, 30.0, true, now).isEmpty())
    }

    @Test fun limitsTriggerOnFirstObservationAndOnlyStrictlyInsideRetiresTheSensor() {
        val atLow = govee.copy(temperatureF = 45.0)
        val atHigh = shelly.copy(temperatureF = 100.0)
        assertEquals(TemperatureBreach.Direction.LOW, assess(atLow).breach?.direction)
        assertEquals(TemperatureBreach.Direction.HIGH, assess(atHigh).breach?.direction)
        val both = assess(atHigh, atLow).outstandingSensorIds
        assertEquals(setOf(atLow.id, atHigh.id), both)
        assertEquals(both, assess(govee, shelly).outstandingSensorIds)
        val inside = assessTemperature(listOf(atLow.copy(temperatureF = 45.1), atHigh),
            45.0, 100.0, now, both)
        assertEquals(setOf(atHigh.id), inside.outstandingSensorIds)
    }

    @Test fun snapshotTriggerTracksPerSensorDirectionRatherThanEveryChangeOfTemperature() {
        val first = govee.copy(temperatureF = 100.0)
        val second = shelly.copy(temperatureF = 75.0)
        val high = TemperatureBreach.Direction.HIGH
        val low = TemperatureBreach.Direction.LOW
        assertEquals(mapOf(first.id to high), assess(first, second).breachDirections)
        assertEquals(mapOf(first.id to high), assess(first.copy(temperatureF = 104.0), second).breachDirections)
        assertEquals(mapOf(first.id to high, second.id to high),
            assess(first, second.copy(temperatureF = 100.0)).breachDirections)
        assertEquals(mapOf(second.id to low),
            assess(first.copy(temperatureF = 98.0), second.copy(temperatureF = 44.0)).breachDirections)
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

    @Test fun equalTemperaturesAndDuplicateDisplayNamesStillReportBothSensorIdentities() {
        val a = govee.copy(temperatureF = 105.0, name = "Rack")
        val b = shelly.copy(temperatureF = 105.0, name = "Rack")
        val first = assess(a, b.copy(temperatureF = 80.0))
        assertEquals(listOf(105.0, 80.0), first.observations.map { it.value })
        val second = assess(a, b)
        assertEquals(setOf(a.id, b.id), second.observations.map { it.sensorId }.toSet())
        assertTrue(second.observations.all { it.value > it.high && it.sensorName == "Rack" })
        // Omitting A or observing it offline must not send any healthy observation for A.
        for (samples in listOf(listOf(b), listOf(a.copy(online = false), b))) {
            val observed = assessTemperature(samples, 45.0, 100.0, now, setOf(a.id, b.id)).observations
            assertEquals(listOf(b.id), observed.map { it.sensorId })
        }
        val recovered = assess(a.copy(temperatureF = 100.0, updatedAtEpochMs = now), a, b)
        assertEquals(100.0, recovered.observations.single { it.sensorId == a.id }.value, 0.0)
        assertEquals(105.0, recovered.observations.single { it.sensorId == b.id }.value, 0.0)
    }

    @Test fun everyFreshSensorReportsEvenIfNoOtherSensorRecoversOrHasAFreshReading() {
        val many = (1..50).map { govee.copy(id = "shelly:$it", name = "Bench", temperatureF = 102.0) }
        val stale = shelly.copy(updatedAtEpochMs = now - 600_001)
        val result = assessTemperature(many + stale, 45.0, 100.0, now)
        assertEquals(many.map { it.id }.toSet(), result.observations.map { it.sensorId }.toSet())
        assertTrue(assessTemperature(many, 100.0, 45.0, now).observations.isEmpty())
        assertTrue(assess(stale, govee.copy(temperatureF = Double.NaN)).observations.isEmpty())
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
                assessment.breach != null, temperatureEvaluated = assessment.evaluated, temperatureBreach = assessment.breach,
                temperatureObservations = assessment.observations, humidityAlertsEnabled = assessment.evaluated,
                humidityObservations = assessHumidity(listOf(shelly), 30.0, 70.0, assessment.evaluated, now)))
        }
        val humidity = sent.first().getJSONArray("humidityObservations").getJSONObject(0)
        assertEquals(shelly.id, humidity.getString("sensorId"))
        assertEquals(75.0, humidity.getDouble("humidityPercent"), 0.0)
        assertEquals(30.0, humidity.getDouble("lowHumidity"), 0.0)
        assertEquals(70.0, humidity.getDouble("highHumidity"), 0.0)
        assertEquals(shelly.updatedAtEpochMs, humidity.getLong("measuredAtEpochMs"))
        assertFalse(sent.last().getBoolean("humidityAlertsEnabled"))
        assertEquals(0, sent.last().getJSONArray("humidityObservations").length())
        val evidence = sent.first().getJSONObject("temperatureBreach")
        assertEquals("West bench", evidence.getString("sensorName"))
        assertEquals("LOW", evidence.getString("direction"))
        assertEquals(40.0, evidence.getDouble("temperatureF"), 0.0)
        assertEquals(45.0, evidence.getDouble("limitF"), 0.0)
        assertEquals((now - 1000) / 1000, evidence.getLong("measuredAt"))
        assertEquals(listOf(true, false, false), sent.map { it.getBoolean("criticalTemperature") })
        assertEquals(listOf(true, true, false), sent.map { it.getBoolean("temperatureEvaluated") })
        assertTrue(sent.drop(1).all { it.isNull("temperatureBreach") })
        val reading = sent.first().getJSONArray("temperatureObservations").getJSONObject(0)
        assertEquals(govee.id, reading.getString("sensorId"))
        assertEquals(govee.name, reading.getString("sensorName"))
        assertEquals(40.0, reading.getDouble("temperatureF"), 0.0)
        assertEquals(45.0, reading.getDouble("lowF"), 0.0)
        assertEquals(100.0, reading.getDouble("highF"), 0.0)
        assertEquals(govee.updatedAtEpochMs, reading.getLong("measuredAtEpochMs"))
        assertEquals(55.0, sent[1].getJSONArray("temperatureObservations").getJSONObject(0).getDouble("temperatureF"), 0.0)
        assertEquals(0, sent.last().getJSONArray("temperatureObservations").length())
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
