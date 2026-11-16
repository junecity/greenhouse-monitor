package studio.junecity.greenhouse.cloud

import studio.junecity.greenhouse.R
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import studio.junecity.greenhouse.settings.SecureSettingsStore

object CloudAlertPollingPolicy {
    fun delayMs(consecutiveFailures: Int): Long = when (consecutiveFailures.coerceAtLeast(0)) {
        0 -> 60_000L
        1 -> 30_000L
        2 -> 60_000L
        else -> 120_000L
    }
}

private const val CRITICAL_TITLE = "Critical greenhouse temperature"
private const val OFFLINE_TITLE = "Greenhouse server offline"
private val CRITICAL_CHANNEL = TemperatureSound.Existing.channelId

internal fun clearRecoveredCloudAlerts(notifications: NotificationManager, inbox: AlertInbox) {
    if (!inbox.complete) return
    val current = inbox.alerts.mapTo(mutableSetOf()) { it.id.hashCode() }
    // Match both current alerts and legacy, untagged notices already in the tray after an upgrade.
    for (posted in notifications.activeNotifications) {
        if (posted.tag != null || posted.id in current) continue
        val channel = posted.notification.channelId
        val title = posted.notification.extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString()
        val offline = title == OFFLINE_TITLE && channel == CRITICAL_CHANNEL
        val temperature = inbox.criticalIncidentsVerified && title == CRITICAL_TITLE &&
            TemperatureSound.entries.any { it.channelId == channel }
        if (offline || temperature) notifications.cancel(posted.id)
    }
}

class CloudAlertService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val polling = EnrollmentBoundWorker(scope)
    private lateinit var notifications: NotificationManager

    override fun onCreate() {
        super.onCreate()
        notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel(SERVICE_CHANNEL, "Greenhouse cloud alerts", NotificationManager.IMPORTANCE_LOW))
        CriticalAlarm.recover(this)
        TemperatureSound.Existing.channel(this)
        notifications.createNotificationChannel(NotificationChannel(HIGH_CHANNEL, "Greenhouse equipment alerts", NotificationManager.IMPORTANCE_HIGH))
        val notification = NotificationCompat.Builder(this, SERVICE_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification_greenhouse)
            .setContentTitle("Greenhouse alert monitoring active")
            .setContentText("Checking greenhouse alerts")
            .setOngoing(true).setOnlyAlertOnce(true).build()
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, SERVICE_NOTIFICATION_ID, notification, type)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        polling.start(SecureSettingsStore(this).cloudEnrollment()) { pollLoop() }
        return START_STICKY
    }

    private suspend fun pollLoop() {
        val enrollment = SecureSettingsStore(this).cloudEnrollment()
        val configuration = runCatching { CloudRuntime.configurationOrNull() }.getOrNull()
        if (enrollment == null || configuration == null) {
            stopSelf(); return
        }
        val gateway = runCatching { gatewayForEnrollment(this, configuration, enrollment) }.getOrElse { stopSelf(); return }
        var failures = 0
        while (scope.isActive) {
            val result = runCatching { gateway.pendingAlerts(enrollment.role == CloudDeviceRole.ViewerClient) }
            if (result.isSuccess) {
                failures = 0
                val inbox = result.getOrThrow()
                val alerts = inbox.alerts
                clearRecoveredCloudAlerts(notifications, inbox)
                alerts.forEach { postCloudAlert(this, it, enrollment.role) }
            } else {
                failures++
            }
            delay(CloudAlertPollingPolicy.delayMs(failures))
        }
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val SERVICE_CHANNEL = "greenhouse_cloud_alert_service"
        private const val HIGH_CHANNEL = "greenhouse_cloud_high"
        private const val SERVICE_NOTIFICATION_ID = 4174

        internal fun alertChannel(context: Context, alert: MonitoringAlert, role: CloudDeviceRole): String = when {
            role == CloudDeviceRole.ViewerClient && alert.type == MonitoringAlertType.CriticalTemperature ->
                TemperatureSound.selected(context).channel(context).id
            alert.priority == MonitoringAlertPriority.Critical || alert.type == MonitoringAlertType.ServerOffline -> CRITICAL_CHANNEL
            else -> HIGH_CHANNEL
        }

        fun sync(context: Context, enabled: Boolean) {
            val intent = Intent(context, CloudAlertService::class.java)
            runCatching {
                if (enabled) context.startForegroundService(intent) else context.stopService(intent)
            }
        }
    }
}

