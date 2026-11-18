package studio.junecity.greenhouse.cloud

import studio.junecity.greenhouse.R
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.os.PowerManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import studio.junecity.greenhouse.settings.CloudEnrollment
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
internal const val STOP_ALARM_LABEL = "Stop alarm"
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
    private var inForeground = false
    private val wake = Channel<Unit>(Channel.CONFLATED)

    private val network = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { wake.trySend(Unit) }
    }
    private val idle = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!getSystemService(PowerManager::class.java).isDeviceIdleMode) wake.trySend(Unit)
        }
    }

    override fun onCreate() {
        super.onCreate()
        val notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel(SERVICE_CHANNEL, "Greenhouse cloud alerts", NotificationManager.IMPORTANCE_LOW))
        CriticalAlarm.recover(this)
        prepareAlertChannels(this)
        val notification = NotificationCompat.Builder(this, SERVICE_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification_greenhouse)
            .setContentTitle("Greenhouse alert monitoring active")
            .setContentText("Checking greenhouse alerts")
            .setOngoing(true).setOnlyAlertOnce(true).build()
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        try {
            ServiceCompat.startForeground(this, SERVICE_NOTIFICATION_ID, notification, type)
            inForeground = true
        } catch (failure: RuntimeException) {
            logStartFailure(failure)
            return
        }
        runningWake = wake
        getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(network)
        ContextCompat.registerReceiver(this, idle, IntentFilter(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (shouldStopAfterStart(intent)) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (!inForeground) {
            if (intent?.action != RESTORE) enqueueFallback(this)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val enrollment = SecureSettingsStore(this).cloudEnrollment()
        polling.start(enrollment) {
            kotlinx.coroutines.coroutineScope {
                val localCollector = if (enrollment?.role == CloudDeviceRole.ViewerClient) launch {
                    studio.junecity.greenhouse.widget.WidgetCollectorRefresh.monitorStandalone(applicationContext, enrollment)
                } else null
                try { pollLoop(enrollment, startId) } finally { localCollector?.cancel() }
            }
        }
        return START_STICKY
    }

    private suspend fun pollLoop(enrollment: CloudEnrollment?, startId: Int) {
        val security = SecureSettingsStore(this)
        if (enrollment == null) {
            stopSelf(startId)
            return
        }
        // Restore outstanding alarms before any operation that needs an internet connection.
        ClientTemperatureAlarms.deliver(this, enrollment)
        val configuration = runCatching { CloudRuntime.configurationOrNull() }.getOrNull()
        if (configuration == null) {
            if (ClientTemperatureAlarms.active(this, enrollment).isEmpty()) stopSelf(startId)
            return
        }
        val gateway by lazy { gatewayForEnrollment(this, configuration, enrollment) }
        val viewer = enrollment.role == CloudDeviceRole.ViewerClient
        var failures = 0
        while (currentCoroutineContext().isActive && security.cloudEnrollment() == enrollment) {
            val received = CloudAlertDelivery.poll(this, enrollment, minimumIntervalMs = 5_000L,
                fetch = { gateway.pendingAlerts(viewer) }, acknowledge = { gateway.acknowledgeAlert(it) },
                readTemperatures = { gateway.viewerReadingCheck(this, enrollment, it) })
            failures = if (received) 0 else failures + 1
            withTimeoutOrNull(CloudAlertPollingPolicy.delayMs(failures)) { wake.receive() }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        if (inForeground) {
            if (runningWake === wake) runningWake = null
            getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(network)
            unregisterReceiver(idle)
            // The player belongs to the enrolled client's incident. Service teardown can occur
            // while the process is still alive and the customer has yet to stop that alarm.
        }
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        @Volatile private var runningWake: Channel<Unit>? = null
        private var nextStart = 0L
        private var pendingStart: Long? = null
        private var stopAfterStart = false
        private const val START_SEQUENCE = "greenhouse.alertStartSequence"
        private const val RESTORE = "greenhouse.RESTORE_ALERT_MONITOR"

        internal fun requestAcknowledgements(context: Context) {
            // A Stop is immediate and persisted. Retrying its network acknowledgement alone must
            // never create a new foreground service, particularly during sign-out or teardown.
            runningWake?.trySend(Unit)
            enqueueFallback(context)
        }

        private const val SERVICE_CHANNEL = "greenhouse_cloud_alert_service"
        private const val HIGH_CHANNEL = "greenhouse_cloud_high"
        private const val SERVICE_NOTIFICATION_ID = 4174

        internal fun alertChannel(context: Context, alert: MonitoringAlert, role: CloudDeviceRole): String = when {
            role == CloudDeviceRole.ViewerClient && alert.isViewerAlarm ->
                TemperatureSound.selected(context).channel(context).id
            alert.priority == MonitoringAlertPriority.Critical || alert.type == MonitoringAlertType.ServerOffline -> CRITICAL_CHANNEL
            else -> HIGH_CHANNEL
        }

        internal fun prepareAlertChannels(context: Context) {
            TemperatureSound.Existing.channel(context)
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(HIGH_CHANNEL, "Greenhouse equipment alerts", NotificationManager.IMPORTANCE_HIGH)
            )
        }

        private fun logStartFailure(failure: RuntimeException) {
            if (failure !is IllegalStateException && failure !is SecurityException) throw failure
            android.util.Log.w("GreenhouseAlerts", "Android declined foreground alert monitoring.", failure)
        }

        private fun enqueueFallback(context: Context) {
            if (SecureSettingsStore(context).cloudEnrollment()?.role == CloudDeviceRole.ViewerClient)
                CloudAlertWorker.checkSoon(context)
        }

        @Synchronized private fun start(context: Context, intent: Intent) {
            val sequence = ++nextStart
            val previous = pendingStart
            stopAfterStart = false
            pendingStart = sequence
            try {
                context.startForegroundService(intent.putExtra(START_SEQUENCE, sequence))
            } catch (failure: RuntimeException) {
                pendingStart = previous
                logStartFailure(failure)
                if (intent.action != RESTORE) enqueueFallback(context)
            }
        }

        @Synchronized private fun shouldStopAfterStart(intent: Intent?): Boolean {
            if (pendingStart == intent?.getLongExtra(START_SEQUENCE, 0L)) pendingStart = null
            return stopAfterStart
        }

        @Synchronized private fun stopMonitoring(context: Context, intent: Intent) {
            stopAfterStart = true
            // Android 10 can crash the process if stopService overtakes onCreate after a
            // startForegroundService request. A pending start promotes itself and then stops.
            if (pendingStart == null) context.stopService(intent)
        }

        internal fun restoreForAlarm(context: Context) {
            if (runningWake == null) start(context, Intent(context, CloudAlertService::class.java).setAction(RESTORE))
        }

        fun sync(context: Context, enabled: Boolean) {
            val intent = Intent(context, CloudAlertService::class.java)
            if (enabled && SecureSettingsStore(context).cloudEnrollment()?.role == CloudDeviceRole.ViewerClient)
                CloudAlertWorker.schedule(context)
            else CloudAlertWorker.cancel(context)
            if (enabled) start(context, intent) else stopMonitoring(context, intent)
        }
    }
}

internal fun postCloudAlert(context: Context, alert: MonitoringAlert, role: CloudDeviceRole, tag: String? = null, sounding: Boolean = false) {
    val notifications = context.getSystemService(NotificationManager::class.java)
    val notificationId = alert.id.hashCode()
    val openApp = studio.junecity.greenhouse.data.AlertDestination.pending(
        context, notificationId, alert.greenhouseId, alert.sensorId, true, alert.occurredAtEpochMs, tag
    )
    val actionIntent = Intent(context, CloudAlertAcknowledgeReceiver::class.java)
        .putExtra(CloudAlertAcknowledgeReceiver.EXTRA_ALERT_ID, alert.id)
        .putExtra(CloudAlertAcknowledgeReceiver.EXTRA_NOTIFICATION_ID, notificationId)
    if (tag != null) {
        actionIntent.action = CloudAlertAcknowledgeReceiver.STOP_TEMPERATURE
        actionIntent.data = Uri.fromParts("greenhouse-alarm", tag, null)
    }
    val action = PendingIntent.getBroadcast(
        context, notificationId, actionIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    val channel = CloudAlertService.alertChannel(context, alert, role)
    val title = when (alert.type) {
        MonitoringAlertType.CriticalTemperature -> CRITICAL_TITLE
        MonitoringAlertType.CriticalHumidity -> "Critical greenhouse humidity"
        MonitoringAlertType.ServerOffline -> OFFLINE_TITLE
        MonitoringAlertType.SensorCommunicationFailure -> if (tag != null) "Greenhouse sensor offline" else "Greenhouse sensor connection"
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
        .addAction(0, if (tag == null) "Acknowledge" else STOP_ALARM_LABEL, action)
        .setAutoCancel(false)
        .setOngoing(alert.requiresAcknowledgement)
        .setOnlyAlertOnce(true)
    if (!alert.requiresAcknowledgement && tag == null) builder.setDeleteIntent(action)
    if (sounding) builder.setSilent(true)
    runCatching { notifications.notify(tag, notificationId, builder.build()) }
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
                    if (intent.action == STOP_TEMPERATURE) {
                        val tag = intent.data?.schemeSpecificPart
                        val incident = ClientTemperatureAlarms.active(context, enrollment).firstOrNull {
                            it.id == alertId && ClientTemperatureAlarms.matchesNotification(enrollment, it, tag)
                        } ?: return@runCatching
                        ClientTemperatureAlarms.stop(context, enrollment, setOf(incident.id))
                        return@runCatching
                    }
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
        internal const val STOP_TEMPERATURE = "greenhouse.STOP_TEMPERATURE_ALARM"
        const val EXTRA_ALERT_ID = "cloudAlertId"
        const val EXTRA_NOTIFICATION_ID = "cloudAlertNotificationId"
    }
}
