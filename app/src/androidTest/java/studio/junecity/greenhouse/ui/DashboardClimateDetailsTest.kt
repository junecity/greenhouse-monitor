package studio.junecity.greenhouse.ui

import android.content.pm.ActivityInfo
import android.os.Build
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import studio.junecity.greenhouse.MainActivity
import studio.junecity.greenhouse.cloud.CloudDeviceRole
import studio.junecity.greenhouse.data.HistoryStore
import studio.junecity.greenhouse.model.*
import studio.junecity.greenhouse.settings.*
import java.time.LocalDateTime

class DashboardClimateDetailsTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun gaugeOpensScopedLiveRepositoryAndReturnsToFanControls() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val secure = SecureSettingsStore(app)
        val suffix = "_standalone"
        val inventory = CurrentSensorStore(app, suffix)
        assumeTrue("Use a disposable, unenrolled emulator", Build.MODEL.contains("sdk", true) &&
            secure.cloudEnrollment() == null && inventory.load().isEmpty())
        val prefsStore = AppPreferencesStore(app, suffix)
        val previous = prefsStore.load()
        val owner = CloudEnrollment("dashboard-widget-fixture", "", CloudDeviceRole.ViewerClient,
            localSensors = true, tenantId = "dashboard-widget-fixture")
        val house = GreenhouseConfig(id = "card-east", name = "East Greenhouse")
        val other = GreenhouseConfig(id = "card-west", name = "West Greenhouse")
        val prefs = AppPreferences(greenhouses = listOf(house, other), activeGreenhouseId = house.id)
        val now = System.currentTimeMillis()
        val bench = GoveeSensor("details-test-east", "H5179", "East bench", 68.0, 71.0, true, now, house.id)
        val westBench = bench.copy(id = "details-test-west", temperatureF = 90.0, greenhouseId = other.id)
        val history = HistoryStore(app, suffix)
        try {
            secure.saveCloudEnrollment(owner)
            studio.junecity.greenhouse.seedMonitoredPlan(app,owner,prefs)
            prefsStore.save(prefs)
            history.insert(bench.copy(temperatureF = 66.0, updatedAtEpochMs = now - 360_000L))
            history.insert(bench)
            history.insert(westBench)
            inventory.save(listOf(bench, westBench))
            val dashboard = GreenhouseUiState(standalone = true, preferences = prefs, sensors = listOf(bench, westBench))
            compose.activityRule.scenario.onActivity { activity ->
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                activity.setContent {
                    DashboardClimateDetails(owner, house.id, {}) { open ->
                        DashboardScreen(dashboard, DeviceStatus(80, true, true), LocalDateTime.now(), {}, onMonitoring = open)
                    }
                }
            }
            compose.onNodeWithText("Climate details ›").assertHasClickAction().performClick()
            fun inDialog(text: String) = hasText(text) and hasAnyAncestor(isDialog())
            compose.waitUntil(5_000) {
                compose.onAllNodes(inDialog("68°F"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNode(inDialog("71%"), useUnmergedTree = true).assertIsDisplayed()
            compose.onNode(inDialog("66°F → 68°F"), useUnmergedTree = true).assertIsDisplayed()
            compose.onNode(inDialog("90°F"), useUnmergedTree = true).assertDoesNotExist()
            // Only update the persistent source; the open card should observe it without another tap.
            inventory.save(listOf(bench.copy(temperatureF = 70.0), westBench))
            compose.waitUntil(5_000) {
                compose.onAllNodes(inDialog("70°F"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithContentDescription("Close live readings").performClick()
            compose.onNode(isDialog()).assertDoesNotExist()
            compose.onNodeWithText("FAN").performClick()
            compose.onNode(inDialog("Air circulation"), useUnmergedTree = true).assertIsDisplayed()
            compose.onNodeWithText("Close").performClick()
            compose.onNodeWithText("Climate details ›").assertIsDisplayed()
        } finally {
            secure.clearCloudEnrollment()
            inventory.clear()
            prefsStore.save(previous)
            val ids = arrayOf(bench.id, westBench.id)
            history.use {
                for (table in listOf("readings", "daily_last")) {
                    it.writableDatabase.delete(table, "sensor_id IN (?, ?)", ids)
                }
            }
        }
    }
}
