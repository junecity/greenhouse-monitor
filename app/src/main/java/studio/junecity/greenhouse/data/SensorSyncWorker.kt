package studio.junecity.greenhouse.data

import android.content.Context
import androidx.work.*
import kotlinx.coroutines.CancellationException
import studio.junecity.greenhouse.settings.SecureSettingsStore
import studio.junecity.greenhouse.sensors.CurrentGoveeReadings
import java.util.concurrent.TimeUnit

class SensorSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val owner = SecureSettingsStore(applicationContext).cloudEnrollment() ?: return Result.success()
        return try {
            CurrentGoveeReadings.refresh(applicationContext, owner)
            Result.success()
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { Result.retry() }
    }
    companion object{
        fun schedule(context:Context){
            val constraints=Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            val request=PeriodicWorkRequestBuilder<SensorSyncWorker>(15,TimeUnit.MINUTES).setConstraints(constraints).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("govee_sensor_sync",ExistingPeriodicWorkPolicy.UPDATE,request)
        }
    }
}
