package studio.junecity.greenhouse.data

import android.content.Context
import androidx.work.*
import studio.junecity.greenhouse.settings.SecureSettingsStore
import studio.junecity.greenhouse.settings.ZoneAssignmentStore
import studio.junecity.greenhouse.sensors.GoveeSensorProvider
import studio.junecity.greenhouse.sensors.SensorProviderCredential
import java.util.concurrent.TimeUnit

class SensorSyncWorker(context:Context,params:WorkerParameters):CoroutineWorker(context,params){
    override suspend fun doWork():Result{
        val key=SecureSettingsStore(applicationContext).goveeApiKey() ?: return Result.success()
        return runCatching{
            val provider=GoveeSensorProvider();val credential=SensorProviderCredential.ApiKey(key);val history=HistoryStore(applicationContext);val notifier=AlertNotifier(applicationContext);val zones=ZoneAssignmentStore(applicationContext)
            provider.discover(credential).map{device->provider.read(credential,device).toLegacySensor(zone=zones.zone(device.deviceId))}.filter{it.online}.forEach{history.insert(it);notifier.evaluate(it)}
            Result.success()
        }.getOrElse{Result.retry()}
    }
    companion object{
        fun schedule(context:Context){
            val constraints=Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            val request=PeriodicWorkRequestBuilder<SensorSyncWorker>(15,TimeUnit.MINUTES).setConstraints(constraints).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("govee_sensor_sync",ExistingPeriodicWorkPolicy.UPDATE,request)
        }
    }
}
