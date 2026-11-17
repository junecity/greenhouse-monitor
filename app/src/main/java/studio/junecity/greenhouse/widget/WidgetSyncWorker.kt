package studio.junecity.greenhouse.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import androidx.work.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import studio.junecity.greenhouse.cloud.*
import studio.junecity.greenhouse.data.viewerHistorySuffix
import studio.junecity.greenhouse.model.CameraMode
import studio.junecity.greenhouse.settings.*

private val viewerSync = Mutex()

/** The dashboard and launcher refreshes serialize their snapshot reads and writes. */
internal suspend fun syncViewerReadings(context: Context, owner: CloudEnrollment, skipRecent: Boolean = false,
                                       fetch: suspend () -> JSONObject?): JSONObject? = viewerSync.withLock {
    withContext(Dispatchers.IO) {
        val secure = SecureSettingsStore(context)
        if (secure.cloudEnrollment() != owner || owner.greenhouseId.isBlank()) return@withContext null
        val sync = context.getSharedPreferences("widget_sync", 0)
        if (skipRecent && sync.getString("scope", "") == owner.widgetScope() &&
            System.currentTimeMillis() - sync.getLong("updatedAt", 0) in 0L..60_000L) return@withContext null
        try {
            val snapshot = fetch()
            if (secure.cloudEnrollment() != owner) return@withContext null
            if (snapshot == null) {
                // A server that has never supplied a snapshot is still setting up. When a
                // previously available source disappears, mark its retained readings stale.
                ClimateCardRepository.feedResult(context, owner, failed = ClimateWidget.viewerReady(context, owner))
                return@withContext null
            }
            GreenhouseBackupStore(context, viewerHistorySuffix(owner)).restore(snapshot, CameraMode.ViewerClient)
            if (secure.cloudEnrollment() != owner) return@withContext null
            ClimateCardRepository.feedResult(context, owner, failed = false)
            ClimateWidget.viewerSynced(context, owner)
            snapshot
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ClimateCardRepository.feedResult(context, owner, failed = true)
            throw e
        }
    }
}

class WidgetSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val context = applicationContext
        val owner = SecureSettingsStore(context).cloudEnrollment() ?: return Result.success()
        if (owner.role != CloudDeviceRole.ViewerClient || owner.isStandaloneClient() || owner.greenhouseId.isBlank()) return Result.success()
        val ids = AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, ClimateWidget::class.java))
        if (ids.none { owner.ownsWidget(ClimateWidget.settings(context, it).getString("scope", null)) }) return Result.success()
        val config = CloudRuntime.configurationOrNull() ?: return Result.success()
        return try {
            syncViewerReadings(context, owner, skipRecent = true) {
                gatewayForEnrollment(context, config, owner).greenhouseBackup(owner.greenhouseId)
            }
            Result.success()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            // Retain the last timestamp and retry at the next launcher refresh.
            Result.failure()
        }
    }

    companion object {
        fun enqueue(context: Context) {
            val work = OneTimeWorkRequestBuilder<WidgetSyncWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
            WorkManager.getInstance(context).enqueueUniqueWork("climate_widget_sync", ExistingWorkPolicy.KEEP, work)
        }
    }
}
