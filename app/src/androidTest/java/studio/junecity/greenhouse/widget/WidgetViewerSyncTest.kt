package studio.junecity.greenhouse.widget

import android.Manifest
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.os.SystemClock
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ListenableWorker.Result
import androidx.work.WorkManager
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import studio.junecity.greenhouse.R
import studio.junecity.greenhouse.cloud.*
import studio.junecity.greenhouse.data.viewerHistorySuffix
import studio.junecity.greenhouse.model.GoveeSensor
import studio.junecity.greenhouse.settings.*
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

class WidgetViewerSyncTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val manager = AppWidgetManager.getInstance(context)
    private val host = AppWidgetHost(context, 4278)
    private val security = SecureSettingsStore(context)
    private val owner = CloudEnrollment("viewer-${UUID.randomUUID()}", "north-house", CloudDeviceRole.ViewerClient, tenantId = "widget-test")
    private val inventory = CurrentSensorStore(context)
    private val preferences = AppPreferencesStore(context)
    private val oldInventory = JSONObject().also { inventory.writeBackup(it) }
    private val oldPreferences = preferences.load()
    private val scope = owner.widgetScope()
    private val jobs = WorkManager.getInstance(context)
    private val jobName = "climate_widget_sync${viewerHistorySuffix(owner)}"
    private var id = AppWidgetManager.INVALID_APPWIDGET_ID

    @Before fun placePin() {
        require(security.cloudEnrollment() == null) { "Use an isolated test profile" }
        security.saveCloudEnrollment(owner)
        id = host.allocateAppWidgetId()
        instrumentation.uiAutomation.adoptShellPermissionIdentity(Manifest.permission.BIND_APPWIDGET)
        try {
            assertTrue(manager.bindAppWidgetIdIfAllowed(id, ComponentName(context, ClimateWidget::class.java)))
        } finally {
            instrumentation.uiAutomation.dropShellPermissionIdentity()
        }
        ClimateWidget.settings(context, id).edit().putString("scope", scope).putString("sensor", "bench").putBoolean("celsius", false).commit()
    }

    @After fun clear() {
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID) return
        host.deleteHost()
        jobs.cancelUniqueWork(jobName).result.get(5, TimeUnit.SECONDS)
        security.clearCloudEnrollment()
        preferences.save(oldPreferences)
        inventory.restore(CurrentSensorCodec.decode(oldInventory.getJSONArray("sensors")), oldInventory.optJSONArray("removedSensors"),
            oldInventory.optJSONObject("sensorLabels"), oldInventory.optJSONArray("forgottenGovee"), oldInventory.optJSONObject("sensorColorSlots"))
        context.deleteDatabase("greenhouse_history${viewerHistorySuffix(owner)}.db")
    }

    private fun snapshot(temperature: Double, at: Long): JSONObject {
        val sensor = GoveeSensor("bench", "H5179", "Bench", temperature, 45.0, true, at, oldPreferences.activeGreenhouse.id)
        return JSONObject().put("schema", 2).put("preferences", preferences.exportJson())
            .put("sensors", CurrentSensorCodec.encode(listOf(sensor)))
    }

    private fun waitFor(condition: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + 8_000L
        while (SystemClock.elapsedRealtime() < until) {
            if (condition()) return
            SystemClock.sleep(50)
        }
        assertTrue(condition())
    }

    private fun temperatureOnHomeScreen(): String? {
        var displayed: String? = null
        instrumentation.runOnMainSync {
            val view = host.createView(context, id, manager.getAppWidgetInfo(id))
            displayed = view.findViewById<TextView>(R.id.widget_temperature)?.text?.toString()
        }
        return displayed
    }

    @Test fun newUploadRecoversAfterFailureAndUpdatesPinWithoutOpeningActivity() = runBlocking {
        val at = System.currentTimeMillis()
        val stale = at - 60 * 60_000L
        assertEquals(Result.success(), WidgetSyncWorker.refresh(context, scope, "upload1") { snapshot(53.0, stale) })
        waitFor { temperatureOnHomeScreen() == "53°F" }
        assertEquals(Result.retry(), WidgetSyncWorker.refresh(context, scope, "upload2") { throw IOException("offline") })
        assertEquals(stale, ClimateCardRepository(context).load(owner, sensorId = "bench").measuredAt)
        assertEquals(Result.success(), WidgetSyncWorker.refresh(context, scope, "upload2") { snapshot(71.0, at) })
        waitFor { temperatureOnHomeScreen() == "71°F" }
        assertEquals(at, ClimateCardRepository(context).load(owner, sensorId = "bench").measuredAt)
        assertEquals(Result.success(), WidgetSyncWorker.refresh(context, scope, "upload2") { error("Repeated download") })
    }

    @Test fun anotherHouseAndPartialSnapshotsCannotExposeRetainedReadings() = runBlocking {
        val now = System.currentTimeMillis()
        val oldSnapshot = snapshot(63.0, now)
        assertNotNull(syncViewerReadings(context, owner) { oldSnapshot })
        val other = owner.copy(greenhouseId = "south-house")
        assertNull(syncViewerReadings(context, owner) { security.saveCloudEnrollment(other); snapshot(99.0, now) })
        assertEquals(63.0, inventory.load().single().temperatureF!!, 0.001)
        assertFalse(ClimateWidget.canDisplay(other, ClimateWidget.settings(context, id)))
        assertFalse(ClimateWidget.viewerReady(context, other))
        assertEquals(Result.success(), WidgetSyncWorker.refresh(context, scope, "upload3") { error("Retired work") })
        val partial = JSONObject().put("schema", 2).put("preferences", preferences.exportJson())
        assertTrue(runCatching { syncViewerReadings(context, other) { partial } }.isFailure)
        assertNull(ClimateCardRepository(context).load(other).temperatureF)
    }

    @Test fun inventoryPollQueuesOnlyUnseenUploadsFromThisGreenhouse() = runBlocking {
        val server = AccountDevice(RegisteredDevice("collector", owner.tenantId, CloudDeviceRole.GreenhouseServer,
            greenhouseId = owner.greenhouseId), 1000L, lastDataUploadEpochMs = 2000L)
        val stamp = "collector|1000|2000"
        assertEquals(Result.success(), WidgetSyncWorker.refresh(context, scope, stamp) { snapshot(68.0, System.currentTimeMillis()) })
        jobs.cancelUniqueWork(jobName).result.get(5, TimeUnit.SECONDS)
        fun ids() = jobs.getWorkInfosForUniqueWork(jobName).get(5, TimeUnit.SECONDS).map { it.id }.toSet()
        val before = ids()
        val newer = server.copy(lastDataUploadEpochMs = 3000L)
        WidgetSyncWorker.serverUploads(context, owner, listOf(server, newer.copy(device = server.device.copy(greenhouseId = "south-house"))))
        WidgetSyncWorker.serverUploads(context, owner, listOf(newer.copy(device = server.device.copy(tenantId = "other"))))
        WidgetSyncWorker.serverUploads(context, owner, listOf(newer.copy(device = server.device.copy(communicationSuspended = true))))
        assertEquals(before, ids())
        WidgetSyncWorker.serverUploads(context, owner, listOf(newer))
        waitFor { (ids() - before).isNotEmpty() }
    }
}