internal fun postCloudAlert(context: Context, alert: MonitoringAlert, role: CloudDeviceRole) {
    val notifications = context.getSystemService(NotificationManager::class.java)
    val notificationId = alert.id.hashCode()
    val openApp = studio.junecity.greenhouse.data.AlertDestination.pending(
        context, notificationId, alert.greenhouseId, null, true, alert.occurredAtEpochMs
    )
    val acknowledge = PendingIntent.getBroadcast(
        context,
        notificationId,
        Intent(context, CloudAlertAcknowledgeReceiver::class.java)
            .putExtra(CloudAlertAcknowledgeReceiver.EXTRA_ALERT_ID, alert.id)
            .putExtra(CloudAlertAcknowledgeReceiver.EXTRA_NOTIFICATION_ID, notificationId),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    val channel = CloudAlertService.alertChannel(context, alert, role)
    val title = when (alert.type) {
        MonitoringAlertType.CriticalTemperature -> CRITICAL_TITLE
        MonitoringAlertType.ServerOffline -> OFFLINE_TITLE
        MonitoringAlertType.SensorCommunicationFailure -> "Greenhouse sensor connection"
        MonitoringAlertType.Information -> "Greenhouse status"
    }
    val message = if (alert.type == MonitoringAlertType.ServerOffline) {
        "The monitoring service has missed the greenhouse server’s heartbeat."
    } else alert.message
    val builder = NotificationCompat.Builder(context, channel)
        .setSmallIcon(R.drawable.ic_notification_greenhouse)
        .setContentTitle(title)
        .addExtras(android.os.Bundle().apply {
            putString(studio.junecity.greenhouse.data.AlertDestination.HOUSE_KEY, alert.greenhouseId)
        })
        .setWhen(alert.occurredAtEpochMs).setShowWhen(true)
        .setContentText(message)
        .setStyle(NotificationCompat.BigTextStyle().bigText(message))
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .setCategory(NotificationCompat.CATEGORY_ALARM)
        .setContentIntent(openApp)
        .addAction(0, "Acknowledge", acknowledge)
        .setAutoCancel(false)
        .setOngoing(alert.requiresAcknowledgement)
        .setOnlyAlertOnce(true)
    if (!alert.requiresAcknowledgement) builder.setDeleteIntent(acknowledge)
    if (role == CloudDeviceRole.ViewerClient && alert.type == MonitoringAlertType.CriticalTemperature &&
        CriticalAlarm.enabled(context) && notifications.activeNotifications.none { it.id == notificationId && it.tag == null } &&
        CriticalAlarm.play(context, notifications.getNotificationChannel(channel))) builder.setSilent(true)
    val notification = builder.build()
    runCatching { notifications.notify(notificationId, notification) }
}


class CloudAlertAcknowledgeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val alertId = intent.getStringExtra(EXTRA_ALERT_ID) ?: return
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, alertId.hashCode())
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                runCatching {
                    val enrollment = SecureSettingsStore(context).cloudEnrollment() ?: return@runCatching
                    val configuration = CloudRuntime.configurationOrNull() ?: return@runCatching
                    gatewayForEnrollment(context.applicationContext, configuration, enrollment).acknowledgeAlert(alertId)
                    context.getSystemService(NotificationManager::class.java).cancel(notificationId)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val EXTRA_ALERT_ID = "cloudAlertId"
        const val EXTRA_NOTIFICATION_ID = "cloudAlertNotificationId"
    }
}
