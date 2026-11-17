package studio.junecity.greenhouse.data

import android.app.NotificationManager
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import studio.junecity.greenhouse.model.AppPreferences
import studio.junecity.greenhouse.model.GoveeSensor
import studio.junecity.greenhouse.model.NotificationPreferences
import studio.junecity.greenhouse.settings.AppPreferencesStore

class SensorAlarmEpisodesTest {
    @Test fun clearingOneSensorKeepsTheOtherArmedAtTheSameLimit() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = context.getSystemService(NotificationManager::class.java)
        val suffix = "_sensor_episodes"
        val settings = AppPreferencesStore(context, suffix)
        val notifier = AlertNotifier(context, suffix)
        // Same display name and hash force the system to use the stable sensor ID.
        val first = "qa-sensor-Aa"
        val second = "qa-sensor-BB"
        assertEquals(first.hashCode(), second.hashCode())
        fun read(id: String, degrees: Double, ageMs: Long = 0) = notifier.evaluate(
            GoveeSensor(id, "H5179", "Bench", degrees, 55.0, true, System.currentTimeMillis() - ageMs)
        )
        fun posted(id: String) = manager.activeNotifications.firstOrNull { it.tag == "sensor/$id" }
        fun awaitState(id: String, present: Boolean) {
            val deadline = SystemClock.elapsedRealtime() + 3000
            while ((posted(id) != null) != present && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(30)
            assertEquals(id, present, posted(id) != null)
        }
        try {
            notifier.forgetSensor(first)
            notifier.forgetSensor(second)
            settings.restore(AppPreferences(notifications = NotificationPreferences(highTemperatureF = 82.0)))
            read(first, 85.0)
            awaitState(first, true)
            read(second, 85.0)
            awaitState(second, true)
            assertNotEquals(posted(first)!!.notification.contentIntent, posted(second)!!.notification.contentIntent)
            manager.cancel("sensor/$first", first.hashCode())
            awaitState(first, false)
            assertNotNull(posted(second))
            read(first, 80.0, ageMs = 660_000)
            read(first, 87.0)
            read(second, 80.0)
            manager.cancel("sensor/$second", second.hashCode())
            awaitState(second, false)
            read(second, 85.0)
            awaitState(second, true)
            assertNull(posted(first))
            read(first, 78.0)
            read(first, 85.0)
            awaitState(first, true)
        } finally {
            notifier.forgetSensor(first)
            notifier.forgetSensor(second)
            settings.clear()
        }
    }

    @Test fun independentTemperatureAndHumidityAlertsAtExactLimits() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = context.getSystemService(NotificationManager::class.java)
        val id = "qa-climate-episodes"
        val suffix = "_climate_episodes"
        val settings = AppPreferencesStore(context, suffix)
        val notifier = AlertNotifier(context, suffix)
        val preferences = NotificationPreferences(highTemperatureF = 82.0, lowHumidity = 30.0, highHumidity = 85.0)
        fun observe(degrees: Double?, rh: Double?, age: Long = 0) = notifier.evaluate(
            GoveeSensor(id, "H5179", "Bench", degrees, rh, true, System.currentTimeMillis() - age))
        fun notice(kind: String) = manager.activeNotifications.firstOrNull { it.tag == "$kind/$id" }
        fun await(temp: String?, humidity: String?) {
            val expected = listOf(temp, humidity)
            val deadline = SystemClock.elapsedRealtime() + 3000
            var messages: List<String?>
            do {
                messages = listOf("sensor", "humidity").map {
                    notice(it)?.notification?.extras?.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString()
                }
                if (messages == expected) return
                SystemClock.sleep(35)
            } while (SystemClock.elapsedRealtime() < deadline)
            assertEquals(expected, messages)
        }
        try {
            notifier.forgetSensor(id)
            settings.restore(AppPreferences(notifications = preferences))
            observe(82.0, 85.0)
            await("Temperature high: 82°F", "Humidity high: 85%")
            assertNotEquals(notice("sensor")!!.notification.contentIntent, notice("humidity")!!.notification.contentIntent)
            observe(75.0, null)
            await(null, "Humidity high: 85%")
            observe(74.0, 60.0, age = 660_000)
            await(null, "Humidity high: 85%")
            observe(74.0, 30.0)
            await(null, "Humidity low: 30%")
            observe(82.0, 55.0)
            await("Temperature high: 82°F", null)
            settings.restore(AppPreferences(notifications = preferences.copy(humidityAlerts = false)))
            observe(74.0, 95.0)
            await(null, null)
        } finally {
            notifier.forgetSensor(id)
            settings.clear()
        }
    }

    @Test fun existingHumidityAlertSurvivesUpgradeAlongsideNewTemperatureAlert() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = app.getSystemService(NotificationManager::class.java)
        val suffix = "_climate_upgrade"
        val id = "qa-upgrade"
        val state = app.getSharedPreferences("alert_state$suffix", android.content.Context.MODE_PRIVATE)
        val settings = AppPreferencesStore(app, suffix)
        val notifier = AlertNotifier(app, suffix)
        try {
            notifier.forgetSensor(id)
            settings.restore(AppPreferences(notifications = NotificationPreferences(highTemperatureF = 82.0)))
            state.edit().putString("condition_$id", "Humidity high").putLong("sent_$id", 100).commit()
            notifier.evaluate(GoveeSensor(id, "H5179", "Bench", 83.0, 94.0, true, System.currentTimeMillis()))
            val deadline = SystemClock.elapsedRealtime() + 3000
            while (manager.activeNotifications.count { it.tag in setOf("humidity/$id", "sensor/$id") } < 2 &&
                SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(40)
            assertEquals("Temperature high", state.getString("condition_$id", null))
            assertEquals("Humidity high", state.getString("rh_condition_$id", null))
            assertEquals(2, manager.activeNotifications.count { it.tag in setOf("humidity/$id", "sensor/$id") })
        } finally {
            notifier.forgetSensor(id)
            settings.clear()
        }
    }
}
