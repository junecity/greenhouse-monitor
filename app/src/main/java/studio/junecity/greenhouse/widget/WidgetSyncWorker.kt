package studio.junecity.greenhouse.widget

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
private const val SCOPE = "scope"
private const val UPLOAD = "upload"

private fun alreadySynced(context: Context, owner: CloudEnrollment, upload: String?, intervalMs: Long): Boolean {
    val saved = context.getSharedPreferences("widget_sync", Context.MODE_PRIVATE)
    if (saved.getString(SCOPE, null) != owner.widgetScope() || ClimateCardRepository.feedFailed(context, owner)) return false
    return if (upload != null) saved.getString(UPLOAD, null) == upload
        else System.currentTimeMillis() - saved.getLong("updatedAt", 0) in 0 until intervalMs
}

/** The dashboard and launcher refreshes serialize their snapshot reads and writes. */
internal suspend fun syncViewerReadings(context: Context, owner: CloudEnrollment, skipRecent: Boolean = false,
                                       upload: String? = null, fetch: suspend () -> JSONObject?): JSONObject? = viewerSync.withLock {
    withContext(Dispatchers.IO) {
        val secure = SecureSettingsStore(context)
        if (secure.cloudEnrollment() != owner || owner.role != CloudDeviceRole.ViewerClient || owner.isStandaloneClient()) return@withContext null
        if (skipRecent && alreadySynced(context, owner, upload, 60_000L)) return@withContext null
        try {
            val snapshot = fetch()
            secure.withCurrentEnrollment(owner) {
                if (snapshot == null) {
                    ClimateCardRepository.feedResult(context, owner, failed = ClimateWidget.viewerReady(context, owner))
                    return@withCurrentEnrollment null
                }
                // Partial backups can be used during a manual restore. They cannot authenticate
                // the shared inventory or floor plan left behind by an earlier viewer enrollment.
                require(snapshot.optJSONObject("preferences") != null && snapshot.optJSONArray("sensors") != null) {
                    "Server snapshot is missing the greenhouse or sensor inventory"
                }
                GreenhouseBackupStore(context, viewerHistorySuffix(owner)).restore(snapshot, CameraMode.ViewerClient)
                ClimateCardRepository.feedResult(context, owner, failed = false)
                ClimateWidget.viewerSynced(context, owner, upload)
                snapshot
            }
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
        val config = CloudRuntime.configurationOrNull() ?: return Result.success()
        return refresh(applicationContext, inputData.getString(SCOPE), inputData.getString(UPLOAD)) { owner ->
            gatewayForEnrollment(applicationContext, config, owner).greenhouseBackup(owner.greenhouseId)
        }
    }

    companion object {
        internal suspend fun refresh(context: Context, expectedScope: String?, upload: String?,
                                     fetch: suspend (CloudEnrollment) -> JSONObject?): Result {
            val owner = SecureSettingsStore(context).cloudEnrollment() ?: return Result.success()
            if (owner.widgetScope() != expectedScope || owner.role != CloudDeviceRole.ViewerClient ||
                owner.isStandaloneClient() || !ClimateWidget.hasReadingDisplay(context, owner)) return Result.success()
            return try {
                syncViewerReadings(context, owner, skipRecent = true, upload = upload) { fetch(owner) }
                Result.success()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (e: CloudApiException) {
                if (e.statusCode in 400..499 && e.statusCode !in setOf(401, 408, 429)) Result.failure() else Result.retry()
            } catch (_: Exception) {
                Result.retry()
            }
        }

        fun enqueue(context: Context) {
            val owner = SecureSettingsStore(context).cloudEnrollment() ?: return
            WidgetWeatherRefresh.enqueue(context, owner)
            if (owner.role != CloudDeviceRole.ViewerClient || owner.isStandaloneClient()) {
                WidgetCollectorRefresh.enqueue(context)
                return
            }
            if (ClimateWidget.hasReadingDisplay(context, owner)) request(context, owner, null)
        }

        /** Reuse the alarm monitor's authenticated device list. Widget downloads run outside the alarm poll. */
        internal fun serverUploads(context: Context, owner: CloudEnrollment, devices: List<AccountDevice>) {
            if (owner.role != CloudDeviceRole.ViewerClient || owner.isStandaloneClient() || !ClimateWidget.hasReadingDisplay(context, owner)) return
            val server = devices.singleOrNull {
                val device = it.device
                device.tenantId == owner.tenantId && device.greenhouseId == owner.greenhouseId &&
                    device.role == CloudDeviceRole.GreenhouseServer && device.enabled && !device.communicationSuspended
            } ?: return
            val upload = server.lastDataUploadEpochMs?.let { "${server.device.id}|${server.enrollmentEpochMs}|$it" }
            val interval = if (upload == null) AppPreferencesStore(context).load().activeRefreshMinutes * 60_000L else 0L
            if (!alreadySynced(context, owner, upload, interval)) request(context, owner, upload)
        }

        private fun request(context: Context, owner: CloudEnrollment, upload: String?) {
            val work = OneTimeWorkRequestBuilder<WidgetSyncWorker>()
                .setInputData(workDataOf(SCOPE to owner.widgetScope(), UPLOAD to upload))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
            WorkManager.getInstance(context).enqueueUniqueWork("climate_widget_sync${viewerHistorySuffix(owner)}", ExistingWorkPolicy.KEEP, work)
        }
    }
}
