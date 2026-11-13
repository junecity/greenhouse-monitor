package studio.junecity.greenhouse.cloud

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
import studio.junecity.greenhouse.MainActivity
import studio.junecity.greenhouse.settings.SecureSettingsStore

object CloudAlertPollingPolicy {
    fun delayMs(consecutiveFailures: Int): Long = when (consecutiveFailures.coerceAtLeast(0)) {
        0 -> 60_000L
        1 -> 30_000L
        2 -> 60_000L
        else -> 120_000L
    }
}

private const val OFFLINE_TITLE = "Greenhouse server offline"
private const val CRITICAL_CHANNEL = "greenhouse_cloud_critical"

internal fun clearRecoveredOfflineAlerts(notifications: NotificationManager, alerts: List<MonitoringAlert>) {
    val currentIds = alerts.filter { it.type == MonitoringAlertType.ServerOffline }.map { it.id.hashCode() }.toSet()
    // The channel and title also identify notifications posted before the upgrade.
    notifications.activeNotifications.filter { posted ->
        posted.notification.channelId == CRITICAL_CHANNEL &&
            posted.notification.extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString() == OFFLINE_TITLE &&
            posted.id !in currentIds
    }.forEach { notifications.cancel(it.id) }
}

class CloudAlertService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val polling = EnrollmentBoundWorker(scope)
    private lateinit var notifications: NotificationManager

    override fun onCreate() {
        super.onCreate()
        notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel(SERVICE_CHANNEL, "Greenhouse cloud alerts", NotificationManager.IMPORTANCE_LOW))
        notifications.createNotificationChannel(NotificationChannel(CRITICAL_CHANNEL, "Critical greenhouse alerts", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Critical temperature and server-offline safety alerts"
            enableVibration(true)
        })
        notifications.createNotificationChannel(NotificationChannel(HIGH_CHANNEL, "Greenhouse equipment alerts", NotificationManager.IMPORTANCE_HIGH))
        val notification = NotificationCompat.Builder(this, SERVICE_CHANNEL)
            .setSmallIcon(android.R.drawable.presence_online)
            .setContentTitle("Greenhouse alert monitoring active")
            .setContentText("Checking junecity.studio greenhouse alerts")
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
                if (inbox.complete) clearRecoveredOfflineAlerts(notifications, alerts)
                alerts.forEach(::showAlert)
            } else {
                failures++
            }
            delay(CloudAlertPollingPolicy.delayMs(failures))
        }
    }

    private fun showAlert(alert: MonitoringAlert) {
        val notificationId = alert.id.hashCode()
        val openApp = studio.junecity.greenhouse.data.AlertDestination.pending(
            this, notificationId, alert.greenhouseId, null, true, alert.occurredAtEpochMs
        )
        val acknowledge = PendingIntent.getBroadcast(
            this,
            notificationId,
            Intent(this, CloudAlertAcknowledgeReceiver::class.java)
                .putExtra(CloudAlertAcknowledgeReceiver.EXTRA_ALERT_ID, alert.id)
                .putExtra(CloudAlertAcknowledgeReceiver.EXTRA_NOTIFICATION_ID, notificationId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val channel = if (alert.priority == MonitoringAlertPriority.Critical || alert.type == MonitoringAlertType.ServerOffline) {
            CRITICAL_CHANNEL
        } else HIGH_CHANNEL
        val title = when (alert.type) {
            MonitoringAlertType.CriticalTemperature -> "Critical greenhouse temperature"
            MonitoringAlertType.ServerOffline -> OFFLINE_TITLE
            MonitoringAlertType.SensorCommunicationFailure -> "Greenhouse sensor connection"
            MonitoringAlertType.Information -> "Greenhouse status"
        }
        val message = if (alert.type == MonitoringAlertType.ServerOffline) {
            "The monitoring service has missed the greenhouse server’s heartbeat."
        } else alert.message
        val builder = NotificationCompat.Builder(this, channel)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
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
        val notification = builder.build()
        runCatching { notifications.notify(notificationId, notification) }
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val SERVICE_CHANNEL = "greenhouse_cloud_alert_service"
        private const val HIGH_CHANNEL = "greenhouse_cloud_high"
        private const val SERVICE_NOTIFICATION_ID = 4174

        fun sync(context: Context, enabled: Boolean) {
            val intent = Intent(context, CloudAlertService::class.java)
            runCatching {
                if (enabled) context.startForegroundService(intent) else context.stopService(intent)
            }
        }
    }
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
