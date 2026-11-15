package studio.junecity.greenhouse.data

import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import studio.junecity.greenhouse.R
import studio.junecity.greenhouse.model.*
import java.time.Instant
import kotlin.math.roundToInt

/** Local advisory when a Display has just dimmed and the same greenhouse has enough matching history. */
internal class EveningColdWatch(private val context: Context) {
    private val nextCheck = mutableMapOf<String, Long>()

    fun check(history: HistoryStore, inventory: List<GoveeSensor>, settings: AppPreferences, scope: String) {
        val now = System.currentTimeMillis()
        val rules = settings.notifications
        if (!rules.masterEnabled || !rules.temperatureAlerts || now < rules.snoozedUntilEpochMs) return
        val house = settings.activeGreenhouseId
        val key = "$scope/$house"
        if (now < (nextCheck[key] ?: 0L)) return
        val sensors = inventory.filter { it.greenhouseId == house && it.isFresh(now) }
        val displays = sensors.filter { it.lightLevel == "Twilight" || it.lightLevel == "Dark" }
        if (displays.isEmpty()) return
        nextCheck[key] = now + 2 * LIGHT_MINUTE
        val (zone, outdoor) = StationWeather(context).cachedTemperatures(settings.zipCode) ?: return
        val local = Instant.ofEpochMilli(now).atZone(zone)
        if (local.hour < 12) return
        val sent = context.getSharedPreferences("light_advisories", Context.MODE_PRIVATE)
        val date = local.toLocalDate().toString()
        if (sent.getString(key, null) == date || !NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val week = now - 7 * DAY_MS
        for (display in displays) {
            val recent = history.sensorReadingsSince(display.id, now - 45 * LIGHT_MINUTE)
            val event = LightHistory.changes(LightHistory.spans(recent), zone).lastOrNull() ?: continue
            if (event.brightening || now - event.time !in 0..20 * LIGHT_MINUTE) continue
            val prior = LightHistory.changes(LightHistory.spans(history.sensorReadingsSince(display.id, week)), zone)
            for (sensor in sensors.sortedBy { it.temperatureF }) {
                if ((sensor.temperatureF ?: continue) <= rules.lowTemperatureF) continue
                val comparison = LightHistory.compare(prior, event, SensorTimeline(history.sensorReadingsSince(sensor.id, week)),
                    zone, rules.lowTemperatureF) { time -> outdoor[time / HEATING_HOUR * HEATING_HOUR] } ?: continue
                if (!comparison.coldWatch) continue
                val text = "${sensor.name} fell below ${rules.lowTemperatureF.roundToInt()}°F within an hour on ${comparison.crossings} of ${comparison.nights} similar evenings."
                val id = "light/$key".hashCode()
                val notification = NotificationCompat.Builder(context, "greenhouse_quiet")
                    .setSmallIcon(R.drawable.ic_notification_greenhouse)
                    .setContentTitle("${settings.activeGreenhouse.name} · Evening cold watch")
                    .setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text))
                    .setContentIntent(AlertDestination.pending(context, id, house, sensor.id, false, now))
                    .setAutoCancel(true).build()
                val manager = context.getSystemService(NotificationManager::class.java)
                if (manager.getNotificationChannel("greenhouse_quiet") == null)
                    manager.createNotificationChannel(android.app.NotificationChannel("greenhouse_quiet", "Greenhouse status", NotificationManager.IMPORTANCE_LOW))
                manager.notify(id, notification)
                sent.edit().putString(key, date).apply()
                return
            }
        }
    }
}
