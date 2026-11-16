package studio.junecity.greenhouse.ui

import android.app.NotificationManager
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.os.Build
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import studio.junecity.greenhouse.MainActivity
import studio.junecity.greenhouse.cloud.*
import studio.junecity.greenhouse.data.AlertDestination
import studio.junecity.greenhouse.settings.CloudEnrollment
import studio.junecity.greenhouse.settings.SecureSettingsStore
import java.io.File

class ClientAlarmPromptTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val secure = SecureSettingsStore(context)
    private val owner = CloudEnrollment("alarm-ui-viewer", "test-house", CloudDeviceRole.ViewerClient, tenantId = "alarm-ui-tenant")
    private val incident = MonitoringAlert("alarm-ui-1", owner.greenhouseId, MonitoringAlertType.CriticalTemperature,
        MonitoringAlertPriority.Critical, "Bench sensor measured 41°F, below the low limit of 45°F.", System.currentTimeMillis(), true)

    @Before fun enroll() {
        assumeTrue("Use an unsigned-in disposable emulator",
            Build.MODEL.contains("sdk", ignoreCase = true) && secure.cloudEnrollment() == null)
        secure.saveCloudEnrollment(owner)
    }

    @After fun release() {
        if (secure.cloudEnrollment() == owner) secure.clearCloudEnrollment()
    }

    @Test fun notificationOpensAppAndExplicitStopWorksWhileReconnectingAfterRecovery() {
        assumeTrue(CloudRuntime.configurationOrNull() != null)
        ClientTemperatureAlarms.deliver(context, owner, AlertInbox(listOf(incident), true, true))
        ClientTemperatureAlarms.deliver(context, owner, AlertInbox(emptyList(), true, true))
        compose.activityRule.scenario.recreate()
        await("Stop alarm")
        // After a cold activity start the fixture has no cloud session. Only the local stop control is exposed.
        compose.onNodeWithText(incident.message).assertDoesNotExist()
        val notice = context.getSystemService(NotificationManager::class.java).activeNotifications.single {
            it.id == incident.id.hashCode() && it.tag != null
        }
        val action = notice.notification.actions.single()
        assertEquals("Stop alarm", action.title.toString())
        val activity = compose.activity
        val launcherIntent = Intent(activity.intent)
        try {
            instrumentation.runOnMainSync { notice.notification.contentIntent.send() }
            compose.waitUntil(5_000) {
                var opened = false
                instrumentation.runOnMainSync { opened = AlertDestination.from(activity.intent)?.time == incident.occurredAtEpochMs }
                opened
            }
            compose.onNodeWithText("Stop alarm").performClick()
            compose.waitUntil(5_000) { ClientTemperatureAlarms.active(context, owner).isEmpty() }
            assertTrue(ClientTemperatureAlarms.pendingAcknowledgements(context, owner).contains(incident.id))
            instrumentation.runOnMainSync { assertNull(AlertDestination.from(activity.intent)) }
        } finally {
            // ActivityScenario tracks launcher activities by their original intent during teardown.
            instrumentation.runOnMainSync { activity.intent = launcherIntent }
        }
        ClientTemperatureAlarms.deliver(context, owner, AlertInbox(listOf(incident), true, true))
        compose.onNodeWithText("Stop alarm").assertDoesNotExist()
    }

    @Test fun authenticatedPromptShowsOriginalReadingsAndStopsDisplayedIncidents() {
        compose.activityRule.scenario.onActivity { activity ->
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            activity.setContent {
                ClientAlarmPrompt(activeTemperatureAlarms(owner), owner, showReadings = true)
            }
        }
        ClientTemperatureAlarms.deliver(context, owner, AlertInbox(listOf(incident), true, true))
        await(incident.message)
        compose.onNodeWithText("Stop alarm").assertIsDisplayed()
        val image = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            File(context.cacheDir, "qa-critical-stop.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally { image.recycle() }
        val second = incident.copy(id = "alarm-ui-2", occurredAtEpochMs = incident.occurredAtEpochMs + 1000,
            message = "Roof sensor measured 41°F, below the low limit of 45°F.")
        ClientTemperatureAlarms.deliver(context, owner, AlertInbox(listOf(incident, second), true, true))
        await("Stop all alarms (2)")
        ClientTemperatureAlarms.deliver(context, owner, AlertInbox(emptyList(), true, true))
        // In short landscape windows the lazy list only composes one incident at a time.
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(1)
        compose.onNodeWithText(second.message).assertIsDisplayed()
        compose.onNodeWithContentDescription("Stop alarm. ${second.message}").performClick()
        await("Stop alarm")
        compose.onNodeWithText(second.message).assertDoesNotExist()
        compose.onNodeWithText(incident.message).assertExists()
        assertEquals(setOf(second.id), ClientTemperatureAlarms.pendingAcknowledgements(context, owner))
        compose.onNodeWithText("Stop alarm").performClick()
        compose.waitUntil(5_000) { ClientTemperatureAlarms.active(context, owner).isEmpty() }
        assertEquals(setOf(incident.id, second.id), ClientTemperatureAlarms.pendingAcknowledgements(context, owner))
    }

    private fun await(label: String) = compose.waitUntil(8_000) {
        compose.onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty()
    }
}
