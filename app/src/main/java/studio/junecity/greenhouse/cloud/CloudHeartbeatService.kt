package studio.junecity.greenhouse.cloud

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
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
import studio.junecity.greenhouse.model.GoveeSensor
import studio.junecity.greenhouse.BuildConfig
import studio.junecity.greenhouse.data.AndroidDeviceStatusProvider
import studio.junecity.greenhouse.settings.AppPreferencesStore
import studio.junecity.greenhouse.settings.CurrentSensorStore
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
    private val reporting = EnrollmentBoundWorker(scope)

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Greenhouse cloud monitoring", NotificationManager.IMPORTANCE_LOW))
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.presence_online)
            .setContentTitle("Greenhouse monitoring active")
            .setContentText("Reporting server health securely")
            .setOngoing(true).setOnlyAlertOnce(true).build()
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        reporting.start(SecureSettingsStore(this).cloudEnrollment()) { reportLoop() }
        return START_STICKY
    }

    private suspend fun reportLoop() {
        val secure = SecureSettingsStore(this)
        val enrollment = secure.cloudEnrollment()
        val configuration = runCatching { CloudRuntime.configurationOrNull() }.getOrNull()
        if (enrollment == null || enrollment.role != CloudDeviceRole.GreenhouseServer || configuration == null) {
            stopSelf(); return
        }
        val gateway = runCatching { gatewayForEnrollment(this, configuration, enrollment) }.getOrElse { stopSelf(); return }
        var failures = 0
        var lastBackupAt = 0L
        var lastDataUploadAt = 0L
        var publishedSensorIds: Set<String>? = null
        var publishedHouse: String? = null
        while (scope.isActive) {
            val health = HeartbeatHealthStore(this).load()
            val sensors = CurrentSensorStore(this).load()
            val device = AndroidDeviceStatusProvider(this).current()
            val result = runCatching {
                HeartbeatReporter(gateway).report(MonitoringHealth(
                    enrollment.deviceId, enrollment.greenhouseId, health.sensorConnectionHealthy, health.criticalTemperature,
                    batteryPercent=device.batteryPercent, charging=device.charging,
                    networkType=if(device.wifiConnected)"WIFI" else "CELLULAR_OR_OTHER",
                    sensorCount=sensors.size, sensorProvider=if(secure.goveeApiKey()!=null)"GOVEE" else null,
                    cameraStatus=if(packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_CAMERA_ANY))"AVAILABLE" else "UNAVAILABLE",
                    lastSensorSyncEpochMs=sensors.maxOfOrNull(GoveeSensor::updatedAtEpochMs),
                    lastDataUploadEpochMs=lastDataUploadAt.takeIf{it>0}, appVersion=BuildConfig.VERSION_NAME
                ))
            }
            val now = System.currentTimeMillis()
            val preferences = AppPreferencesStore(this).load()
            val backupIntervalMs = preferences.activeRefreshMinutes * 60_000L
            val activeHouse = preferences.activeGreenhouseId
            val available = sensors.filter { it.greenhouseId == activeHouse && it.updatedAtEpochMs > 0 }.map { it.id }.toSet()
            if (result.isSuccess && (activeHouse != publishedHouse || available != publishedSensorIds || now - lastBackupAt >= backupIntervalMs)) runCatching {
                gateway.uploadGreenhouseBackup(enrollment.greenhouseId, GreenhouseBackupStore(this@CloudHeartbeatService).snapshot())
            }.onSuccess {
                publishedHouse = activeHouse
                publishedSensorIds = available
                lastBackupAt = now
                lastDataUploadAt = now
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
            runCatching {
                if (enabled) context.startForegroundService(intent) else context.stopService(intent)
            }
        }
    }
}
