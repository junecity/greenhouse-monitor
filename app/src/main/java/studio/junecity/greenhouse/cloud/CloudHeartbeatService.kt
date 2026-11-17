package studio.junecity.greenhouse.cloud

import studio.junecity.greenhouse.R
import studio.junecity.greenhouse.model.sensorHouse
import studio.junecity.greenhouse.model.monitorsSensor
import studio.junecity.greenhouse.model.forAlertsIn
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Job
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.launch
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

object HeartbeatRetryPolicy {
    fun delayMs(consecutiveFailures: Int): Long = when (consecutiveFailures.coerceAtLeast(0)) {
        0 -> 60_000L
        1 -> 15_000L
        2 -> 30_000L
        else -> 60_000L
    }
}

/** The minute cadence starts when sampling starts. Only a finished upload can be advertised to viewers. */
internal data class PublishedSnapshot(val startedElapsedMs: Long, val uploadedAtEpochMs: Long) {
    fun nextUploadDue(elapsedMs: Long, intervalMinutes: Int) =
        elapsedMs - startedElapsedMs >= intervalMinutes * 60_000L
}

/** A successful backend heartbeat from this process is also evidence of recovery on older backends. */
internal object ServerHeartbeatReceipt {
    private data class Receipt(val deviceId: String, val elapsedMs: Long)
    @Volatile private var latest: Receipt? = null

    fun record(deviceId: String) { latest = Receipt(deviceId, android.os.SystemClock.elapsedRealtime()) }

    fun recent(deviceId: String): Boolean {
        val receipt = latest ?: return false
        return receipt.deviceId == deviceId && android.os.SystemClock.elapsedRealtime() - receipt.elapsedMs < 120_000L
    }
}

