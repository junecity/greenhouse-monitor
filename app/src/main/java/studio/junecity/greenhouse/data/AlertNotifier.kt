package studio.junecity.greenhouse.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import studio.junecity.greenhouse.R
import studio.junecity.greenhouse.model.*
import studio.junecity.greenhouse.settings.AppPreferencesStore
import studio.junecity.greenhouse.settings.CloudEnrollment
import kotlin.math.roundToInt

class AlertNotifier(private val context: Context, private val suffix: String = "") {
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val state = context.getSharedPreferences("alert_state$suffix", Context.MODE_PRIVATE)

    init {
        manager.createNotificationChannel(NotificationChannel("greenhouse_quiet", "Greenhouse status", NotificationManager.IMPORTANCE_LOW))
        manager.createNotificationChannel(NotificationChannel("greenhouse_alerts", "Greenhouse alerts", NotificationManager.IMPORTANCE_HIGH))
    }

    fun forgetSensor(id: String) = synchronized(state) {
        manager.cancel("sensor/$id", id.hashCode())
        cancelOlderNotification(id)
        state.edit().remove("condition_$id").remove("sent_$id").remove("measured_$id").apply()
    }

    private fun cancelOlderNotification(id: String) {
        // Earlier versions used only the hash as the system notification ID.
        if (manager.activeNotifications.any { it.tag == null && it.id == id.hashCode() &&
                it.notification.channelId in setOf("greenhouse_alerts", "greenhouse_quiet") }) {
            manager.cancel(id.hashCode())
        }
    }

    fun evaluate(sensor: GoveeSensor, owner: CloudEnrollment? = null) {
        val settings = AppPreferencesStore(context, suffix).loadForMonitoring(owner)
        val house = settings.sensorHouse(sensor)
        val prefs = settings.forAlertsIn(house)
        val now = System.currentTimeMillis()
        if (!prefs.masterEnabled || now < prefs.snoozedUntilEpochMs) return

        synchronized(state) {
            val id = sensor.id
            val key = "condition_$id"
            val lastSample = "measured_$id"
            val storedCondition = state.getString(key, null)
            val previous = storedCondition?.substringBefore(':')
            if (sensor.online) {
                if (now - sensor.updatedAtEpochMs !in 0L..600_000L ||
                    sensor.updatedAtEpochMs < state.getLong(lastSample, 0L)) return
                state.edit().putLong(lastSample, sensor.updatedAtEpochMs).apply()
            } else if (!prefs.offlineAlerts) return
            val message = condition(sensor, prefs)
            if (message == null) {
                // An absent measurement cannot establish recovery of the previous excursion.
                if (previous?.startsWith("Temperature ") == true && sensor.temperatureF?.isFinite() != true) return
                if (previous?.startsWith("Humidity ") == true && sensor.humidityPercent?.isFinite() != true) return
                state.edit().remove(key).apply()
                return
            }
            val kind = message.substringBefore(':')
            val lastSent = state.getLong("sent_$id", 0)
            if (previous == kind && (!prefs.repeatedAlerts || now - lastSent < prefs.repeatMinutes * 60_000L)) return

            val tag = "sensor/$id"
            val title = listOfNotNull(settings.greenhouses.find { it.id == house }?.name,
                sensor.zone.takeUnless { it == "Unassigned" }, sensor.name).joinToString(" · ")
            val channel = if (prefs.popupsEnabled) "greenhouse_alerts" else "greenhouse_quiet"
            val notice = NotificationCompat.Builder(context, channel)
                .setSmallIcon(R.drawable.ic_notification_greenhouse)
                .setContentTitle(title)
                .setContentText(message)
                .setContentIntent(AlertDestination.pending(context, id.hashCode(), house, id, false, now, tag))
                .setAutoCancel(true)
                .setVibrate(if (prefs.vibrationEnabled) longArrayOf(0, 250, 150, 250) else longArrayOf(0))
            if (prefs.soundEnabled) notice.setDefaults(NotificationCompat.DEFAULT_SOUND)
            runCatching {
                manager.notify(tag, id.hashCode(), notice.build())
                cancelOlderNotification(id)
                state.edit().putString(key, kind).putLong("sent_$id", now).apply()
            }
        }
    }

    private fun condition(sensor: GoveeSensor, prefs: NotificationPreferences): String? {
        if (!sensor.online) return if (prefs.offlineAlerts) "Sensor offline" else null
        val temperature = sensor.temperatureF?.takeIf { it.isFinite() }
        val humidity = sensor.humidityPercent?.takeIf { it.isFinite() }
        return when {
            prefs.temperatureAlerts && temperature != null && temperature < prefs.lowTemperatureF -> "Temperature low: ${temperature.roundToInt()}°F"
            prefs.temperatureAlerts && temperature != null && temperature > prefs.highTemperatureF -> "Temperature high: ${temperature.roundToInt()}°F"
            prefs.humidityAlerts && humidity != null && humidity < prefs.lowHumidity -> "Humidity low: ${humidity.roundToInt()}%"
            prefs.humidityAlerts && humidity != null && humidity > prefs.highHumidity -> "Humidity high: ${humidity.roundToInt()}%"
            else -> null
        }
    }
}
