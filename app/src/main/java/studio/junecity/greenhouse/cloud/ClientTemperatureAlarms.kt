package studio.junecity.greenhouse.cloud

import android.app.NotificationManager
import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import studio.junecity.greenhouse.settings.CloudEnrollment
import studio.junecity.greenhouse.settings.SecureSettingsStore
import studio.junecity.greenhouse.widget.ClimateWidget

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
    private const val READINGS = "reading_episodes"
    private const val LAST_CHECK = "last_successful_check"

    private fun preferences(context: Context) = context.getSharedPreferences("client_temperature_incidents", Context.MODE_PRIVATE)

    private fun owner(enrollment: CloudEnrollment?): String? = enrollment
        ?.takeIf { it.role == CloudDeviceRole.ViewerClient && it.deviceId.isNotBlank() }
        ?.let { "${it.tenantId}|${it.deviceId}" }

    private fun isCurrent(context: Context, enrollment: CloudEnrollment?) =
        owner(enrollment) != null && owner(enrollment) == owner(SecureSettingsStore(context).cloudEnrollment())

    fun watch(context: Context, changed: () -> Unit, includeChecks: Boolean = false): () -> Unit {
        val prefs = preferences(context)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key == OWNER || key.startsWith(LIVE) || (includeChecks && key == LAST_CHECK)) changed()
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        return { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    fun lastCheck(context: Context, enrollment: CloudEnrollment): Long? {
        val prefs = preferences(context)
        if (prefs.getString(OWNER, null) != owner(enrollment)) return null
        return prefs.getLong(LAST_CHECK, 0L).takeIf { it > 0 }
    }

    private fun uploadKey(house: String, server: String, generation: Long) = "climateUpload/$house/$server/$generation"

    /** Old inventories expose only initial registration. Require a new sample after first discovery or an observed disconnect. */
    @Synchronized fun readingSources(
        context: Context, enrollment: CloudEnrollment, devices: List<AccountDevice>, now: Long
    ): List<AccountDevice> {
        if (!isCurrent(context, enrollment)) return emptyList()
        val prefs = preferences(context)
        val owner = requireNotNull(owner(enrollment))
        if (prefs.getString(OWNER, null) != owner) forget(context)
        val editor = prefs.edit().putString(OWNER, owner)
        val sources = mutableListOf<AccountDevice>()
        for (source in devices) {
            val device = source.device
            val house = device.greenhouseId?.takeIf { it.isNotBlank() } ?: continue
            if (device.role != CloudDeviceRole.GreenhouseServer || device.tenantId != enrollment.tenantId) continue
            val key = "enrollment/$house/${device.id}"
            val previous = prefs.getLong(key, 0)
            if (!device.enabled || device.communicationSuspended) {
                if (previous != -1L) editor.putLong(key, -1L)
                continue
            }
            val cutoff = when {
                previous < 0L -> maxOf(now, source.enrollmentEpochMs)
                !source.reportsEnrollmentTime && previous == 0L -> maxOf(now, source.createdAtEpochMs)
                else -> maxOf(previous, source.enrollmentEpochMs)
            }
            if (previous != cutoff) editor.putLong(key, cutoff)
            sources.add(source.copy(enrollmentEpochMs = cutoff))
        }
        editor.commit()
        return sources
    }

    @Synchronized fun checkedUpload(context: Context, enrollment: CloudEnrollment, source: AccountDevice): Boolean {
        val stamp = source.lastDataUploadEpochMs ?: return false
        val prefs = preferences(context)
        val key = uploadKey(source.device.greenhouseId ?: return false, source.device.id, source.enrollmentEpochMs)
        return isCurrent(context, enrollment) && prefs.getString(OWNER, null) == owner(enrollment) && prefs.getLong(key, 0) == stamp
    }

    private fun incidents(prefs: SharedPreferences): List<MonitoringAlert> = prefs.all.mapNotNull { (key, value) ->
        if (!key.startsWith(LIVE) || value !is String) return@mapNotNull null
        val entry = JSONObject(value)
        val type = if (entry.optString("type") == MonitoringAlertType.CriticalHumidity.name)
            MonitoringAlertType.CriticalHumidity else MonitoringAlertType.CriticalTemperature
        MonitoringAlert(key.removePrefix(LIVE), entry.getString("house"), type,
            MonitoringAlertPriority.Critical, entry.getString("message"), entry.getLong("time"), true,
            entry.optString("server").ifBlank { null }, entry.optString("sensor").ifBlank { null },
            entry.optLong("resolvedAt").takeIf { it > 0 })
    }.sortedWith(compareBy({ it.occurredAtEpochMs }, { it.id }))

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
        val active = incidents(prefs)
        for (incident in active) notifications.cancel(tag(previousOwner, incident.id), incident.id.hashCode())
        // Individual removals notify an open alert dialog on Android 10 as well as newer releases.
        val edit = prefs.edit()
        prefs.all.keys.forEach { edit.remove(it) }
        edit.commit()
        if (active.isNotEmpty()) ClimateWidget.changed(context)
    }

    private fun tag(owner: String, id: String) = "greenhouse.temperature/$owner/$id"

    fun matchesNotification(enrollment: CloudEnrollment?, incident: MonitoringAlert, notificationTag: String?): Boolean =
        owner(enrollment)?.let { tag(it, incident.id) == notificationTag } == true

    /** Called once before networking on service start and after each successful authenticated poll. */
    @Synchronized fun deliver(context: Context, enrollment: CloudEnrollment, inbox: AlertInbox? = null,
                              readings: List<ServerTemperatures> = emptyList()) {
        if (!isCurrent(context, enrollment)) return
        val owner = requireNotNull(owner(enrollment))
        val prefs = preferences(context)
        if (prefs.getString(OWNER, null) != owner) forget(context)
        val stopped = prefs.getStringSet(STOPPED, emptySet()).orEmpty()
        if (inbox != null || readings.isNotEmpty()) {
            val now = System.currentTimeMillis()
            val critical = inbox?.alerts.orEmpty().filter { it.type.isClimateAlarm }
            val edit = prefs.edit().putString(OWNER, owner)
            if (inbox != null) edit.putLong(LAST_CHECK, now)
            for (batch in readings) batch.uploadTime?.let {
                edit.putLong(uploadKey(batch.house, batch.server, batch.generation), it)
            }
            val ledger = ReadingAlarmLedger(prefs.getString(READINGS, null))
            val update = ledger.update(readings, critical, incidents(prefs), stopped, now)
            val pending = prefs.getStringSet(ACK_QUEUE, emptySet()).orEmpty() + update.acknowledge
            val encoded = ledger.encode()
            if (encoded != prefs.getString(READINGS, null)) edit.putString(READINGS, encoded)
            if (update.acknowledge.isNotEmpty()) edit.putStringSet(ACK_QUEUE, pending)
            var incidentsChanged = false
            for (alert in update.deliver) {
                if (alert.id in stopped) continue
                val entry = JSONObject().put("house", alert.greenhouseId).put("time", alert.occurredAtEpochMs)
                    .put("type", alert.type.name).put("message", alert.message).put("server", alert.serverId).put("sensor", alert.sensorId)
                    .put("resolvedAt", alert.resolvedAtEpochMs).toString()
                if (entry != prefs.getString(LIVE + alert.id, null)) {
                    edit.putString(LIVE + alert.id, entry)
                    incidentsChanged = true
                }
            }
            val suppressed = stopped + update.acknowledge
            if (inbox?.complete == true && inbox.criticalIncidentsVerified) {
                edit.putStringSet(STOPPED, suppressed.intersect(critical.map { it.id }.toSet() + pending))
            } else if (suppressed != stopped) edit.putStringSet(STOPPED, suppressed)
            edit.commit()
            if (incidentsChanged) ClimateWidget.changed(context)
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
            if (old?.channelId == channel.id &&
                old.extras.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString() == alarm.message &&
                old.actions?.any { it.title.toString() == STOP_ALARM_LABEL } == true) continue
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
        val remote = ReadingAlarmLedger(prefs.getString(READINGS, null)).remoteIds(targets)
        val edit = prefs.edit()
            .putStringSet(STOPPED, prefs.getStringSet(STOPPED, emptySet()).orEmpty() + remote)
            .putStringSet(ACK_QUEUE, prefs.getStringSet(ACK_QUEUE, emptySet()).orEmpty() + remote)
        targets.forEach { edit.remove(LIVE + it) }
        edit.commit()
        ClimateWidget.changed(context)
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
