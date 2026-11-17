package studio.junecity.greenhouse.widget

import android.Manifest
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.os.Build
import android.widget.TextView
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import studio.junecity.greenhouse.MainActivity
import studio.junecity.greenhouse.R
import studio.junecity.greenhouse.cloud.CloudDeviceRole
import studio.junecity.greenhouse.model.*
import studio.junecity.greenhouse.settings.*
import studio.junecity.greenhouse.ui.DashboardScreen
import java.time.LocalDateTime

class WidgetDashboardAverageTest {
    @get:Rule(order = 0) val notifications: TestRule = if (Build.VERSION.SDK_INT >= 33)
        GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS) else TestRule { base, _ -> base }
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext
    private val manager = AppWidgetManager.getInstance(app)
    private val host = AppWidgetHost(app, 4290)

    private fun pin(owner: CloudEnrollment, sensor: String? = null): Int {
        val id = host.allocateAppWidgetId()
        instrumentation.uiAutomation.adoptShellPermissionIdentity(Manifest.permission.BIND_APPWIDGET)
        try {
            assertTrue(manager.bindAppWidgetIdIfAllowed(id, ComponentName(app, ClimateWidget::class.java)))
        } finally {
            instrumentation.uiAutomation.dropShellPermissionIdentity()
        }
        ClimateWidget.settings(app, id).edit().putString("scope", owner.widgetScope())
            .putString("sensor", sensor).putBoolean("celsius", false).commit()
        return id
    }

    private fun widgetTemperature(id: Int): String? {
        var value: String? = null
        instrumentation.runOnMainSync {
            val rendered = host.createView(app, id, manager.getAppWidgetInfo(id))
            value = rendered.findViewById<TextView>(R.id.widget_temperature)?.text?.toString()
        }
        return value
    }

    private fun verifyReadings(whole: Int, sensor: Int, houseName: String, expectedAverage: String, expectedSensor: String) {
        val gauge = compose.onNode(hasText(houseName) and hasText("Climate details ›"))
        val dashboard = gauge.fetchSemanticsNode().config[SemanticsProperties.Text].single { it.text.endsWith("°F") }.text
        assertEquals("Dashboard greenhouse gauge", expectedAverage, dashboard)
        assertEquals("The gauge and Avg Temp card both display the greenhouse average", 2,
            compose.onAllNodesWithText(dashboard, useUnmergedTree = true).fetchSemanticsNodes().size)
        var lastWhole: String? = null
        try {
            compose.waitUntil(5_000) {
                lastWhole = widgetTemperature(whole)
                lastWhole == dashboard && widgetTemperature(sensor) == expectedSensor
            }
        } catch (timeout: ComposeTimeoutException) {
            throw AssertionError("Dashboard = $dashboard, greenhouse widget = $lastWhole, sensor widget = ${widgetTemperature(sensor)}", timeout)
        }
    }

    @Test fun savedReadingsProduceTheSameAverageOnDashboardAndHomeScreen() {
        val secure = SecureSettingsStore(app)
        val prefsStore = AppPreferencesStore(app, "_standalone")
        val sensors = CurrentSensorStore(app, "_standalone")
        assumeTrue("Requires an isolated emulator", Build.MODEL.contains("sdk", true) &&
            secure.cloudEnrollment() == null && sensors.load().isEmpty())
        val previous = prefsStore.load()
        val owner = CloudEnrollment("average-widget-test", "", CloudDeviceRole.ViewerClient,
            localSensors = true, tenantId = "average-widget-test")
        val house = GreenhouseConfig(id = "east", name = "East Greenhouse", sensorPositions = mapOf(
            "base" to SensorPosition(1.0, 1.0), "top" to SensorPosition(14.0, 4.0),
            "door" to SensorPosition(2.0, 2.0), "expired" to SensorPosition(10.0, 4.0)))
        val other = GreenhouseConfig(id = "west", name = "West Greenhouse")
        val prefs = AppPreferences(greenhouses = listOf(house, other), activeGreenhouseId = house.id)
        val now = System.currentTimeMillis()
        fun reading(id: String, temp: Double, ageMinutes: Int, houseId: String = house.id) =
            GoveeSensor(id, "H5179", id, temp, 75.0, true, now - ageMinutes * 60_000L, houseId)
        val first = listOf(reading("base", 60.0, 0), reading("top", 84.0, 4), reading("door", 64.0, 0),
            reading("expired", 105.0, 20), reading("other-house", 40.0, 0, other.id))
        val state = mutableStateOf(GreenhouseUiState(preferences = prefs, sensors = first, standalone = true))
        try {
            secure.saveCloudEnrollment(owner)
            studio.junecity.greenhouse.seedMonitoredPlan(app,owner,prefs)
            prefsStore.save(prefs)
            val whole = pin(owner)
            val top = pin(owner, sensor = "top")
            sensors.save(first)
            compose.activityRule.scenario.onActivity { activity ->
                activity.setContent {
                    DashboardScreen(state.value, DeviceStatus(82, true, true), LocalDateTime.now(), {}, onMonitoring = {})
                }
            }
            // The solitary upper sensor covers more floor area than the two clustered at the door.
            verifyReadings(whole, top, house.name, "72°F", "84°F")
            val updated = first.map { if (it.id == "top") it.copy(temperatureF = 70.0, updatedAtEpochMs = System.currentTimeMillis()) else it }
            sensors.save(updated)
            compose.runOnIdle { state.value = state.value.copy(sensors = sensors.load()) }
            verifyReadings(whole, top, house.name, "66°F", "70°F")
        } finally {
            host.deleteHost()
            secure.clearCloudEnrollment()
            sensors.clear()
            prefsStore.save(previous)
        }
    }
}
