package studio.junecity.greenhouse.cloud

import android.app.NotificationManager
import android.content.Context
import android.os.SystemClock
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import studio.junecity.greenhouse.settings.CloudEnrollment
import studio.junecity.greenhouse.settings.SecureSettingsStore
import java.util.concurrent.TimeUnit

/** Serialize the foreground monitor and Android's recovery job. Read credentials at execution time. */
internal object CloudAlertDelivery {
    private val mutex = Mutex()
    private var lastScope: Pair<String, String>? = null
    private var lastSuccessMs = 0L
    suspend fun poll(
        context: Context, enrollment: CloudEnrollment, minimumIntervalMs: Long,
        fetch: suspend () -> AlertInbox, acknowledge: suspend (String) -> Unit,
        readTemperatures: suspend (AlertInbox?) -> ViewerReadingCheck = { ViewerReadingCheck(emptyList(), true) }
    ): Boolean = mutex.withLock {
        val security = SecureSettingsStore(context)
        if (security.cloudEnrollment() != enrollment) return@withLock true
        val scope = enrollment.tenantId to enrollment.deviceId
        if (lastScope == scope && SystemClock.elapsedRealtime() - lastSuccessMs in 0 until minimumIntervalMs &&
            ClientTemperatureAlarms.pendingAcknowledgements(context, enrollment).isEmpty()) return@withLock true
        val inbox = try {
            fetch()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: Exception) {
            if (failure.invalidViewerAuthorization()) {
                ClientTemperatureAlarms.forgetIfCurrent(context, enrollment)
                return@withLock true
            }
            null
        }
        currentCoroutineContext().ensureActive()
        if (security.cloudEnrollment() != enrollment) return@withLock true

        val viewer = enrollment.role == CloudDeviceRole.ViewerClient
        if (inbox != null) {
            val notifications = context.getSystemService(NotificationManager::class.java)
            clearRecoveredCloudAlerts(notifications, inbox)
            // A slow or unavailable snapshot must never hold up a verified alert.
            if (viewer) ClientTemperatureAlarms.deliver(context, enrollment, inbox)
        }
        for (alert in inbox?.alerts.orEmpty()) {
            if (!viewer || alert.type != MonitoringAlertType.CriticalTemperature) postCloudAlert(context, alert, enrollment.role)
        }
        val readings = if (viewer) try {
            readTemperatures(inbox)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: Exception) {
            if (failure.invalidViewerAuthorization()) {
                ClientTemperatureAlarms.forgetIfCurrent(context, enrollment)
                return@withLock true
            }
            ViewerReadingCheck(emptyList(), false)
        } else ViewerReadingCheck(emptyList(), true)
        currentCoroutineContext().ensureActive()
        if (security.cloudEnrollment() != enrollment) return@withLock true
        if (viewer && (inbox == null || readings.readings.isNotEmpty())) {
            ClientTemperatureAlarms.deliver(context, enrollment, inbox, readings.readings)
        }
        val success = inbox != null && readings.complete
        if (success) {
            lastScope = scope
            lastSuccessMs = SystemClock.elapsedRealtime()
        }
        for (id in ClientTemperatureAlarms.pendingAcknowledgements(context, enrollment)) {
            currentCoroutineContext().ensureActive()
            if (security.cloudEnrollment() != enrollment) return@withLock true
            try {
                acknowledge(id)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                val retired = inbox?.complete == true && inbox.criticalIncidentsVerified && inbox.alerts.none { it.id == id }
                if (!retired || failure !is CloudApiException || failure.statusCode !in setOf(404, 409)) return@withLock false
            }
            ClientTemperatureAlarms.acknowledged(context, enrollment, id)
        }
        success
    }
}

/** Safety net when Android reclaims the client process or rejects a background foreground-service start. */
class CloudAlertWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val context = applicationContext
        val enrollment = SecureSettingsStore(context).cloudEnrollment() ?: return@withContext Result.success()
        if (enrollment.role != CloudDeviceRole.ViewerClient) return@withContext Result.success()
        CloudAlertService.prepareAlertChannels(context)
        CriticalAlarm.recover(context)
        ClientTemperatureAlarms.deliver(context, enrollment)
        val config = CloudRuntime.configurationOrNull() ?: return@withContext Result.success()
        val gateway by lazy { gatewayForEnrollment(context, config, enrollment) }
        val success = CloudAlertDelivery.poll(context, enrollment, minimumIntervalMs = 60_000L,
            fetch = { gateway.pendingAlerts(true) }, acknowledge = { gateway.acknowledgeAlert(it) },
            readTemperatures = { gateway.viewerReadingCheck(context, enrollment, it) })
        if (ClientTemperatureAlarms.active(context, enrollment).isNotEmpty()) CloudAlertService.restoreForAlarm(context)
        if (success) Result.success() else Result.retry()
    }

    companion object {
        internal const val PERIODIC = "greenhouse_client_alert_recovery"
        internal const val IMMEDIATE = "greenhouse_client_alert_check"
        private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        internal fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<CloudAlertWorker>(15, TimeUnit.MINUTES)
                .setInitialDelay(15, TimeUnit.MINUTES).setConstraints(network).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        internal fun checkSoon(context: Context) {
            val request = OneTimeWorkRequestBuilder<CloudAlertWorker>().setConstraints(network).build()
            WorkManager.getInstance(context).enqueueUniqueWork(IMMEDIATE, ExistingWorkPolicy.KEEP, request)
        }

        internal fun cancel(context: Context) {
            val manager = WorkManager.getInstance(context)
            manager.cancelUniqueWork(PERIODIC)
            manager.cancelUniqueWork(IMMEDIATE)
        }
    }
}
