package studio.junecity.greenhouse.data

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import studio.junecity.greenhouse.cloud.CloudDeviceRole
import studio.junecity.greenhouse.sensors.CurrentGoveeReadings
import studio.junecity.greenhouse.sensors.sampleWidgetRadios
import studio.junecity.greenhouse.settings.GreenhousePlanCache
import studio.junecity.greenhouse.settings.SecureSettingsStore
import studio.junecity.greenhouse.settings.isStandaloneClient
import studio.junecity.greenhouse.widget.LockScreenClimate

class SensorSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val context = applicationContext
        val current = SecureSettingsStore(context).cloudEnrollment() ?: return Result.success()
        val source = if (current.role == CloudDeviceRole.ViewerClient)
            GreenhousePlanCache.localCollector(context, current) ?: return Result.success()
        else current
        return coroutineScope {
            if (source.isStandaloneClient() || LockScreenClimate.enabled(context, current)) {
                launch { sampleWidgetRadios(context, source, authenticatedAs = current) }
            }
            try {
                CurrentGoveeReadings.refresh(context, source, authenticatedAs = current)
                Result.success()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                Result.retry()
            }
        }
    }

    companion object {
        fun schedule(context: Context) {
            // Bluetooth can collect offline. The Wi-Fi provider independently retries an unavailable network.
            val request = PeriodicWorkRequestBuilder<SensorSyncWorker>(15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("govee_sensor_sync", ExistingPeriodicWorkPolicy.UPDATE, request)
        }
    }
}
