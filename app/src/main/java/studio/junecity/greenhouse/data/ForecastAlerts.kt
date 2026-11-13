package studio.junecity.greenhouse.data

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.work.*
import studio.junecity.greenhouse.MainActivity
import studio.junecity.greenhouse.model.ForecastDay
import studio.junecity.greenhouse.model.WeatherReading
import studio.junecity.greenhouse.settings.AppPreferencesStore
import studio.junecity.greenhouse.settings.SecureSettingsStore
import studio.junecity.greenhouse.settings.isStandaloneClient
import java.time.*
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

data class ForecastRules(
    val cold: Boolean = false, val lowF: Int = 32,
    val heat: Boolean = false, val highF: Int = 90,
    val reminder: Boolean = false, val minuteOfDay: Int = 7 * 60
) {
    fun matches(day: ForecastDay): Map<String, String> = buildMap {
        if (cold && day.lowF != null && day.lowF <= lowF) put("cold", "low of ${day.lowF.roundToInt()}°F")
        if (heat && day.highF != null && day.highF >= highF) put("heat", "high of ${day.highF.roundToInt()}°F")
    }

    fun nextReminder(now: ZonedDateTime): ZonedDateTime {
        val time = LocalTime.of(minuteOfDay / 60, minuteOfDay % 60)
        val today = now.toLocalDate().atTime(time).atZone(now.zone)
        return if (today.isAfter(now)) today else now.toLocalDate().plusDays(1).atTime(time).atZone(now.zone)
    }
}

class ForecastAlerts(private val context: Context) {
    private val enrollment = SecureSettingsStore(context).cloudEnrollment()
    private val namespace = enrollment?.deviceId ?: "local"
    private val prefs = context.getSharedPreferences("forecast_alerts_$namespace", Context.MODE_PRIVATE)
    private val manager = context.getSystemService(NotificationManager::class.java)
    fun currentZip(): String = AppPreferencesStore(context, if (enrollment.isStandaloneClient()) "_standalone" else "").load().zipCode

    fun load() = ForecastRules(prefs.getBoolean("cold", false), prefs.getInt("low", 32),
        prefs.getBoolean("heat", false), prefs.getInt("high", 90),
        prefs.getBoolean("reminder", false), prefs.getInt("minute", 420))

    fun save(rules: ForecastRules) {
        prefs.edit().putBoolean("cold", rules.cold).putInt("low", rules.lowF)
            .putBoolean("heat", rules.heat).putInt("high", rules.highF)
            .putBoolean("reminder", rules.reminder).putInt("minute", rules.minuteOfDay).apply()
        schedule()
    }

    fun schedule() {
        val rules = load()
        val alarms = context.getSystemService(AlarmManager::class.java)
        val pending = PendingIntent.getBroadcast(context, 0,
            Intent(context, ForecastReminderReceiver::class.java).setAction(REMINDER).putExtra("scope", namespace),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        if (rules.reminder) {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, rules.nextReminder(ZonedDateTime.now()).toInstant().toEpochMilli(), pending)
        } else alarms.cancel(pending)
        val work = WorkManager.getInstance(context)
        if (rules.cold || rules.heat || rules.reminder) {
            val request = PeriodicWorkRequestBuilder<ForecastWorker>(1, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
            work.enqueueUniquePeriodicWork("outdoor_forecast", ExistingPeriodicWorkPolicy.KEEP, request)
        } else work.cancelUniqueWork("outdoor_forecast")
    }

    fun remind(scope: String?) {
        if (scope != namespace || !load().reminder) return
        val zip = currentZip()
        val zone = ZoneId.of(prefs.getString("forecast_zone", ZoneId.systemDefault().id))
        val date = LocalDate.now(zone)
        val recent = System.currentTimeMillis() - prefs.getLong("forecast_time", 0) in 0..TimeUnit.HOURS.toMillis(24)
        val summary = if (recent && prefs.getString("forecast_zip", null) == zip)
            prefs.getString("summary_$date", null) else null
        post("daily", "Daily greenhouse forecast · $zip", summary ?: "Open the seven-day outdoor forecast.")
    }

    fun evaluate(weather: WeatherReading) {
        synchronized(lock) {
            if (weather.stale || weather.zipCode != currentZip() || SecureSettingsStore(context).cloudEnrollment()?.deviceId != enrollment?.deviceId) return
            val rules = load()
            if (!rules.cold && !rules.heat && !rules.reminder) return
            val today = LocalDate.now(weather.zone)
            val summary = prefs.edit().putString("forecast_zone", weather.zone.id)
                .putString("forecast_zip", weather.zipCode).putLong("forecast_time", weather.updatedAtEpochMs)
            prefs.all.keys.filter { it.startsWith("summary_") }.forEach { summary.remove(it) }
            weather.days.take(2).forEach { day ->
                val parts = listOfNotNull(day.condition.takeUnless { it == "Unavailable" },
                    day.highF?.let { "High ${it.roundToInt()}°F" }, day.lowF?.let { "Low ${it.roundToInt()}°F" })
                if (parts.isNotEmpty()) summary.putString("summary_${day.date}", parts.joinToString(" · "))
            }
            summary.apply()
            val seen = prefs.getStringSet("delivered", emptySet()).orEmpty().filterTo(mutableSetOf()) {
                it.substringBefore('|') >= today.toString()
            }
            for (day in weather.days.filter { it.date == today || it.date == today.plusDays(1) }) {
                val matches = rules.matches(day)
                val prefix = "${day.date}|${weather.zipCode}|"
                if (matches.keys.none { prefix + it !in seen }) continue
                if (post(prefix, "Outdoor forecast · ${day.date.monthValue}/${day.date.dayOfMonth}",
                        "${weather.location} · ${matches.values.joinToString(", ")}")) {
                    matches.keys.forEach { seen.add(prefix + it) }
                }
            }
            prefs.edit().putStringSet("delivered", seen).apply()
        }
    }

    fun notificationsAvailable(): Boolean = manager.areNotificationsEnabled() &&
        manager.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE

    private fun post(tag: String, title: String, body: String): Boolean {
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Outdoor forecast", NotificationManager.IMPORTANCE_DEFAULT))
        if (!notificationsAvailable()) return false
        val intent = Intent(context, MainActivity::class.java).setAction(OPEN_FORECAST)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val open = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle(title).setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body)).setContentIntent(open).setAutoCancel(true).build()
        return try { manager.notify(tag, 7311, notification); true } catch (_: SecurityException) { false }
    }

    companion object {
        const val OPEN_FORECAST = "studio.junecity.greenhouse.OPEN_FORECAST"
        const val REMINDER = "studio.junecity.greenhouse.FORECAST_REMINDER"
        private const val CHANNEL = "outdoor_forecast"
        private val lock = Any()
    }
}

class ForecastReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val alerts = ForecastAlerts(context)
        if (intent.action == ForecastAlerts.REMINDER) alerts.remind(intent.getStringExtra("scope"))
        alerts.schedule()
    }
}

class ForecastWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val alerts = ForecastAlerts(applicationContext)
        val rules = alerts.load()
        if (!rules.cold && !rules.heat && !rules.reminder) return Result.success()
        return try {
            alerts.evaluate(LiveWeatherProvider().current(alerts.currentZip()))
            Result.success()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: java.io.IOException) {
            Result.retry()
        } catch (_: IllegalStateException) {
            Result.retry()
        }
    }
}
