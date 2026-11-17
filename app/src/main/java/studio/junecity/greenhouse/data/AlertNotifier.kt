package studio.junecity.greenhouse.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import org.json.JSONArray
import studio.junecity.greenhouse.R
import studio.junecity.greenhouse.cloud.TemperatureBreach
import studio.junecity.greenhouse.model.*
import studio.junecity.greenhouse.settings.AppPreferencesStore
import studio.junecity.greenhouse.settings.isStandaloneClient
import studio.junecity.greenhouse.settings.CloudEnrollment
import studio.junecity.greenhouse.widget.ClimateWidget
import kotlin.math.roundToInt

class AlertNotifier(private val context: Context, private val suffix: String = "") {
    companion object {
        // A standalone collector keeps its own store while the viewer switches between servers.
        private fun collectorScope(owner: CloudEnrollment) = JSONArray(listOf(
            owner.tenantId, owner.deviceId, owner.role.name, if (owner.isStandaloneClient()) "" else owner.greenhouseId
        )).toString()

        internal fun hasClimateAlarm(context: Context, owner: CloudEnrollment, house: String,
                                     sensors: List<GoveeSensor>, rules: NotificationPreferences, now: Long): Boolean {
            if (!rules.masterEnabled || now < rules.snoozedUntilEpochMs || sensors.isEmpty()) return false
            val suffix = if (owner.isStandaloneClient()) "_standalone" else ""
            val store = context.getSharedPreferences("alert_state$suffix", Context.MODE_PRIVATE)
            val scope = collectorScope(owner)
            return synchronized(store) {
                sensors.any { sensor ->
                    val id = sensor.id
                    store.getString("owner_$id", null) == scope && store.getString("house_$id", null) == house &&
                        when (store.getString("condition_$id", null)?.substringBefore(':')) {
                            "Temperature low", "Temperature high" -> rules.temperatureAlerts
                            "Humidity low", "Humidity high" -> rules.humidityAlerts
                            else -> false
                        }
                }
            }
        }
    }

    private val manager = context.getSystemService(NotificationManager::class.java)
    private val state = context.getSharedPreferences("alert_state$suffix", Context.MODE_PRIVATE)

    init {
        manager.createNotificationChannel(NotificationChannel("greenhouse_quiet", "Greenhouse status", NotificationManager.IMPORTANCE_LOW))
        manager.createNotificationChannel(NotificationChannel("greenhouse_alerts", "Greenhouse alerts", NotificationManager.IMPORTANCE_HIGH))
    }

    fun forgetSensor(id: String) = synchronized(state) {
        manager.cancel("sensor/$id", id.hashCode())
        cancelOlderNotification(id)
        state.edit().remove("condition_$id").remove("sent_$id").remove("measured_$id")
            .remove("owner_$id").remove("tenant_$id").remove("house_$id").remove("lastMessage_$id").apply()
        ClimateWidget.changed(context)
    }

    private fun cancelOlderNotification(id: String) {
        // Earlier versions used only the hash as the system notification ID.
        if (manager.activeNotifications.any { it.tag == null && it.id == id.hashCode() &&
                it.notification.channelId in setOf("greenhouse_alerts", "greenhouse_quiet") }) {
            manager.cancel(id.hashCode())
        }
    }

    internal fun recordedLocalMessage(id: String?, house: String, at: Long, owner: CloudEnrollment): String? {
        if (id == null || suffix != "_standalone" || state.getLong("sent_$id", -1) != at ||
            state.getString("tenant_$id", null) != owner.tenantId || state.getString("house_$id", null) != house) return null
        return state.getString("lastMessage_$id", null)
    }

