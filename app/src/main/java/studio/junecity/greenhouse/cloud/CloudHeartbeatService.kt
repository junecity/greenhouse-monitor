package studio.junecity.greenhouse.cloud

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import studio.junecity.greenhouse.model.GoveeSensor
import studio.junecity.greenhouse.settings.AppPreferencesStore
import studio.junecity.greenhouse.settings.SecureSettingsStore

data class HeartbeatHealth(val sensorConnectionHealthy: Boolean, val criticalTemperature: Boolean)

class HeartbeatHealthStore(context: Context) {
    private val values = context.getSharedPreferences("greenhouse_heartbeat_health", Context.MODE_PRIVATE)
    fun save(sensors: List<GoveeSensor>, lowF: Double, highF: Double) {
        val online = sensors.isNotEmpty() && sensors.any(GoveeSensor::online)
        val critical = sensors.asSequence().filter(GoveeSensor::online).mapNotNull(GoveeSensor::temperatureF).any { it < lowF || it > highF }
        values.edit().putBoolean("sensorHealthy", online).putBoolean("criticalTemperature", critical).apply()
    }
    fun load() = HeartbeatHealth(values.getBoolean("sensorHealthy", false), values.getBoolean("criticalTemperature", false))
}

object HeartbeatRetryPolicy {
    fun delayMs(consecutiveFailures: Int): Long = when (consecutiveFailures.coerceAtLeast(0)) {
        0 -> 60_000L
        1 -> 15_000L
        2 -> 30_000L
        else -> 60_000L
    }
}

class CloudHeartbeatService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var reporting: Job? = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Greenhouse cloud monitoring", NotificationManager.IMPORTANCE_LOW))
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.presence_online)
            .setContentTitle("Greenhouse monitoring active")
            .setContentText("Reporting server health securely")
            .setOngoing(true).setOnlyAlertOnce(true).build()
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (reporting?.isActive != true) reporting = scope.launch { reportLoop() }
        return START_STICKY
    }

    private suspend fun reportLoop() {
        var failures = 0
        while (scope.isActive) {
            val secure = SecureSettingsStore(this)
            val session = secure.cloudSession()
            if (session == null || session.accessTokenExpiresAtEpochMs <= System.currentTimeMillis() + 30_000L) {
                stopSelf(); return
            }
            val preferences = AppPreferencesStore(this).load()
            val gateway = AuthenticatedCloudGateway(CloudApiConfig(session.apiBaseUrl, session.deviceId), CloudTokenProvider { session.accessToken })
            val health = HeartbeatHealthStore(this).load()
            val result = runCatching {
                HeartbeatReporter(gateway).report(MonitoringHealth(session.deviceId, preferences.activeGreenhouse.id, health.sensorConnectionHealthy, health.criticalTemperature))
            }
            failures = if (result.isSuccess) 0 else failures + 1
            delay(HeartbeatRetryPolicy.delayMs(failures))
        }
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL = "greenhouse_cloud_monitoring"
        private const val NOTIFICATION_ID = 4172
        fun sync(context: Context, enabled: Boolean) {
            val intent = Intent(context, CloudHeartbeatService::class.java)
            if (enabled) context.startForegroundService(intent) else context.stopService(intent)
        }
    }
}
