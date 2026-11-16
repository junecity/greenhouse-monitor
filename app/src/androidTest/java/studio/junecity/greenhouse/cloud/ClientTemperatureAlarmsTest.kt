package studio.junecity.greenhouse.cloud

import android.app.NotificationManager
import android.media.AudioManager
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import studio.junecity.greenhouse.settings.CloudEnrollment
import studio.junecity.greenhouse.settings.SecureSettingsStore

class ClientTemperatureAlarmsTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val secure = SecureSettingsStore(context)
    private val notifications = context.getSystemService(NotificationManager::class.java)
    private val audio = context.getSystemService(AudioManager::class.java)
    private val owner = CloudEnrollment("device-test-alarm", "house-A", CloudDeviceRole.ViewerClient, tenantId = "tenant-test-a")
    private val selected = TemperatureSound.selected(context)
    private val originalAudio = context.getSharedPreferences("client_alert_audio", 0).all
    private val previousVolume = audio.getStreamVolume(AudioManager.STREAM_ALARM)
    private val previousEnrollment = secure.cloudEnrollment()
    private fun active() = ClientTemperatureAlarms.active(context, owner)
    private fun inbox(vararg alerts: MonitoringAlert) = AlertInbox(alerts.toList(), true, true)
    private fun alert(id: String, house: String) = MonitoringAlert(id, house, MonitoringAlertType.CriticalTemperature,
        MonitoringAlertPriority.Critical, "Bench measured 88°F above 82°F.", System.currentTimeMillis(), true)
    private fun waitUntil(message: String, predicate: () -> Boolean) {
        repeat(100) { if (predicate()) return else SystemClock.sleep(35) }
        assertTrue(message, predicate())
    }

    @Before fun setup() {
        require(secure.cloudEnrollment() == null) { "Run on a fresh Android test profile." }
        secure.saveCloudEnrollment(owner)
        TemperatureSound.Pulse.select(context)
        CriticalAlarm.setEnabled(context, true)
    }

    @After fun cleanup() {
        if (secure.cloudEnrollment()?.deviceId != owner.deviceId) return
        secure.clearCloudEnrollment()
        CloudAlertService.sync(context, false)
        CriticalAlarm.stopIncident(context)
        selected.select(context)
        val prefs = context.getSharedPreferences("client_alert_audio", 0)
        val edit = prefs.edit()
        if (!originalAudio.containsKey("critical_temperature")) edit.remove("critical_temperature")
        if (originalAudio.containsKey("loud_alarm")) edit.putBoolean("loud_alarm", originalAudio["loud_alarm"] as Boolean)
        else edit.remove("loud_alarm")
        edit.commit()
        audio.setStreamVolume(AudioManager.STREAM_ALARM, previousVolume, 0)
        previousEnrollment?.let(secure::saveCloudEnrollment)
    }

    @Test fun swipeRecoveryAndServiceRestartPreserveTheLocalAlarmUntilExplicitStop() {
        val alarm = alert("qa-incident-first", owner.greenhouseId)
        ClientTemperatureAlarms.deliver(context, owner, inbox(alarm))
        waitUntil("critical alarm notification") { notifications.activeNotifications.any { it.id == alarm.id.hashCode() } }
        val notice = notifications.activeNotifications.single { it.id == alarm.id.hashCode() }
        assertEquals("Open alarm", notice.notification.actions.single().title.toString())
        assertEquals(notice.notification.contentIntent, notice.notification.actions.single().actionIntent)
        notifications.cancel(notice.tag, notice.id)
        waitUntil("tray entry dismissed") { notifications.activeNotifications.none { it.id == notice.id } }
        assertTrue(CriticalAlarm.isIncidentPlaying())
        clearRecoveredCloudAlerts(notifications, inbox())
        ClientTemperatureAlarms.deliver(context, owner, inbox())
        assertEquals(listOf(alarm.id), active().map { it.id })
        // Recreate the real foreground service after dropping its in-memory audio handle.
        CriticalAlarm.stopIncident(context)
        assertFalse(CriticalAlarm.isIncidentPlaying())
        CloudAlertService.sync(context, true)
        waitUntil("foreground service resumed the persisted alarm without a successful poll") { CriticalAlarm.isIncidentPlaying() }
        ClientTemperatureAlarms.stop(context, owner, setOf(alarm.id))
        assertTrue(active().isEmpty())
        assertFalse(CriticalAlarm.isIncidentPlaying())
        assertEquals(setOf(alarm.id), ClientTemperatureAlarms.pendingAcknowledgements(context, owner))
        ClientTemperatureAlarms.deliver(context, owner, inbox(alarm))
        assertFalse(CriticalAlarm.isIncidentPlaying())
        ClientTemperatureAlarms.acknowledged(context, owner, alarm.id)
        assertTrue(ClientTemperatureAlarms.pendingAcknowledgements(context, owner).isEmpty())
        ClientTemperatureAlarms.deliver(context, owner, inbox(alarm))
        assertTrue(active().isEmpty())
    }

    @Test fun multipleGreenhousesNewBreachesAndChangedRegistrationsAreIndependent() {
        val first = alert("qa-incident-Aa", "house-A")
        val second = alert("qa-incident-BB", "house-B")
        assertEquals("fixture should exercise Android integer ID collisions", first.id.hashCode(), second.id.hashCode())
        ClientTemperatureAlarms.deliver(context, owner, inbox(first, second))
        ClientTemperatureAlarms.deliver(context, owner, inbox(first, second))
        assertEquals(2, active().size)
        waitUntil("distinct notification tags preserve both incidents") {
            notifications.activeNotifications.count { it.id == first.id.hashCode() } == 2
        }
        val selectedHouse = owner.copy(greenhouseId = "house-B")
        secure.saveCloudEnrollment(selectedHouse)
        ClientTemperatureAlarms.stop(context, selectedHouse, setOf(first.id))
        assertTrue(CriticalAlarm.isIncidentPlaying())
        assertEquals(second.id, ClientTemperatureAlarms.active(context, selectedHouse).single().id)
        val newIncident = alert("qa-incident-A-recurred", "house-A")
        ClientTemperatureAlarms.deliver(context, selectedHouse, inbox(newIncident))
        assertEquals(setOf(second.id, newIncident.id), active().map { it.id }.toSet())
        val newOwner = owner.copy(tenantId = "tenant-test-b")
        secure.saveCloudEnrollment(newOwner)
        assertTrue(ClientTemperatureAlarms.active(context, newOwner).isEmpty())
        assertFalse(CriticalAlarm.isIncidentPlaying())
        ClientTemperatureAlarms.deliver(context, selectedHouse, inbox(first)) // late response from the previous account
        assertTrue(ClientTemperatureAlarms.active(context, newOwner).isEmpty())
        assertTrue(ClientTemperatureAlarms.pendingAcknowledgements(context, newOwner).isEmpty())
    }
}