    fun evaluate(sensor: GoveeSensor, owner: CloudEnrollment? = null) {
        val settings = AppPreferencesStore(context, suffix).loadForMonitoring(owner)
        val house = settings.sensorHouse(sensor)
        if (!settings.monitorsSensor(sensor)) return
        val prefs = settings.forAlertsIn(house)
        val now = System.currentTimeMillis()
        if (!prefs.masterEnabled || now < prefs.snoozedUntilEpochMs) return

        synchronized(state) {
            val id = sensor.id
            val key = "condition_$id"
            val lastSample = "measured_$id"
            val storedCondition = state.getString(key, null)
            val scope = owner?.let(::collectorScope)
            val recordedScope = state.getString("owner_$id", null)
            val sameSource = owner == null ||
                (recordedScope == scope && state.getString("house_$id", null) == house)
            // Earlier standalone installs already recorded the receiving tenant and floor plan.
            val legacyStandalone = recordedScope == null && suffix == "_standalone" && owner != null &&
                state.getString("tenant_$id", null) == owner.tenantId && state.getString("house_$id", null) == house
            val priorKind = storedCondition?.substringBefore(':')
            val previous = priorKind.takeIf { sameSource || legacyStandalone }
            if (sensor.online) {
                if (now - sensor.updatedAtEpochMs !in 0L..600_000L ||
                    ((sameSource || legacyStandalone) && sensor.updatedAtEpochMs < state.getLong(lastSample, 0L))) return
                state.edit().putLong(lastSample, sensor.updatedAtEpochMs).apply()
            } else if (!prefs.offlineAlerts) return
            val message = condition(sensor, prefs)
            if (message == null) {
                // An absent measurement cannot establish recovery of the previous excursion.
                if (previous?.startsWith("Temperature ") == true && sensor.temperatureF?.isFinite() != true) return
                if (previous?.startsWith("Humidity ") == true && sensor.humidityPercent?.isFinite() != true) return
                if (storedCondition != null) {
                    state.edit().remove(key).apply()
                    ClimateWidget.changed(context)
                }
                return
            }
            val kind = message.substringBefore(':')
            val lastSent = state.getLong("sent_$id", 0)
            val repeatDue = prefs.repeatedAlerts && now - lastSent >= prefs.repeatMinutes * 60_000L
            if (previous == kind && !repeatDue) {
                if (legacyStandalone) {
                    state.edit().putString("owner_$id", scope).apply()
                    ClimateWidget.changed(context)
                }
                return
            }

            val tag = "sensor/$id"
            val title = listOfNotNull(settings.greenhouses.find { it.id == house }?.name,
                sensor.zone.takeUnless { it == "Unassigned" }, sensor.name).joinToString(" · ")
            val channel = if (prefs.popupsEnabled) "greenhouse_alerts" else "greenhouse_quiet"
            val notice = NotificationCompat.Builder(context, channel)
                // Old server records lack an owner. Bind the fresh reading and replace an
                // already visible matching notification silently during the upgrade.
                .setOnlyAlertOnce(recordedScope == null && priorKind == kind && !repeatDue)
                .setSmallIcon(R.drawable.ic_notification_greenhouse)
                .setContentTitle(title)
                .setContentText(message)
                .setContentIntent(AlertDestination.pending(context, id.hashCode(), house, id, false, now, tag,
                    owner?.tenantId?.takeIf { suffix == "_standalone" && owner.isStandaloneClient() }))
                .setAutoCancel(true)
                .setVibrate(if (prefs.vibrationEnabled) longArrayOf(0, 250, 150, 250) else longArrayOf(0))
            if (prefs.soundEnabled) notice.setDefaults(NotificationCompat.DEFAULT_SOUND)
            runCatching {
                manager.notify(tag, id.hashCode(), notice.build())
                cancelOlderNotification(id)
                val saved = state.edit().putString(key, kind).putLong("sent_$id", now)
                    .putString("owner_$id", scope).putString("house_$id", house)
                if (suffix == "_standalone" && owner != null) {
                    saved.putString("tenant_$id", owner.tenantId).putString("lastMessage_$id", message)
                }
                saved.apply()
                ClimateWidget.changed(context)
            }
        }
    }

    private fun condition(sensor: GoveeSensor, prefs: NotificationPreferences): String? {
        if (!sensor.online) return if (prefs.offlineAlerts) "Sensor offline" else null
        val temperature = sensor.temperatureF?.takeIf { it.isFinite() }
        val humidity = sensor.humidityPercent?.takeIf { it.isFinite() }
        if (prefs.temperatureAlerts && temperature != null) {
            when (TemperatureBreach.Direction.forReading(temperature, prefs.lowTemperatureF, prefs.highTemperatureF)) {
                TemperatureBreach.Direction.LOW -> return "Temperature low: ${temperature.roundToInt()}°F"
                TemperatureBreach.Direction.HIGH -> return "Temperature high: ${temperature.roundToInt()}°F"
                null -> Unit
            }
        }
        return when {
            prefs.humidityAlerts && humidity != null && humidity < prefs.lowHumidity -> "Humidity low: ${humidity.roundToInt()}%"
            prefs.humidityAlerts && humidity != null && humidity > prefs.highHumidity -> "Humidity high: ${humidity.roundToInt()}%"
            else -> null
        }
    }
}