class CloudHeartbeatService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val reporting = EnrollmentBoundWorker(scope)

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Greenhouse cloud monitoring", NotificationManager.IMPORTANCE_LOW))
        val openApp = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, NOTIFICATION_ID, it, PendingIntent.FLAG_IMMUTABLE)
        }
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification_greenhouse)
            .setContentTitle("Greenhouse monitoring active")
            .setContentText("Reporting server health securely")
            .setContentIntent(openApp).setOngoing(true).setOnlyAlertOnce(true).build()
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        reporting.start(SecureSettingsStore(this@CloudHeartbeatService).cloudEnrollment()) { reportLoop() }
        return START_STICKY
    }

    private suspend fun reportLoop() = coroutineScope {
        val secure = SecureSettingsStore(this@CloudHeartbeatService)
        val enrollment = secure.cloudEnrollment()
        val configuration = runCatching { CloudRuntime.configurationOrNull() }.getOrNull()
        if (enrollment == null || enrollment.role != CloudDeviceRole.GreenhouseServer || configuration == null) {
            stopSelf(); return@coroutineScope
        }
        val gateway = runCatching { gatewayForEnrollment(this@CloudHeartbeatService, configuration, enrollment) }.getOrElse { stopSelf(); return@coroutineScope }
        launch { studio.junecity.greenhouse.sensors.collectShelly(this@CloudHeartbeatService, enrollment, "") }
        // Govee has no continuous BLE temperature feed. Keep collecting while the server
        // phone is on its launcher, independent of the camera, heartbeat and UI lifecycle.
        launch {
            while (isActive && secure.cloudEnrollment() == enrollment) {
                try { studio.junecity.greenhouse.sensors.CurrentGoveeReadings.refresh(this@CloudHeartbeatService, enrollment) }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (_: Exception) { /* Keep the original observation time; retry next interval. */ }
                delay(AppPreferencesStore(this@CloudHeartbeatService).load().activeRefreshMinutes.coerceIn(1, 15) * 60_000L)
            }
        }
        val appliedRules = AppliedAlertRules(this@CloudHeartbeatService, enrollment)
        var failures = 0
        val lastUpload = AtomicReference<PublishedSnapshot?>()
        var update: Job? = null
        var backup: Job? = null
        var publishedSensors: Map<String,String>? = null
        var publishedBreaches: Map<String, TemperatureBreach.Direction> = emptyMap()
        var publishedPlan: studio.junecity.greenhouse.model.GreenhouseConfig? = null
        var publishedRules: AlertRules? = null
        while (isActive) {
            var rulesChanged = false
            val result = runCatching {
                val context = this@CloudHeartbeatService
                val prefs = AppPreferencesStore(context).loadForMonitoring(enrollment)
                val house = prefs.activeGreenhouse.id
                val sensors = CurrentSensorStore(context).load().filter { prefs.sensorHouse(it) == house && prefs.monitorsSensor(it) }
                val applied = appliedRules.load()
                val rules = applied?.rules ?: AlertRules.from(prefs.forAlertsIn(house))
                val limits = rules.applyTo(prefs.notifications)
                val incident = TemperatureIncidentSensors(context, enrollment, house)
                val previousOffenders = incident.load()
                val health = assessTemperature(sensors, limits.lowTemperatureF, limits.highTemperatureF,
                    System.currentTimeMillis(), previousOffenders)
                // A lost response or process restart must not forget a reported breach before its recovery is delivered.
                incident.save(previousOffenders + health.outstandingSensorIds)
                val device = AndroidDeviceStatusProvider(context).current().copy(
                    appVersion = BuildConfig.VERSION_NAME, appVersionCode = BuildConfig.VERSION_CODE,
                    sourceDeviceId = enrollment.deviceId)
                val updateMode = ManagedServerUpdate.mode(context, enrollment)
                val reply = HeartbeatReporter(gateway).report(MonitoringHealth(
                    enrollment.deviceId, enrollment.greenhouseId, health.sensorConnectionHealthy, health.breach != null,
                    temperatureEvaluated = health.evaluated, temperatureBreach = health.breach,
                    remoteRecordingReady = CloudCameraServerService.isRecordingReady(enrollment),
                    temperatureObservations = health.observations,
                    alertRules = rules.takeIf { it.valid() },
                    appliedAlertRulesRevision = applied?.revision ?: 0,
                    batteryPercent=device.batteryPercent, charging=device.charging,
                    networkType=if (device.wifiConnected) "WIFI" else device.network.uppercase(),
                    wifiSignalStrength=device.signalDbm.takeIf { device.wifiConnected },
                    sensorCount = sensors.size, sensorProvider = listOfNotNull(
                        "GOVEE".takeIf { secure.goveeApiKey() != null },
                        "SHELLY".takeIf { sensors.any { s -> s.id.startsWith("shelly:") } },
                        "BLE ESS".takeIf { sensors.any { s -> s.id.startsWith("ess:") } }
                    ).joinToString(", ").ifBlank { null },
                    cameraStatus=if(packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_CAMERA_ANY))"AVAILABLE" else "UNAVAILABLE",
                    lastSensorSyncEpochMs=sensors.maxOfOrNull(GoveeSensor::updatedAtEpochMs),
                    managedUpdates=updateMode == "MANAGED", updateMode=updateMode,
                    updateStatus=ManagedServerUpdate.status(context, enrollment.deviceId),
                    updateStatusRequestId=ManagedServerUpdate.statusRequestId(context, enrollment.deviceId),
                    lastDataUploadEpochMs=lastUpload.get()?.uploadedAtEpochMs, appVersion=BuildConfig.VERSION_NAME
                ))
                if (secure.cloudEnrollment() != enrollment) return@coroutineScope
                if (reply.remoteRecordingPending) CloudCameraServerService.signalRecording(enrollment)
                rulesChanged = reply.alertRules?.let(appliedRules::install) == true
                val command = reply.update
                incident.save(health.outstandingSensorIds)
                ServerHeartbeatReceipt.record(enrollment.deviceId)
                val requestedUpdate = command?.versionCode
                val install = command?.authorizes(updateMode) == true
                ManagedServerUpdate.reconcileSession(context, enrollment, command.takeIf { install }, updateMode)
                if (command != null && install) {
                    if (update?.isActive != true && ManagedServerUpdate.due(context, command.key(enrollment.deviceId))) {
                        update = launch {
                            try {
                                ManagedServerUpdate.install(context, gateway, enrollment, command.versionCode, mode = updateMode, requestId = command.id)
                            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                                throw cancelled
                            } catch (error: Exception) {
                                ManagedServerUpdate.setStatus(context, "RETRY")
                                android.util.Log.w("GreenhouseUpdate", "Update deferred: ${error.javaClass.simpleName}")
                            }
                        }
                    }
                } else {
                    update?.cancel()
                    runCatching { ServerUpdateNotice.sync(context, enrollment.deviceId, requestedUpdate) }
                }
                // A slow backup must never block the next heartbeat or overlap another upload.
                if (backup?.isActive != true) {
                    backup?.join()
                    val available = sensors.associate { it.id to it.name }
                    val snapshotRules = reply.alertRules?.rules ?: rules
                    val start = SystemClock.elapsedRealtime()
                    // Older cloud deployments rely on these readings to alert individual client sensors.
                    // Publish crossings and recoveries even when the dashboard refresh interval is 15 minutes.
                    if (health.breachDirections != publishedBreaches || prefs.activeGreenhouse != publishedPlan ||
                        available != publishedSensors || snapshotRules != publishedRules ||
                        lastUpload.get()?.nextUploadDue(start, prefs.activeRefreshMinutes) != false) {
                        backup = launch {
                            runCatching { gateway.uploadGreenhouseBackup(enrollment.greenhouseId, GreenhouseBackupStore(context).snapshot(enrollment).put("serverHealth", device.toHealthJson())) }
                                .onSuccess {
                                    publishedPlan = prefs.activeGreenhouse
                                    publishedRules = snapshotRules
                                    publishedSensors = available
                                    publishedBreaches = health.breachDirections
                                    lastUpload.set(PublishedSnapshot(start, System.currentTimeMillis()))
                                }
                        }
                    }
                }
            }
            val heartbeatError = result.exceptionOrNull() as? CloudApiException
            if (heartbeatError?.statusCode in listOf(401, 403, 404)) {
                update?.cancel()
                ManagedServerUpdate.cancelSession(this@CloudHeartbeatService)
            }
            failures = if (result.isSuccess) 0 else failures + 1
            // Immediately re-evaluate real readings under the new thresholds and acknowledge that revision.
            if (!rulesChanged) delay(HeartbeatRetryPolicy.delayMs(failures))
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
