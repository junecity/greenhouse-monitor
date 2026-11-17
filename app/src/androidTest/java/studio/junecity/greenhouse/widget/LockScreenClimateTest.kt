package studio.junecity.greenhouse.widget

import android.Manifest
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationManager
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.work.ListenableWorker.Result
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import studio.junecity.greenhouse.cloud.CloudDeviceRole
import studio.junecity.greenhouse.data.SpatialInterpolator
import studio.junecity.greenhouse.data.viewerHistorySuffix
import studio.junecity.greenhouse.model.*
import studio.junecity.greenhouse.seedMonitoredPlan
import studio.junecity.greenhouse.settings.*
import java.util.UUID
import kotlin.math.roundToInt

class LockScreenClimateTest {
    @get:Rule val notifications: TestRule = if (Build.VERSION.SDK_INT >= 33)
        GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS) else TestRule { base, _ -> base }
    private val ins = InstrumentationRegistry.getInstrumentation()
    private val app = ins.targetContext
    private val secure = SecureSettingsStore(app)
    private val manager = app.getSystemService(NotificationManager::class.java)
    private val standalone = CloudEnrollment("lock-collector", "", CloudDeviceRole.ViewerClient,
        localSensors = true, tenantId = "lock-qa")

    private fun isolated() {
        assumeTrue("Requires an isolated, unenrolled emulator", Build.MODEL.contains("sdk", true) && secure.cloudEnrollment() == null)
        val widgets = AppWidgetManager.getInstance(app).getAppWidgetIds(ComponentName(app, ClimateWidget::class.java))
        assertEquals("Readings should also work without a launcher widget", 0, widgets.size)
    }

    private fun notice() = manager.activeNotifications.firstOrNull { it.notification.channelId == LockScreenClimate.CHANNEL_ID }?.notification
    private fun copyText(n: Notification) = listOf(Notification.EXTRA_TITLE, Notification.EXTRA_TEXT,
        Notification.EXTRA_SUB_TEXT, Notification.EXTRA_BIG_TEXT).joinToString(" ") { n.extras.getCharSequence(it)?.toString().orEmpty() }
    private fun until(description: String, matches: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 10_000
        do {
            if (matches()) return
            SystemClock.sleep(70)
        } while (SystemClock.elapsedRealtime() < end)
        fail("$description; posted text: ${notice()?.let(::copyText)}")
    }
    private fun shell(command: String) =
        ParcelFileDescriptor.AutoCloseInputStream(ins.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }

    @Test fun currentSpatialAveragesFollowTheSelectedHouseUnitsAndObservationTime() {
        isolated()
        val inventory = CurrentSensorStore(app, "_standalone")
        assumeTrue(inventory.load().isEmpty())
        val settings = AppPreferencesStore(app, "_standalone")
        val old = settings.load()
        val house = GreenhouseConfig(name = "West Glasshouse", sensorPositions = mapOf(
            "base" to SensorPosition(1.0, 1.0), "top" to SensorPosition(14.0, 4.0), "door" to SensorPosition(2.0, 2.0)))
        val annex = GreenhouseConfig(id = "annex", name = "East Glasshouse")
        val prefs = AppPreferences(greenhouses = listOf(house, annex), activeGreenhouseId = house.id)
        val now = System.currentTimeMillis()
        val base = GoveeSensor("base", "H5179", "Bench", 60.0, 45.0, true, now - 30_000, house.id)
        val rows = listOf(base, base.copy(id = "top", temperatureF = 84.0, humidityPercent = 95.0, updatedAtEpochMs = now),
            base.copy(id = "door", temperatureF = 64.0, humidityPercent = 55.0),
            base.copy(id = "old", temperatureF = 120.0, updatedAtEpochMs = now - 3_600_000),
            base.copy(id = "outbuilding", temperatureF = 50.0, humidityPercent = 82.0, greenhouseId = annex.id))
        try {
            secure.saveCloudEnrollment(standalone)
            seedMonitoredPlan(app, standalone, prefs)
            settings.save(prefs)
            inventory.save(rows)
            assertFalse(LockScreenClimate.enabled(app, standalone))
            LockScreenClimate.setEnabled(app, standalone, true)
            val temp = SpatialInterpolator.greenhouseAverage(rows.filter { it.greenhouseId == house.id }, house, Metric.Temperature, now)!!.roundToInt()
            val rh = SpatialInterpolator.greenhouseAverage(rows.filter { it.greenhouseId == house.id }, house, Metric.Humidity, now)!!.roundToInt()
            assertEquals(72, temp)
            until("Expected the dashboard averages $temp°F and $rh%") {
                notice()?.let { copyText(it).contains("$temp°F") && copyText(it).contains("$rh%") && copyText(it).contains(house.name) } == true
            }
            val posted = requireNotNull(notice())
            assertEquals(Notification.VISIBILITY_PUBLIC, posted.visibility)
            assertTrue(posted.flags and Notification.FLAG_ONGOING_EVENT != 0)
            assertEquals(base.updatedAtEpochMs, posted.`when`)
            val channel = manager.getNotificationChannel(LockScreenClimate.CHANNEL_ID)
            assertNull(channel.sound)
            assertFalse(channel.shouldVibrate())
            settings.save(prefs.copy(activeGreenhouseId = annex.id, temperatureFahrenheit = false))
            until("New selected greenhouse and Celsius value") {
                notice()?.let { copyText(it).contains("10°C") && copyText(it).contains("82%") && copyText(it).contains(annex.name) } == true
            }
            inventory.save(listOf(rows.last().copy(updatedAtEpochMs = now - 3_600_000)))
            until("Show the last observation time and label the old reading") {
                notice()?.let { it.`when` == now - 3_600_000 && copyText(it).contains("cached", true) } == true
            }
            inventory.save(emptyList())
            until("Empty inventory must clear the last house's numbers") { notice()?.let { !copyText(it).contains("10°C") } == true }
            LockScreenClimate.setEnabled(app, standalone, false)
            until("Disabling removes the public notification") { notice() == null }
        } finally {
            LockScreenClimate.setEnabled(app, standalone, false)
            secure.clearCloudEnrollment()
            inventory.clear()
            settings.save(old)
        }
    }

    @Test fun pairedPhoneGetsFreshSnapshotsWithNoPinsAndClearsTheOldHouseOnSourceChange() = runBlocking {
        isolated()
        val owner = CloudEnrollment("lock-viewer-" + UUID.randomUUID(), "cloud-house-A", CloudDeviceRole.ViewerClient, tenantId = "paired-lock")
        val inventory = CurrentSensorStore(app)
        assumeTrue(inventory.load().isEmpty())
        val settings = AppPreferencesStore(app)
        val old = settings.load()
        val now = System.currentTimeMillis()
        val a = GoveeSensor("a", "H5179", "A", 66.0, 70.0, true, now - 5_000)
        val b = a.copy(id = "b", temperatureF = 76.0, humidityPercent = 80.0, updatedAtEpochMs = now)
        try {
            secure.saveCloudEnrollment(owner)
            LockScreenClimate.setEnabled(app, owner, true)
            until("Waiting for authenticated server data") { notice()?.let { !copyText(it).contains("°F") && !copyText(it).contains("%") } == true }
            val snapshot = JSONObject().put("schema", 2).put("preferences", settings.exportJson())
                .put("sensors", CurrentSensorCodec.encode(listOf(a, b)))
            assertEquals(Result.success(), WidgetSyncWorker.refresh(app, owner.widgetScope(), "lock-upload") { snapshot })
            until("Authenticated client displays both averages without a home widget") {
                notice()?.let { copyText(it).contains("71°F") && copyText(it).contains("75%") } == true
            }
            val other = owner.copy(greenhouseId = "cloud-house-B")
            secure.saveCloudEnrollment(other)
            until("Switching servers removes the previous greenhouse readings") { notice()?.let { !copyText(it).contains("71°F") } ?: true }
            secure.clearCloudEnrollment()
            until("Sign-out removes the lock-screen reading") { notice() == null }
            assertFalse(LockScreenClimate.enabled(app, other))
        } finally {
            secure.clearCloudEnrollment()
            inventory.clear()
            settings.save(old)
            app.deleteDatabase("greenhouse_history${viewerHistorySuffix(owner)}.db")
        }
    }

    @Test fun publicAverageNotificationRemainsAvailableWhileThePhoneIsLocked() {
        isolated()
        assumeTrue("Run with a disposable profile configured for lock-screen notifications",
            InstrumentationRegistry.getArguments().getString("keyguardQa") == "true")
        val inventory = CurrentSensorStore(app, "_standalone")
        val settings = AppPreferencesStore(app, "_standalone")
        assumeTrue(inventory.load().isEmpty())
        val old = settings.load()
        try {
            secure.saveCloudEnrollment(standalone)
            seedMonitoredPlan(app, standalone)
            settings.save(AppPreferences())
            val a = GoveeSensor("qa-lower", "H5179", "Lower", 60.0, 70.0, true, System.currentTimeMillis())
            inventory.save(listOf(a, a.copy(id = "qa-upper", temperatureF = 74.0, humidityPercent = 80.0)))
            LockScreenClimate.setEnabled(app, standalone, true)
            until("System notification has both averages") { notice()?.let { copyText(it).contains("67°F") && copyText(it).contains("75%") } == true }
            shell("input keyevent KEYCODE_HOME")
            shell("input keyevent KEYCODE_SLEEP")
            val lock = app.getSystemService(KeyguardManager::class.java)
            until("Keyguard locked") { lock.isKeyguardLocked }
            shell("input keyevent KEYCODE_WAKEUP")
            until("The locked phone has a public notification containing both averages") {
                val power = app.getSystemService(android.os.PowerManager::class.java)
                power.isInteractive && lock.isKeyguardLocked && notice()?.let {
                    it.visibility == Notification.VISIBILITY_PUBLIC && copyText(it).contains("67°F") && copyText(it).contains("75%")
                } == true
            }
        } finally {
            shell("input keyevent KEYCODE_WAKEUP")
            shell("wm dismiss-keyguard")
            LockScreenClimate.setEnabled(app, standalone, false)
            secure.clearCloudEnrollment()
            inventory.clear()
            settings.save(old)
        }
    }
}
