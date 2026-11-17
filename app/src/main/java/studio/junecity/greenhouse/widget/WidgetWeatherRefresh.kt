package studio.junecity.greenhouse.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import androidx.work.*
import kotlinx.coroutines.CancellationException
import studio.junecity.greenhouse.data.LiveWeatherProvider
import studio.junecity.greenhouse.settings.*

/** Keep the outside tile current even if the forecast screen and weather alerts are never opened. */
class WidgetWeatherRefresh(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext
        val owner = SecureSettingsStore(app).cloudEnrollment() ?: return Result.success()
        if (inputData.getString("scope") != owner.widgetScope()) return Result.success()
        val zip = inputData.getString("zip") ?: return Result.success()
        if (activeZip(app, owner) != zip || (runAttemptCount == 0 && !ClimateCardRepository(app).weatherDue(zip))) return Result.success()
        return try {
            val reading = LiveWeatherProvider().current(zip)
            if (activeZip(app, owner) == zip) ClimateCardRepository.weatherResult(app, zip, reading)
            Result.success()
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) {
            if (activeZip(app, owner) == zip) ClimateCardRepository.weatherResult(app, zip, null)
            if (runAttemptCount < 2) Result.retry() else Result.failure()
        }
    }

    companion object {
        private fun activeZip(app: Context, owner: CloudEnrollment): String? {
            if (SecureSettingsStore(app).cloudEnrollment() != owner || !ClimateWidget.viewerReady(app, owner)) return null
            val ids = AppWidgetManager.getInstance(app).getAppWidgetIds(ComponentName(app, ClimateWidget::class.java))
            if (ids.none { ClimateWidget.canDisplay(owner, ClimateWidget.settings(app, it)) }) return null
            return AppPreferencesStore(app, if (owner.isStandaloneClient()) "_standalone" else "").load().zipCode
                .takeIf { it.matches(Regex("[0-9]{5}")) }
        }
        internal fun enqueue(app: Context, owner: CloudEnrollment) {
            val zip = activeZip(app, owner) ?: return
            if (!ClimateCardRepository(app).weatherDue(zip)) return
            val scope = owner.widgetScope()
            val work = OneTimeWorkRequestBuilder<WidgetWeatherRefresh>()
                .setInputData(workDataOf("scope" to scope, "zip" to zip))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
            WorkManager.getInstance(app).enqueueUniqueWork("widget-weather:$scope:$zip", ExistingWorkPolicy.KEEP, work)
        }
    }
}
