package studio.junecity.greenhouse.cloud

import android.app.NotificationManager
import android.content.ComponentName
import android.content.ContextWrapper
import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
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

    @Test fun syncedHumidityReadingsPostIndependentAlarmsAndRearmAfterRecovery() = runBlocking {
        val now = System.currentTimeMillis()
        val device = JSONObject().put("id", "server").put("role", "GREENHOUSE_SERVER").put("tenantId", owner.tenantId)
            .put("greenhouseId", owner.greenhouseId).put("createdAtEpochMs", now - 120_000)
            .put("enrollmentEpochMs", now - 120_000).put("sensorTemperatureMode", true)
        val rules = JSONObject().put("lowT", 45).put("highT", 82).put("lowH", 35).put("highH", 85)
            .put("humidity", false).put("temp", false).put("master", false)
        val sensors = JSONArray()
        fun sensor(id: String, rh: Int, at: Long) = JSONObject().put("id", id).put("name", "Bench $id")
            .put("online", true).put("humidityPercent", rh).put("updatedAtEpochMs", at)
        sensors.put(sensor("A", 85, now - 5_000).put("temperatureF", 105.0)).put(sensor("B", 35, now - 5_000))
            .put(sensor("stale", 95, now - 600_001)).put(sensor("other", 95, now).put("greenhouseId", "other"))
        val health = JSONObject().put("network", "Wi-Fi").put("serverDeviceId", device.getString("id"))
        val backup = JSONObject().put("schema", 2).put("serverHealth", health).put("sensors", sensors)
            .put("preferences", JSONObject().put("active", "main")
                .put("greenhouses", JSONArray().put(JSONObject().put("id", "main")))
                .put("notifications", rules))
        val remote = JSONArray()
        val inbox = JSONObject().put("complete", true).put("criticalIncidentsVerified", true)
            .put("sensorTemperatureInbox", true).put("alerts", remote)
        var snapshotGets = 0
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val response = when (request.url.encodedPath) {
                "/v1/alerts" -> {
                    assertEquals("true", request.url.queryParameter("includeHumidity"))
                    inbox
                }
                "/v1/devices" -> JSONObject().put("devices", JSONArray().put(device))
                "/v1/greenhouses/house-a/backup" -> {
                    assertEquals("true", request.url.queryParameter("temperatureOnly"))
                    assertEquals("true", request.url.queryParameter("includeHumidity"))
                    snapshotGets++
                    JSONObject().put("backup", backup)
                }
                else -> error("Unexpected request: ${request.method} ${request.url.encodedPath}")
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(response.toString().toResponseBody()).build()
        }.build()
        val gateway = AuthenticatedCloudGateway(CloudApiConfig("https://fixture.invalid", owner.deviceId), { "fixture" }, client = http)
        suspend fun poll(at: Long) {
            health.put("reportedAt", at)
            device.put("lastDataUploadEpochMs", at)
            assertTrue(CloudAlertDelivery.poll(context, owner, 0, { gateway.pendingAlerts(true) },
                { fail("Unexpected cloud acknowledgement") }, { inbox -> gateway.viewerReadingCheck(context, owner, inbox) }))
        }
        fun active() = ClientTemperatureAlarms.active(context, owner)
        poll(now - 5_000)
        assertTrue(active().isEmpty())
        rules.put("humidity", true)
        // Version 2.45.0 already checked this upload for temperature. RH still needs its first evaluation.
        val oldUpload = "upload/${owner.greenhouseId}/server/${device.getLong("enrollmentEpochMs")}"
        context.getSharedPreferences("client_temperature_incidents", 0).edit().putLong(oldUpload, now - 4_000).commit()
        poll(now - 4_000)
        val simultaneous = active()
        assertEquals(setOf("A", "B"), simultaneous.map { it.sensorId }.toSet())
        assertTrue(simultaneous.all { it.type == MonitoringAlertType.CriticalHumidity })
        val first = simultaneous.single { it.sensorId == "A" }
        val other = simultaneous.single { it.sensorId == "B" }
        assertTrue(first.message.contains("at the high limit of 85% RH"))
        assertTrue(other.message.contains("at the low limit of 35% RH"))
        val tray = notifications.activeNotifications.single { ClientTemperatureAlarms.matchesNotification(owner, first, it.tag) }
        assertEquals("Critical greenhouse humidity", tray.notification.extras.getCharSequence(android.app.Notification.EXTRA_TITLE))
        assertTrue(tray.notification.actions.any { it.title == STOP_ALARM_LABEL })
        assertEquals(TemperatureSound.selected(context).channel(context).id, tray.notification.channelId)
        ClientTemperatureAlarms.stop(context, owner, setOf(first.id))
        sensors.put(0, sensor("A", 95, now - 3_000))
        poll(now - 3_000)
        assertEquals(listOf(other.id), active().map { it.id })
        sensors.put(0, sensor("A", 70, now - 2_000)).put(1, sensor("B", 60, now - 2_000))
        poll(now - 2_000)
        assertEquals(listOf(other.id), active().map { it.id })
        sensors.put(0, sensor("A", 86, now - 1_000))
        poll(now - 1_000)
        val again = active().single { it.sensorId == "A" }
        assertNotEquals(first.id, again.id)
        // A server and cloud upgrade can race an incident first noticed in the legacy backup.
        val lastGets = snapshotGets
        device.put("sensorHumidityMode", true)
        inbox.put("sensorHumidityInbox", true)
        remote.put(JSONObject().put("type", "CRITICAL_HUMIDITY").put("priority", "CRITICAL").put("id", "cloud-rh")
            .put("greenhouseId", owner.greenhouseId).put("deviceId", "server").put("sensorId", "A")
            .put("message", again.message).put("createdAtEpochMs", now - 1_000).put("requiresAcknowledgement", true))
        poll(now)
        assertEquals(lastGets, snapshotGets)
        assertEquals(setOf(again.id, other.id), active().map { it.id }.toSet())
        ClientTemperatureAlarms.stop(context, owner, setOf(again.id, other.id))
        assertTrue(active().isEmpty())
        assertEquals(setOf("cloud-rh"), ClientTemperatureAlarms.pendingAcknowledgements(context, owner))
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
