package studio.junecity.greenhouse.cloud

import android.app.NotificationManager
import android.content.ComponentName
import android.content.ContextWrapper
import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import studio.junecity.greenhouse.settings.CloudEnrollment
import studio.junecity.greenhouse.settings.SecureSettingsStore
import java.util.UUID
import java.util.concurrent.TimeUnit

class CloudAlertWorkerTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val security = SecureSettingsStore(context)
    private val owner = CloudEnrollment("viewer-${UUID.randomUUID()}", "house-a", CloudDeviceRole.ViewerClient, tenantId = "test")
    private val jobs = WorkManager.getInstance(context)
    private val notifications = context.getSystemService(NotificationManager::class.java)

    @Before fun freshViewer() {
        require(security.cloudEnrollment() == null) { "Run on a fresh test profile." }
        security.saveCloudEnrollment(owner)
    }

    @After fun clear() {
        CloudAlertService.sync(context, false)
        security.clearCloudEnrollment()
    }

    @Test fun rejectedForegroundStartSchedulesOneDurableWatchAndAnImmediateCheck() {
        val denied = object : ContextWrapper(context) {
            override fun startForegroundService(service: Intent): ComponentName? {
                throw IllegalStateException("background launch is restricted")
            }
        }
        repeat(2) { CloudAlertService.sync(denied, true) }
        fun unfinished() = jobs.getWorkInfosForUniqueWork(CloudAlertWorker.PERIODIC).get(5, TimeUnit.SECONDS)
            .filterNot { it.state.isFinished }
        assertEquals(1, unfinished().size)
        assertTrue(jobs.getWorkInfosForUniqueWork(CloudAlertWorker.IMMEDIATE).get(5, TimeUnit.SECONDS).isNotEmpty())
        CloudAlertService.sync(context, false)
        assertTrue(unfinished().isEmpty())
    }

    @Test fun tearingDownMonitorDoesNotSilenceAnUnacknowledgedIncident() {
        val prefs = context.getSharedPreferences("client_alert_audio", 0)
        val previousTone = prefs.getString("critical_temperature", null)
        val previousLoud = prefs.all["loud_alarm"] as Boolean?
        try {
            TemperatureSound.Pulse.select(context)
            CriticalAlarm.setEnabled(context, true)
            val alert = MonitoringAlert("alert-teardown", owner.greenhouseId, MonitoringAlertType.CriticalTemperature,
                MonitoringAlertPriority.Critical, "Roof reached 30°F.", System.currentTimeMillis(), true, sensorId = "roof")
            ClientTemperatureAlarms.deliver(context, owner, AlertInbox(listOf(alert), true, true))
            CloudAlertService.sync(context, true)
            val activityManager = context.getSystemService(android.app.ActivityManager::class.java)
            @Suppress("DEPRECATION")
            fun monitorRunning() = activityManager.getRunningServices(Int.MAX_VALUE)
                .any { it.service.className == CloudAlertService::class.java.name && it.foreground }
            fun waitFor(condition: () -> Boolean) {
                repeat(100) { if (condition()) return else android.os.SystemClock.sleep(30) }
                assertTrue(condition())
            }
            waitFor { monitorRunning() && CriticalAlarm.isIncidentPlaying() }
            context.stopService(Intent(context, CloudAlertService::class.java))
            waitFor { !monitorRunning() }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            assertTrue(CriticalAlarm.isIncidentPlaying())
            ClientTemperatureAlarms.stop(context, owner, setOf(alert.id))
            assertFalse(CriticalAlarm.isIncidentPlaying())
        } finally {
            security.clearCloudEnrollment()
            val edit = prefs.edit().putString("critical_temperature", previousTone)
            if (previousLoud == null) edit.remove("loud_alarm") else edit.putBoolean("loud_alarm", previousLoud)
            edit.commit()
        }
    }

    @Test fun recoveryPollDeliversWithoutActivityAndRefusesAStaleOwnerResponse() = runBlocking {
        val alert = MonitoringAlert("alert-A", "house-a", MonitoringAlertType.CriticalTemperature,
            MonitoringAlertPriority.Critical, "Bench reached 30°F.", System.currentTimeMillis(), true, sensorId = "bench")
        val inbox = AlertInbox(listOf(alert), complete = true, criticalIncidentsVerified = true)
        var requests = 0
        suspend fun poll() = CloudAlertDelivery.poll(context, owner, 60_000L, { requests++; inbox }, {})
        repeat(2) { assertTrue(poll()) }
        assertEquals(1, requests)
        assertEquals(listOf(alert), ClientTemperatureAlarms.active(context, owner))
        assertTrue(notifications.activeNotifications.any { ClientTemperatureAlarms.matchesNotification(owner, alert, it.tag) })

        val other = owner.copy(deviceId = "other", tenantId = "another-customer")
        CloudAlertDelivery.poll(context, owner, 0, {
            security.saveCloudEnrollment(other)
            inbox
        }, {})
        assertTrue(ClientTemperatureAlarms.active(context, other).isEmpty())
        assertTrue(notifications.activeNotifications.none { ClientTemperatureAlarms.matchesNotification(owner, alert, it.tag) })
    }
}
