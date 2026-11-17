package studio.junecity.greenhouse.widget

import android.content.Context
import androidx.work.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import studio.junecity.greenhouse.cloud.CloudDeviceRole
import studio.junecity.greenhouse.settings.*
import studio.junecity.greenhouse.sensors.CurrentGoveeReadings
import studio.junecity.greenhouse.sensors.sampleWidgetRadios
import studio.junecity.greenhouse.sensors.collectShelly
import studio.junecity.greenhouse.sensors.isLocalRadio

/** Data work is separate from rendering: resizing a pin or redrawing a graph never samples a sensor. */
class WidgetCollectorRefresh(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext
        val owner = SecureSettingsStore(app).cloudEnrollment() ?: return Result.success()
        if (owner.widgetScope() != inputData.getString("scope") || !eligible(app, owner)) return Result.success()
        return try {
            val failure = coroutineScope {
                launch { sampleWidgetRadios(app, owner) }
                // An unavailable cloud must not cancel an offline Bluetooth observation.
                runCatching { CurrentGoveeReadings.refresh(app, owner) }.exceptionOrNull()
                    ?.also { if (it is CancellationException) throw it }
            }
            if (failure == null) Result.success() else if (runAttemptCount < 2) Result.retry() else Result.failure()
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { if (runAttemptCount < 2) Result.retry() else Result.failure() }
    }

    companion object {
        private fun eligible(app: Context, owner: CloudEnrollment): Boolean {
            if (!owner.isStandaloneClient() && owner.role != CloudDeviceRole.GreenhouseServer) return false
            return ClimateWidget.hasReadingDisplay(app, owner)
        }

        /** The existing enrolled-phone monitoring service stays active when the app leaves the screen. */
        internal suspend fun monitorStandalone(app: Context, owner: CloudEnrollment) = withContext(Dispatchers.IO) {
            val secure = SecureSettingsStore(app)
            var radio: Job? = null
            try {
                while (isActive && secure.cloudEnrollment() == owner) {
                    val collector = GreenhousePlanCache.localCollector(app, owner)
                    if (collector != null) {
                        val hasRadio = CurrentSensorStore(app, "_standalone").load().any {
                            it.isLocalRadio() || it.sku.equals("H5179", true)
                        }
                        if (hasRadio && radio?.isActive != true) {
                            radio = launch { collectShelly(app, collector, "_standalone", authenticatedAs = owner) }
                        } else if (!hasRadio) {
                            radio?.cancel()
                            radio = null
                        }
                        try { CurrentGoveeReadings.refresh(app, collector, authenticatedAs = owner) }
                        catch (e: CancellationException) { throw e }
                        catch (_: Exception) { /* Keep the latest local reading while its provider is offline. */ }
                    } else {
                        radio?.cancel()
                        radio = null
                    }
                    delay(60_000L)
                }
            } finally { radio?.cancel() }
        }

        internal fun enqueue(app: Context) {
            val owner = SecureSettingsStore(app).cloudEnrollment() ?: return
            if (!eligible(app, owner)) return
            val scope = owner.widgetScope()
            val data = workDataOf("scope" to scope)
            val manager = WorkManager.getInstance(app)
            manager.enqueueUniqueWork("widget-collect:$scope", ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<WidgetCollectorRefresh>().setInputData(data).build())
        }
    }
}
