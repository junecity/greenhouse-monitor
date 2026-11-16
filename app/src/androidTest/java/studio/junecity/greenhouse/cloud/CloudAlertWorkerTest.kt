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

    @Test fun shutdownImmediatelyAfterStartWaitsForForegroundPromotion() {
        var prematureStops = 0
        val observed = object : ContextWrapper(context) {
            override fun stopService(service: Intent): Boolean {
                prematureStops++
                return super.stopService(service)
            }
        }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            // Android cannot dispatch service creation until this main-thread callback returns.
            CloudAlertService.sync(observed, true)
            CloudAlertService.sync(observed, false)
        }
        assertEquals(0, prematureStops)
        val manager = context.getSystemService(android.app.ActivityManager::class.java)
        @Suppress("DEPRECATION")
        fun monitorPresent() = manager.getRunningServices(Int.MAX_VALUE)
            .any { it.service.className == CloudAlertService::class.java.name }
        repeat(100) {
            instrumentation.waitForIdleSync()
            if (!monitorPresent()) return
            android.os.SystemClock.sleep(30)
        }
        fail("The foreground monitor must finish the requested shutdown.")
    }

    @Test fun oldInventoryWaitsForNewReadingOnlyOnFirstDiscoveryOrWitnessedRevocation() {
        val first = System.currentTimeMillis()
        val device = RegisteredDevice("server", "test", CloudDeviceRole.GreenhouseServer, greenhouseId = "house-a")
        val legacy = AccountDevice(device, first - 86_400_000L, reportsEnrollmentTime = false)
        fun inspect(at: Long, vararg devices: AccountDevice) =
            ClientTemperatureAlarms.readingSources(context, owner, devices.toList(), at)
        assertEquals(first, inspect(first, legacy).single().enrollmentEpochMs)
        assertEquals(first, inspect(first + 40_000, legacy).single().enrollmentEpochMs)
        assertTrue(inspect(first + 41_000, legacy.copy(device = device.copy(enabled = false))).isEmpty())
        assertTrue(inspect(first + 42_000, legacy.copy(device = device.copy(tenantId = "other"))).isEmpty())
        assertEquals(first + 43_000, inspect(first + 43_000, legacy).single().enrollmentEpochMs)
        val otherHouse = legacy.copy(device = device.copy(greenhouseId = "house-b"))
        assertEquals(first + 50_000, inspect(first + 50_000, otherHouse).single().enrollmentEpochMs)
        assertEquals(first + 43_000, inspect(first + 51_000, legacy).single().enrollmentEpochMs)
    }

    @Test fun failedInboxStillRingsForIndependentFreshAuthorizedReadingsWhileAppClosed() = runBlocking {
        val now = System.currentTimeMillis()
        val server = AccountDevice(RegisteredDevice("server", "test", CloudDeviceRole.GreenhouseServer,
            greenhouseId = "house-a"), now - 600_000L, now - 60_000L)
        val sensors = org.json.JSONArray()
        fun sensor(id: String, reading: Double, at: Long = now, plan: String = "main") =
            org.json.JSONObject().put("id", id).put("name", id).put("online", true).put("greenhouseId", plan)
                .put("temperatureF", reading).put("updatedAtEpochMs", at)
        val backup = org.json.JSONObject().put("schema", 2)
            .put("serverHealth", org.json.JSONObject().put("reportedAt", now).put("network", "Wi-Fi").put("serverDeviceId", "server"))
            .put("preferences", org.json.JSONObject().put("active", "main")
                .put("greenhouses", org.json.JSONArray().put(org.json.JSONObject().put("id", "main")))
                .put("notifications", org.json.JSONObject().put("lowT", 45).put("highT", 82)))
            .put("sensors", sensors.put(sensor("a", 84.0)).put(sensor("other-house", 100.0, plan = "other"))
                .put(sensor("before-repair", 90.0, now - 61_000L)))
        assertNull(temperatureSnapshot(context, server.copy(enrollmentEpochMs = now + 1), backup, now))
        assertNull(temperatureSnapshot(context, server.copy(device = server.device.copy(id = "replacement")), backup, now))
        assertNull(temperatureSnapshot(context, server, backup, now + 600_001))
        suspend fun poll(inboxAvailable: Boolean): Boolean = CloudAlertDelivery.poll(context, owner, 0,
            { if (inboxAvailable) AlertInbox(emptyList(), true, true) else throw java.io.IOException("inbox unavailable") },
            { fail("Synthetic IDs must never be acknowledged against the backend") },
            { ViewerReadingCheck(listOf(requireNotNull(temperatureSnapshot(context, server, backup, now))), true) })
        assertFalse(poll(false))
        val a = ClientTemperatureAlarms.active(context, owner).single()
        assertEquals("a", a.sensorId)
        ClientTemperatureAlarms.stop(context, owner, setOf(a.id))
        sensors.put(sensor("b", 82.0))
        assertTrue(poll(true))
        val b = ClientTemperatureAlarms.active(context, owner).single()
        assertEquals("b", b.sensorId)
        assertTrue(b.message.contains("at the high limit"))
        ClientTemperatureAlarms.stop(context, owner, setOf(b.id))
        assertTrue(poll(true))
        assertTrue(ClientTemperatureAlarms.active(context, owner).isEmpty())
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
