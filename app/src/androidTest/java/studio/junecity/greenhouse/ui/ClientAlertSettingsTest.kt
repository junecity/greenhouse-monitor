package studio.junecity.greenhouse.ui

import android.content.pm.ActivityInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import java.time.LocalDateTime
import java.util.concurrent.atomic.AtomicReference
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import studio.junecity.greenhouse.MainActivity
import studio.junecity.greenhouse.cloud.*
import studio.junecity.greenhouse.model.*
import studio.junecity.greenhouse.settings.CloudEnrollment
import studio.junecity.greenhouse.settings.SecureSettingsStore

class ClientAlertSettingsTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun pairedSettingsValidateSaveWaitAndReloadConflictingRules() {
        val secure = SecureSettingsStore(context)
        assumeTrue("Use a disposable emulator", android.os.Build.MODEL.contains("sdk", true) && secure.cloudEnrollment() == null)
        val owner = CloudEnrollment("client-alert-fixture", "greenhouse-alert-fixture", CloudDeviceRole.ViewerClient)
        var revision = 1
        var applied = 1
        var stored = AlertRules.from(NotificationPreferences())
        val apiFixtureError = AtomicReference<String?>()
        val api = AuthenticatedCloudGateway(CloudApiConfig("https://greenhouse.invalid", owner.deviceId),
            tokens = CloudTokenProvider { "fixture" }, client = OkHttpClient.Builder().addInterceptor { call ->
                val req = call.request()
                if (req.url.encodedPath != "/v1/greenhouses/${owner.greenhouseId}/alert-rules" ||
                    req.header("x-greenhouse-device-id") != owner.deviceId) {
                    apiFixtureError.set("Unexpected target or device")
                }
                var status = 200
                if (req.method == "POST") {
                    val body = Buffer().also { req.body!!.writeTo(it) }.readUtf8()
                    val update = JSONObject(body)
                    if (revision != update.getInt("expectedRevision")) {
                        status = 409
                    } else {
                        stored = AlertRules.parse(update.getJSONObject("rules"))
                        revision++
                    }
                }
                val json = JSONObject().put("rules", stored.toJson()).put("revision", revision).put("appliedRevision", applied)
                Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(status).message("Fixture")
                    .body(json.toString().toResponseBody("application/json".toMediaType())).build()
            }.build())
        try {
            compose.activityRule.scenario.onActivity { activity ->
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                secure.saveCloudEnrollment(owner)
                activity.setContent {
                    CompositionLocalProvider(LocalAlertGateway provides { _, _ -> api }) {
                        StageScreen(GreenhouseUiState(screen = AppScreen.Settings), DeviceStatus(80,true,true),
                            LocalDateTime.now(), {}, {}, {}, {}, { _,_,_ -> }, { _,_ -> }, {}, {},
                            { _,_,_ -> }, { error("Viewer rules must go through the paired server API") }, Modifier.fillMaxSize())
                    }
                }
            }
            click("General")
            click("Alerts")
            await("Applied on greenhouse server")
            val low = compose.onNodeWithText("Low °F")
            low.performScrollTo().performTextReplacement("100")
            compose.onNodeWithText("Save alerts").assertIsNotEnabled()
            low.performTextReplacement("53.5")
            compose.onNodeWithText("High % RH").performScrollTo().performTextReplacement("79")
            val originalHumidityAlerts = stored.humidityAlerts
            click("Humidity alarms")
            click("Save alerts")
            await("Saved · Waiting for greenhouse server")
            assertEquals(53.5, stored.lowTemperatureF, 0.0)
            assertEquals(79.0, stored.highHumidity, 0.0)
            assertEquals(!originalHumidityAlerts, stored.humidityAlerts)
            compose.onNodeWithText("Test sound").performScrollTo().assertExists()

            // A second device saves before this editor finishes. The stale draft cannot overwrite it.
            applied = ++revision
            stored = stored.copy(lowTemperatureF = 51.0)
            low.performScrollTo().performTextReplacement("54")
            click("Save alerts")
            await("Reload limits")
            click("Reload limits")
            await("Applied on greenhouse server")
            low.assertTextContains("51")
            assertEquals(51.0, stored.lowTemperatureF, 0.0)
            assertNull(apiFixtureError.get())
        } finally { secure.clearCloudEnrollment() }
    }

    private fun click(label: String) {
        val node = compose.onNodeWithText(label)
        if (label != "General" && label != "Alerts") node.performScrollTo()
        node.performClick()
    }

    private fun await(text: String) = compose.waitUntil(8_000) {
        compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }
}
