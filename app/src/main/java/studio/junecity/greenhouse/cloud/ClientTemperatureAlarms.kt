package studio.junecity.greenhouse.cloud

import android.app.NotificationManager
import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import studio.junecity.greenhouse.settings.CloudEnrollment
import studio.junecity.greenhouse.settings.SecureSettingsStore

/**
 * Receipt and human acknowledgement are independent of whether the server still reports a breach.
 * A phone has one enrolled owner at a time. Changing the selected greenhouse keeps that owner's
 * alarms, including alarms from other greenhouses in the same account.
 */
internal object ClientTemperatureAlarms {
    private const val OWNER = "owner"
    private const val LIVE = "incident/"
    private const val STOPPED = "stopped"
    private const val ACK_QUEUE = "ack_queue"

    private fun preferences(context: Context) = context.getSharedPreferences("client_temperature_incidents", Context.MODE_PRIVATE)

    private fun owner(enrollment: CloudEnrollment?): String? = enrollment
        ?.takeIf { it.role == CloudDeviceRole.ViewerClient && it.deviceId.isNotBlank() }
        ?.let { "${it.tenantId}|${it.deviceId}" }

    private fun isCurrent(context: Context, enrollment: CloudEnrollment?) =
        owner(enrollment) != null && owner(enrollment) == owner(SecureSettingsStore(context).cloudEnrollment())

    fun watch(context: Context, changed: () -> Unit): () -> Unit {
        val prefs = preferences(context)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key == OWNER || key.startsWith(LIVE)) changed()
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        return { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    private fun incidents(prefs: SharedPreferences): List<MonitoringAlert> = prefs.all.mapNotNull { (key, value) ->
        if (!key.startsWith(LIVE) || value !is String) return@mapNotNull null
        val entry = JSONObject(value)
        MonitoringAlert(key.removePrefix(LIVE), entry.getString("house"), MonitoringAlertType.CriticalTemperature,
            MonitoringAlertPriority.Critical, entry.getString("message"), entry.getLong("time"), true,
            entry.optString("server").ifBlank { null })
    }.sortedBy { it.occurredAtEpochMs }

    @Synchronized fun active(context: Context, enrollment: CloudEnrollment?): List<MonitoringAlert> {
        val prefs = preferences(context)
        if (!isCurrent(context, enrollment) || prefs.getString(OWNER, null) != owner(enrollment)) return emptyList()
        return incidents(prefs)
    }

    @Synchronized fun forgetIfOwnerChanged(context: Context, previous: CloudEnrollment?, current: CloudEnrollment?) {
        if (owner(previous) != owner(current) && preferences(context).getString(OWNER, null) != owner(current)) forget(context)
    }

    @Synchronized fun forgetIfCurrent(context: Context, enrollment: CloudEnrollment) {
        if (isCurrent(context, enrollment)) forget(context)
    }

    @Synchronized fun forget(context: Context) {
        val prefs = preferences(context)
        val previousOwner = prefs.getString(OWNER, null) ?: return
        val notifications = context.getSystemService(NotificationManager::class.java)
        CriticalAlarm.stopIncident(context)
        for (incident in incidents(prefs)) notifications.cancel(tag(previousOwner, incident.id), incident.id.hashCode())
        // Individual removals notify an open alert dialog on Android 10 as well as newer releases.
        val edit = prefs.edit()
        prefs.all.keys.forEach { edit.remove(it) }
        edit.commit()
    }

    private fun tag(owner: String, id: String) = "greenhouse.temperature/$owner/$id"

    /** Called once before networking on service start and after each successful authenticated poll. */
    @Synchronized fun deliver(context: Context, enrollment: CloudEnrollment, inbox: AlertInbox? = null) {
        if (!isCurrent(context, enrollment)) return
        val owner = requireNotNull(owner(enrollment))
        val prefs = preferences(context)
        if (prefs.getString(OWNER, null) != owner) forget(context)
        val stopped = prefs.getStringSet(STOPPED, emptySet()).orEmpty()
        if (inbox != null) {
            val critical = inbox.alerts.filter { it.type == MonitoringAlertType.CriticalTemperature }
            val edit = prefs.edit().putString(OWNER, owner)
            for (alert in critical) {
                if (alert.id in stopped) continue
                val entry = JSONObject().put("house", alert.greenhouseId).put("time", alert.occurredAtEpochMs)
                    .put("message", alert.message).put("server", alert.serverId).toString()
                if (entry != prefs.getString(LIVE + alert.id, null)) edit.putString(LIVE + alert.id, entry)
            }
            if (inbox.complete && inbox.criticalIncidentsVerified) {
                val pending = prefs.getStringSet(ACK_QUEUE, emptySet()).orEmpty()
                edit.putStringSet(STOPPED, stopped.intersect(critical.map { it.id }.toSet() + pending))
            }
            edit.commit()
        }
        val active = incidents(prefs)
        if (active.isEmpty()) {
            CriticalAlarm.stopIncident(context)
            return
        }
        val channel = TemperatureSound.selected(context).channel(context)
        val sounding = CriticalAlarm.startIncident(context, channel)
        val notifications = context.getSystemService(NotificationManager::class.java)
        val posted = notifications.activeNotifications
        for (alarm in active) {
            val id = alarm.id.hashCode()
            val tag = tag(owner, alarm.id)
            val old = posted.firstOrNull { it.id == id && it.tag == tag }?.notification
            if (old?.channelId == channel.id && old.extras.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString() == alarm.message) continue
            // Replace the old notification action when upgrading during an already active breach.
            if (posted.any { it.tag == null && it.id == id && it.notification.extras.getCharSequence(android.app.Notification.EXTRA_TITLE) == "Critical greenhouse temperature" }) {
                notifications.cancel(id)
            }
            postCloudAlert(context, alarm, enrollment.role, tag, sounding)
        }
    }

    @Synchronized fun stop(context: Context, enrollment: CloudEnrollment?, ids: Set<String>) {
        val prefs = preferences(context)
        val active = active(context, enrollment)
        val targets = active.filter { it.id in ids }.mapTo(mutableSetOf()) { it.id }
        if (targets.isEmpty()) return
        val edit = prefs.edit()
            .putStringSet(STOPPED, prefs.getStringSet(STOPPED, emptySet()).orEmpty() + targets)
            .putStringSet(ACK_QUEUE, prefs.getStringSet(ACK_QUEUE, emptySet()).orEmpty() + targets)
        targets.forEach { edit.remove(LIVE + it) }
        edit.commit()
        if (targets.size == active.size) CriticalAlarm.stopIncident(context)
        val notifications = context.getSystemService(NotificationManager::class.java)
        for (id in targets) notifications.cancel(tag(requireNotNull(owner(enrollment)), id), id.hashCode())
        CloudAlertService.requestAcknowledgements(context)
    }

    @Synchronized fun pendingAcknowledgements(context: Context, enrollment: CloudEnrollment): Set<String> {
        val prefs = preferences(context)
        if (!isCurrent(context, enrollment) || prefs.getString(OWNER, null) != owner(enrollment)) return emptySet()
        return prefs.getStringSet(ACK_QUEUE, emptySet()).orEmpty().toSet()
    }

    @Synchronized fun acknowledged(context: Context, enrollment: CloudEnrollment, alertId: String) {
        val prefs = preferences(context)
        val pending = pendingAcknowledgements(context, enrollment)
        if (alertId in pending) prefs.edit().putStringSet(ACK_QUEUE, pending - alertId).commit()
    }
}
