package studio.junecity.greenhouse.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import org.json.JSONArray
import studio.junecity.greenhouse.R
import studio.junecity.greenhouse.cloud.TemperatureBreach
import studio.junecity.greenhouse.model.*
import studio.junecity.greenhouse.settings.AppPreferencesStore
import studio.junecity.greenhouse.settings.CloudEnrollment
import studio.junecity.greenhouse.settings.isStandaloneClient
import studio.junecity.greenhouse.widget.ClimateWidget
import kotlin.math.roundToInt

class AlertNotifier(private val context: Context, private val suffix: String = "") {
    private enum class Metric(val prefix: String, val tag: String) {
        Temperature("", "sensor"), Humidity("rh_", "humidity"), Connection("offline_", "offline")
    }

    companion object {
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
                        (when (store.getString("condition_$id", null)?.substringBefore(':')) {
                            "Temperature low", "Temperature high" -> rules.temperatureAlerts
                            "Humidity low", "Humidity high" -> rules.humidityAlerts // Existing single-condition records.
                            else -> false
                        } || (rules.humidityAlerts && store.contains("rh_condition_$id")))
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

    private fun clearRecords(id: String) {
        state.edit {
            for (metric in Metric.entries) {
                manager.cancel("${metric.tag}/$id", id.hashCode())
                for (field in listOf("condition", "sent", "lastMessage")) remove("${metric.prefix}${field}_$id")
            }
            remove("measured_$id").remove("owner_$id").remove("tenant_$id").remove("house_$id")
        }
        cancelOlderNotification(id)
    }

    fun forgetSensor(id: String) = synchronized(state) {
        clearRecords(id)
        ClimateWidget.changed(context)
    }

    private fun cancelOlderNotification(id: String) {
        if (manager.activeNotifications.any { it.tag == null && it.id == id.hashCode() &&
                it.notification.channelId in setOf("greenhouse_alerts", "greenhouse_quiet") }) {
            manager.cancel(id.hashCode())
        }
    }

    internal fun recordedLocalMessage(id: String?, house: String, at: Long, owner: CloudEnrollment, tag: String? = null): String? {
        if (id == null || suffix != "_standalone" || state.getString("tenant_$id", null) != owner.tenantId ||
            state.getString("house_$id", null) != house) return null
        val metric = if (tag == null) Metric.Temperature else Metric.entries.find { tag == "${it.tag}/$id" } ?: return null
        return state.getString("${metric.prefix}lastMessage_$id", null).takeIf {
            state.getLong("${metric.prefix}sent_$id", -1) == at
        }
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
            if (sensor.online && now - sensor.updatedAtEpochMs !in 0L..600_000L) return
            val scope = owner?.let(::collectorScope)
            val recordedScope = state.getString("owner_$id", null)
            val previousHouse = state.getString("house_$id", null)
            val changedSource = owner != null && (recordedScope != null && recordedScope != scope ||
                previousHouse != null && previousHouse != house ||
                state.getString("tenant_$id", null)?.let { it != owner.tenantId } == true)
            if (changedSource) {
                clearRecords(id)
                ClimateWidget.changed(context)
            }
            if (sensor.online && sensor.updatedAtEpochMs < state.getLong("measured_$id", 0)) return
            state.edit {
                putString("house_$id", house)
                if (owner != null) putString("owner_$id", scope).putString("tenant_$id", owner.tenantId)
                if (sensor.online) putLong("measured_$id", sensor.updatedAtEpochMs)
            }
            val moved = migrateLegacyCondition(id)
            val title = listOfNotNull(settings.greenhouses.find { it.id == house }?.name,
                sensor.zone.takeUnless { it == "Unassigned" }, sensor.name).joinToString(" · ")

            fun record(metric: Metric, message: String?) {
                updateCondition(metric, sensor.id, house, owner, title, message, prefs, now, moved == metric)
            }
            record(Metric.Connection, if (!sensor.online && prefs.offlineAlerts) "Sensor offline" else null)
            if (sensor.online) {
                val temperature = sensor.temperatureF?.takeIf(Double::isFinite)
                val humidity = sensor.humidityPercent?.takeIf { it in 0.0..100.0 }
                if (!prefs.temperatureAlerts || temperature != null) record(Metric.Temperature,
                    temperature?.takeIf { prefs.temperatureAlerts }?.let {
                        limitMessage("Temperature", it, prefs.lowTemperatureF, prefs.highTemperatureF, "°F")
                    })
                if (!prefs.humidityAlerts || humidity != null) record(Metric.Humidity,
                    humidity?.takeIf { prefs.humidityAlerts }?.let {
                        limitMessage("Humidity", it, prefs.lowHumidity, prefs.highHumidity, "%")
                    })
            }
            if ((recordedScope == null && owner != null) || moved != null) ClimateWidget.changed(context)
        }
    }

