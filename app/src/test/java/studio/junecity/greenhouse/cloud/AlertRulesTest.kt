package studio.junecity.greenhouse.cloud

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import studio.junecity.greenhouse.model.*

class AlertRulesTest {
    private val rules = AlertRules(50.0, 90.0, 25.0, 86.0, true, false, true)
    private fun response(rev: Int = 2, applied: Int = 1) =
        JSONObject().put("revision", rev).put("rules", rules.toJson()).put("appliedRevision", applied)
    private fun body(request: Request) = JSONObject(Buffer().apply { request.body!!.writeTo(this) }.readUtf8())
    private fun client(respond: (Request) -> Pair<Int, String>) = OkHttpClient.Builder().addInterceptor {
        val req = it.request()
        val (status, text) = respond(req)
        Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(status).message("test").body(text.toResponseBody()).build()
    }.build()

    @Test fun onlySelectedHouseThresholdsAndSensorSwitchesAreChanged() {
        val phone = NotificationPreferences(soundEnabled = true, repeatedAlerts = true, snoozedUntilEpochMs = 1234)
        val prefs = AppPreferences(greenhouses = listOf(GreenhouseConfig(), GreenhouseConfig("annex")), notifications = phone)
        val changed = prefs.withAlertRules("annex", rules)
        assertEquals(phone, changed.forAlertsIn("main"))
        assertEquals(rules.applyTo(phone), changed.forAlertsIn("annex"))
        assertTrue(changed.forAlertsIn("annex").soundEnabled)
        assertEquals(1234L, changed.forAlertsIn("annex").snoozedUntilEpochMs)
        val remote = rules.copy(lowTemperatureF = 57.0, humidityAlerts = true)
        val monitoring = changed.copy(serverAlertRules = remote)
        assertEquals(remote.applyTo(phone), monitoring.forAlertsIn("main"))
        assertEquals(rules.applyTo(phone), monitoring.forAlertsIn("annex"))
        assertTrue(runCatching { prefs.withAlertRules("absent-house", rules) }.isFailure)
    }

    @Test fun invalidValuesAndImpossibleApplicationStatusesAreRejected() {
        assertTrue(rules.copy(lowTemperatureF = -150.0, highTemperatureF = 250.0, lowHumidity = 0.0, highHumidity = 100.0).valid())
        for (invalid in listOf(rules.copy(lowTemperatureF = Double.NaN), rules.copy(highTemperatureF = 251.0),
                rules.copy(lowTemperatureF = rules.highTemperatureF), rules.copy(highHumidity = 101.0),
                rules.copy(lowHumidity = 90.0))) assertFalse(invalid.valid())
        assertTrue(runCatching { AlertRuleState.parse(response(1, 2)) }.isFailure)
        assertTrue(runCatching { AlertRuleState.parse(response(0, 0)) }.isFailure)
        assertEquals(AlertRuleState(0, null, 0), AlertRuleState.parse(JSONObject("""{"revision":0,"rules":null,"appliedRevision":0}""")))
    }

    @Test fun clientAndDeviceReadAndWriteThroughTheirAuthorizedHouseRoutes() = runBlocking {
        for (server in listOf(false, true)) {
            val requests = mutableListOf<Request>()
            val transport = client { requests += it; 200 to response().toString() }
            val config = CloudApiConfig("https://fixture.invalid", "phone-one")
            val gateway = if (server) AuthenticatedCloudGateway(config, deviceSecret = "fixture", client = transport)
                else AuthenticatedCloudGateway(config, CloudTokenProvider { "fixture" }, client = transport)
            val current = gateway.alertRules("greenhouse-2")
            val saved = gateway.saveAlertRules("greenhouse-2", current.revision, rules)
            assertEquals(1, saved.appliedRevision)
            assertEquals(listOf("GET", "POST"), requests.map { it.method })
            for (request in requests) {
                assertEquals(if (server) "/v1/device/greenhouses/greenhouse-2/alert-rules" else "/v1/greenhouses/greenhouse-2/alert-rules", request.url.encodedPath)
                assertEquals("phone-one", request.header("x-greenhouse-device-id"))
                assertEquals(if (server) null else "Bearer fixture", request.header("Authorization"))
                assertEquals(if (server) "fixture" else null, request.header("x-greenhouse-device-secret"))
            }
            val payload = body(requests.last())
            assertEquals(2, payload.getInt("expectedRevision"))
            assertEquals(rules, AlertRules.parse(payload.getJSONObject("rules")))
        }
    }

    @Test fun competingEditFailsWithoutSubmittingAgain() = runBlocking {
        var writes = 0
        val gateway = AuthenticatedCloudGateway(CloudApiConfig("https://fixture.invalid", "client-01"), CloudTokenProvider { "fixture" },
            client = client { writes++; 409 to """{"message":"Settings changed"}""" })
        val failure = runCatching { gateway.saveAlertRules("greenhouse-2", 2, rules) }.exceptionOrNull()
        assertEquals(409, (failure as CloudApiException).statusCode)
        assertEquals(1, writes)
    }

    @Test fun invalidLegacyRulesStillReceiveCollectorRevisionAndUpdateCommand() = runBlocking {
        val sent = mutableListOf<JSONObject>()
        val transport = client {
            sent += body(it)
            if (sent.size == 3) 204 to "" else 200 to JSONObject().put("requestedUpdateCode", 99)
                .put("alertRules", response()).toString()
        }
        val api = AuthenticatedCloudGateway(CloudApiConfig("https://fixture.invalid", "server-01"), deviceSecret = "fixture", client = transport)
        val heartbeat = GreenhouseHeartbeat("server-01", "house-one", 1000, true, false)
        val first = api.reportHeartbeat(heartbeat.copy(appliedAlertRulesRevision = 0))
        assertTrue(sent.first().has("alertRules") && sent.first().isNull("alertRules"))
        assertEquals(0, sent.first().getInt("appliedAlertRulesRevision"))
        assertEquals(99, first.update!!.versionCode)
        assertEquals(rules, first.alertRules!!.rules)
        api.reportHeartbeat(heartbeat.copy(alertRules = first.alertRules.rules, appliedAlertRulesRevision = first.alertRules.revision))
        assertEquals(rules, AlertRules.parse(sent[1].getJSONObject("alertRules")))
        assertEquals(2, sent[1].getInt("appliedAlertRulesRevision"))
        assertEquals(HeartbeatReply(), api.reportHeartbeat(heartbeat))
        assertFalse(sent[2].has("alertRules"))
    }
}
