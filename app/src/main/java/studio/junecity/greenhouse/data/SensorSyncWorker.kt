package studio.junecity.greenhouse.data

import android.content.Context
import androidx.work.*
import studio.junecity.greenhouse.settings.isStandaloneClient
import studio.junecity.greenhouse.settings.SecureSettingsStore
import studio.junecity.greenhouse.settings.ZoneAssignmentStore
import studio.junecity.greenhouse.settings.AppPreferencesStore
import studio.junecity.greenhouse.settings.CurrentSensorStore
import studio.junecity.greenhouse.sensors.withCurrentAssignments
import studio.junecity.greenhouse.sensors.collectReadings
import studio.junecity.greenhouse.sensors.GoveeSensorProvider
import studio.junecity.greenhouse.sensors.SensorProviderCredential
import java.util.concurrent.TimeUnit

class SensorSyncWorker(context:Context,params:WorkerParameters):CoroutineWorker(context,params){
    override suspend fun doWork():Result{
        val secure=SecureSettingsStore(applicationContext)
        val owner = secure.cloudEnrollment()
        val suffix = if (owner.isStandaloneClient()) "_standalone" else ""
        val key=secure.sensorKey(owner) ?: return Result.success()
        return runCatching{
            val provider=GoveeSensorProvider();val credential=SensorProviderCredential.ApiKey(key);val history=HistoryStore(applicationContext,suffix);val notifier=AlertNotifier(applicationContext,suffix);val zones=ZoneAssignmentStore(applicationContext,suffix);val prefs=AppPreferencesStore(applicationContext,suffix)
            val store = CurrentSensorStore(applicationContext,suffix)
            val collected = provider.collectReadings(credential, prefs.load(), store.load(), zones::zone)
            if (secure.cloudEnrollment() != owner || secure.sensorKey(owner) != key) return Result.success()
            val sensors = collected.withCurrentAssignments(prefs.load(), zones::zone)
            store.save(sensors)
            sensors.filter{it.online}.forEach{history.insert(it);notifier.evaluate(it)}
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
