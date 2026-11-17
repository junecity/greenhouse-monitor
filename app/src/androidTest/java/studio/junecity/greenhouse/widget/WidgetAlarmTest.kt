package studio.junecity.greenhouse.widget

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import studio.junecity.greenhouse.MainActivity
import studio.junecity.greenhouse.R
import studio.junecity.greenhouse.cloud.*
import studio.junecity.greenhouse.data.AlertNotifier
import studio.junecity.greenhouse.model.*
import studio.junecity.greenhouse.seedMonitoredPlan
import studio.junecity.greenhouse.settings.*

class WidgetAlarmTest {
    @get:Rule val notifications: TestRule = if (Build.VERSION.SDK_INT >= 33)
        GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS) else TestRule { base, _ -> base }
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext
    private val secure = SecureSettingsStore(app)
    private val manager = AppWidgetManager.getInstance(app)
    private val host = AppWidgetHost(app, 4294)

    private fun pin(owner: CloudEnrollment, sensorId: String? = null, floor: String? = null): Int {
        val id = host.allocateAppWidgetId()
        instrumentation.uiAutomation.adoptShellPermissionIdentity(Manifest.permission.BIND_APPWIDGET)
        try {
            assertTrue(manager.bindAppWidgetIdIfAllowed(id, ComponentName(app, ClimateWidget::class.java)))
        } finally {
            instrumentation.uiAutomation.dropShellPermissionIdentity()
        }
        ClimateWidget.settings(app, id).edit().putString("scope", owner.widgetScope())
            .putString("sensor", sensorId).putString("house", floor).commit()
        ClimateWidget.refresh(app, id)
        return id
    }

    @Test fun namedFloorWidgetMatchesHumidityAndTemperatureIndependentlyWhileReconnecting() {
        assumeTrue(Build.MODEL.contains("sdk", true) && secure.cloudEnrollment() == null)
        val owner = CloudEnrollment("humidity-viewer", "cloud-greenhouse-uuid", CloudDeviceRole.ViewerClient, tenantId = "humidity-widget-qa")
        val prefs = AppPreferencesStore(app)
        val inventory = CurrentSensorStore(app)
        assumeTrue(inventory.load().isEmpty())
        val saved = prefs.load()
        val floor = GreenhouseConfig(id = "main", name = "Main Greenhouse")
        val annex = GreenhouseConfig(id = "annex", name = "Annex")
        val at = System.currentTimeMillis()
        val bench = GoveeSensor("bench", "H5179", "Bench", 86.0, 90.0, true, at, floor.id)
        val dry = MonitoringAlert("bench-humidity", owner.greenhouseId, MonitoringAlertType.CriticalHumidity,
            MonitoringAlertPriority.Critical, "Bench reached 90% RH.", at, true, serverId = "server-045", sensorId = bench.id)
        val hot = dry.copy(id = "bench-temperature", type = MonitoringAlertType.CriticalTemperature, message = "Bench reached 86°F.")
        fun send(vararg alarms: MonitoringAlert) =
            ClientTemperatureAlarms.deliver(app, owner, AlertInbox(alarms.toList(), complete = true, criticalIncidentsVerified = true))
        fun tapStop(alert: MonitoringAlert) {
            val notifications = app.getSystemService(NotificationManager::class.java)
            val posted = notifications.activeNotifications.single { ClientTemperatureAlarms.matchesNotification(owner, alert, it.tag) }
            posted.notification.actions.single { it.title == "Stop alarm" }.actionIntent.send()
            val deadline = SystemClock.elapsedRealtime() + 5_000L
            while (ClientTemperatureAlarms.active(app, owner).any { it.id == alert.id } && SystemClock.elapsedRealtime() < deadline)
                SystemClock.sleep(40)
            assertFalse(ClientTemperatureAlarms.active(app, owner).any { it.id == alert.id })
        }
        try {
            secure.saveCloudEnrollment(owner)
            prefs.save(AppPreferences(greenhouses = listOf(floor, annex), activeGreenhouseId = floor.id))
            inventory.save(listOf(bench, bench.copy(id = "roof", greenhouseId = annex.id)))
            ClimateWidget.viewerSynced(app, owner)
            val named = pin(owner, floor = floor.id)
            val other = pin(owner, floor = annex.id)
            val single = pin(owner, bench.id, floor.id)
            awaitPins(mapOf(named to false, other to false, single to false))
            secure.saveCloudEnrollment(owner.copy(greenhouseId = "different-server"))
            awaitPins(mapOf(named to false, other to false, single to false))
            secure.saveCloudEnrollment(owner)
            assertFalse(ClimateWidget.viewerReady(app, owner))
            send(dry)
            awaitPins(mapOf(named to true, other to false, single to true))
            send(dry, hot)
            tapStop(dry)
            awaitPins(mapOf(named to true, other to false, single to true))
            send(dry, hot)
            assertEquals(listOf(hot.id), ClientTemperatureAlarms.active(app, owner).map { it.id })
            tapStop(hot)
            awaitPins(mapOf(named to false, other to false, single to false))
        } finally {
            host.deleteHost()
            secure.clearCloudEnrollment()
            CloudAlertService.sync(app, false)
            inventory.clear()
            prefs.save(saved)
        }
    }

    private fun paintedRed(view: View): Boolean {
        val drawable = view.background.constantState!!.newDrawable(app.resources).mutate()
        val bitmap = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, 48, 48)
        drawable.draw(Canvas(bitmap))
        val pixel = bitmap.getPixel(24, 24)
        bitmap.recycle()
        return Color.red(pixel) > Color.blue(pixel) * 2 && Color.red(pixel) > Color.green(pixel) * 2
    }

    private fun awaitPins(expected: Map<Int, Boolean>) {
        val deadline = SystemClock.elapsedRealtime() + 8_000
        var seen = emptyMap<Int, Pair<String, Boolean>>()
        do {
            instrumentation.runOnMainSync {
                seen = expected.keys.mapNotNull { id ->
                    val view = host.createView(app, id, manager.getAppWidgetInfo(id))
                    val badge = view.findViewById<TextView>(R.id.widget_status) ?: return@mapNotNull null
                    id to (badge.text.toString() to paintedRed(view.findViewById(R.id.widget_body)))
                }.toMap()
            }
            if (expected.all { (id, alarm) -> seen[id]?.let { (label, red) -> red == alarm && label.isNotBlank() && label.contains("ALARM") == alarm } == true }) return
            SystemClock.sleep(60)
        } while (SystemClock.elapsedRealtime() < deadline)
        fail("Expected alarm colors $expected, saw $seen")
    }

    @Test fun viewerPinsFollowEachDeliveredAlarmThroughRecoveryAndOfflineStop() {
        assumeTrue(Build.MODEL.contains("sdk", true) && secure.cloudEnrollment() == null)
        val owner = CloudEnrollment("alarm-widget-viewer", "cloud-north", CloudDeviceRole.ViewerClient, tenantId = "widget-qa")
        val prefs = AppPreferencesStore(app)
        val inventory = CurrentSensorStore(app)
        assumeTrue(inventory.load().isEmpty())
        val saved = prefs.load()
        val house = GreenhouseConfig()
        val config = AppPreferences(greenhouses = listOf(house), activeGreenhouseId = house.id)
        val at = System.currentTimeMillis()
        val bench = MonitoringAlert("alarm-widget-bench", owner.greenhouseId, MonitoringAlertType.CriticalTemperature,
            MonitoringAlertPriority.Critical, "Bench exceeded its limit", at, true, sensorId = "bench")
        val roof = bench.copy(id = "alarm-widget-roof", sensorId = "roof")
        val foreign = bench.copy(id = "alarm-widget-elsewhere", greenhouseId = "cloud-south")
        fun send(vararg alarms: MonitoringAlert) =
            ClientTemperatureAlarms.deliver(app, owner, AlertInbox(alarms.toList(), complete = true, criticalIncidentsVerified = true))
        try {
            secure.saveCloudEnrollment(owner)
            prefs.save(config)
            val all = pin(owner)
            val first = pin(owner, "bench")
            val second = pin(owner, "roof")
            awaitPins(mapOf(all to false, first to false, second to false))
            send(foreign)
            send(foreign, bench)
            // The first alarm can reach the phone while its very first snapshot is still pending.
            awaitPins(mapOf(all to true, first to true, second to false))
            val sample = GoveeSensor("bench", "H5179", "Bench", 66.0, 70.0, true, at, house.id)
            inventory.save(listOf(sample, sample.copy(id = "roof", name = "Roof")))
            ClimateWidget.viewerSynced(app, owner)
            send(foreign, bench, roof)
            awaitPins(mapOf(all to true, first to true, second to true))
            send(bench.copy(resolvedAtEpochMs = at + 1), roof, foreign)
            ClientTemperatureAlarms.stop(app, owner, setOf(roof.id))
            awaitPins(mapOf(all to true, first to true, second to false))
            ClientTemperatureAlarms.stop(app, owner, setOf(bench.id))
            awaitPins(mapOf(all to false, first to false, second to false))
            assertEquals(listOf(foreign.id), ClientTemperatureAlarms.active(app, owner).map { it.id })
            send(bench.copy(id = "alarm-widget-next"))
            awaitPins(mapOf(all to true, first to true, second to false))
            secure.clearCloudEnrollment()
            awaitPins(mapOf(all to false, first to false, second to false))
        } finally {
            host.deleteHost()
            secure.clearCloudEnrollment()
            CloudAlertService.sync(app, false)
            inventory.clear()
            prefs.save(saved)
        }
    }

    @Test fun standaloneWidgetRecolorsOnPostedSensorAlertsAndClearsAfterLastRecovery() = checkLocal(
        CloudEnrollment("widget-local", "", CloudDeviceRole.ViewerClient, localSensors = true, tenantId = "local-alert-test"), "_standalone")

    @Test fun serverWidgetRecolorsOnPostedSensorAlertsAndClearsAfterLastRecovery() = checkLocal(
        CloudEnrollment("widget-collector", "cloud-north", CloudDeviceRole.GreenhouseServer, tenantId = "local-alert-test"), "")

    private fun checkLocal(owner: CloudEnrollment, suffix: String) {
        val inventory = CurrentSensorStore(app, suffix)
        assumeTrue(Build.MODEL.contains("sdk", true) && secure.cloudEnrollment() == null && inventory.load().isEmpty())
        val store = AppPreferencesStore(app, suffix)
        val saved = store.load()
        val config = AppPreferences(notifications = NotificationPreferences(highTemperatureF = 85.0, highHumidity = 90.0))
        val notifier = AlertNotifier(app, suffix)
        val bench = GoveeSensor("alarm-local-bench", "H5179", "Bench", 70.0, 65.0, true, System.currentTimeMillis())
        val roof = bench.copy(id = "alarm-local-roof", name = "Roof")
        fun observe(sensor: GoveeSensor) = notifier.evaluate(sensor.copy(updatedAtEpochMs = System.currentTimeMillis()), owner)
        try {
            secure.saveCloudEnrollment(owner)
            seedMonitoredPlan(app, owner, config)
            store.save(config)
            inventory.save(listOf(bench, roof))
            val all = pin(owner)
            val first = pin(owner, bench.id)
            val second = pin(owner, roof.id)
            awaitPins(mapOf(all to false, first to false, second to false))
            observe(bench.copy(temperatureF = 92.0))
            awaitPins(mapOf(all to true, first to true, second to false))
            observe(roof.copy(humidityPercent = 98.0))
            awaitPins(mapOf(all to true, first to true, second to true))
            observe(bench)
            awaitPins(mapOf(all to true, first to false, second to true))
            observe(roof)
            awaitPins(mapOf(all to false, first to false, second to false))
        } finally {
            host.deleteHost()
            notifier.forgetSensor(bench.id)
            notifier.forgetSensor(roof.id)
            secure.clearCloudEnrollment()
            inventory.clear()
            store.save(saved)
        }
    }

    @Test fun everyLauncherSizeRestoresItsOriginalSurfaceOnReapply() {
        val open = PendingIntent.getActivity(app, 0, Intent(app, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val live = GreenhouseWidgetUiState(status = WidgetFeedStatus.Live)
        instrumentation.runOnMainSync {
            for ((width, height) in listOf(150 to 85, 300 to 330, 410 to 150)) {
                val baseline = climateRemoteViews(app, width, height, live, WidgetMetric.Both, open)
                val view = baseline.apply(app, FrameLayout(app))
                val root = view.findViewById<View>(R.id.widget_body)
                assertFalse(paintedRed(root))
                val alert = climateRemoteViews(app, width, height, live.copy(alarmActive = true), WidgetMetric.Both, open)
                alert.reapply(app, view)
                assertTrue("Alarm background at ${width}x${height}", paintedRed(root))
                assertTrue(view.findViewById<TextView>(R.id.widget_status).text.contains("ALARM"))
                baseline.reapply(app, view)
                assertFalse(paintedRed(root))
                assertTrue(view.findViewById<TextView>(R.id.widget_status).text.contains("LIVE"))
            }
        }
    }
}