    // Older versions kept whichever condition won first under the temperature notification tag.
    private fun migrateLegacyCondition(id: String): Metric? {
        val old = state.getString("condition_$id", null) ?: return null
        val metric = when {
            old.startsWith("Humidity ") -> Metric.Humidity
            old == "Sensor offline" -> Metric.Connection
            else -> return null
        }
        state.edit {
            putString("${metric.prefix}condition_$id", old.substringBefore(':'))
            putLong("${metric.prefix}sent_$id", state.getLong("sent_$id", 0))
            putString("${metric.prefix}lastMessage_$id", state.getString("lastMessage_$id", null))
            remove("condition_$id")
        }
        manager.cancel("sensor/$id", id.hashCode())
        return metric
    }

    private fun updateCondition(metric: Metric, id: String, house: String, owner: CloudEnrollment?, title: String,
                                message: String?, prefs: NotificationPreferences, now: Long, migrated: Boolean) {
        val prefix = metric.prefix
        val key = "${prefix}condition_$id"
        val previous = state.getString(key, null)?.substringBefore(':')
        val tag = "${metric.tag}/$id"
        if (message == null) {
            if (previous != null) {
                state.edit { remove(key) }
                manager.cancel(tag, id.hashCode())
                ClimateWidget.changed(context)
            }
            return
        }
        val kind = message.substringBefore(':')
        val repeatDue = prefs.repeatedAlerts && now - state.getLong("${prefix}sent_$id", 0) >= prefs.repeatMinutes * 60_000L
        if (previous == kind && !repeatDue && !migrated) return
        val channel = if (prefs.popupsEnabled) "greenhouse_alerts" else "greenhouse_quiet"
        val notice = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification_greenhouse)
            .setContentTitle(title).setContentText(message)
            .setContentIntent(AlertDestination.pending(context, id.hashCode(), house, id, false, now, tag,
                owner?.tenantId?.takeIf { suffix == "_standalone" && owner.isStandaloneClient() }))
            .setAutoCancel(true)
            .setVibrate(if (prefs.vibrationEnabled) longArrayOf(0, 250, 150, 250) else longArrayOf(0))
        if (prefs.soundEnabled) notice.setDefaults(NotificationCompat.DEFAULT_SOUND)
        if (migrated && previous == kind && !repeatDue) notice.setSilent(true)
        runCatching {
            manager.notify(tag, id.hashCode(), notice.build())
            cancelOlderNotification(id)
            state.edit {
                putString(key, kind).putLong("${prefix}sent_$id", now)
                putString("${prefix}lastMessage_$id", message)
            }
            ClimateWidget.changed(context)
        }
    }

    private fun limitMessage(name: String, value: Double, low: Double, high: Double, unit: String): String? =
        when (TemperatureBreach.Direction.forReading(value, low, high)) {
            TemperatureBreach.Direction.LOW -> "$name low: ${value.roundToInt()}$unit"
            TemperatureBreach.Direction.HIGH -> "$name high: ${value.roundToInt()}$unit"
            null -> null
        }
}
