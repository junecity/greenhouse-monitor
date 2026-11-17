package studio.junecity.greenhouse.cloud

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import studio.junecity.greenhouse.MainActivity
import studio.junecity.greenhouse.R
import studio.junecity.greenhouse.settings.CloudEnrollment
import studio.junecity.greenhouse.settings.GreenhousePlanCache
import studio.junecity.greenhouse.settings.SecureSettingsStore
import studio.junecity.greenhouse.widget.ClimateWidget

class GreenhousePlanWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val context = applicationContext
        val secure = SecureSettingsStore(context)
        val owner = secure.cloudEnrollment() ?: return Result.success()
        val config = CloudRuntime.configurationOrNull() ?: return Result.success()
        val outcome = try {
            GreenhousePlanCache.refresh(context, owner, gatewayForEnrollment(context, config, owner))
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if (failure is CloudApiException && failure.statusCode in setOf(403, 404)) return Result.success()
            Result.retry()
        }
        secure.withCurrentEnrollment(owner) {
            ClimateWidget.changed(context)
            if (owner.role == CloudDeviceRole.ViewerClient) showScheduledPauses(context, owner)
        }
        return outcome
    }

    companion object {
        private const val NOTICES = "greenhouse-plan-notices"

        internal fun clearNotices(context: Context) {
            val notices = context.getSharedPreferences(NOTICES, Context.MODE_PRIVATE)
            val manager = context.getSystemService(NotificationManager::class.java)
            notices.all.keys.forEach { manager.cancel(it, 1) }
            notices.edit { clear() }
        }

        internal fun showScheduledPauses(context: Context, owner: CloudEnrollment) {
            val notices = context.getSharedPreferences(NOTICES, Context.MODE_PRIVATE)
            val manager = context.getSystemService(NotificationManager::class.java)
            val channel = "greenhouse-plan-status"
            manager.createNotificationChannel(NotificationChannel(channel, "Greenhouse monitoring schedule", NotificationManager.IMPORTANCE_DEFAULT))
            val now = System.currentTimeMillis()
            val due = GreenhousePlanCache(context, owner).scheduledPauses()
                .filter { it.pauseAtEpochMs - now in 1L..TimeUnit.DAYS.toMillis(1) }
                .associateBy { "plan/${owner.tenantId}/${owner.deviceId}/${it.id}" }
            for (tag in notices.all.keys - due.keys) {
                manager.cancel(tag, 1)
                notices.edit { remove(tag) }
            }
            if (!manager.areNotificationsEnabled() || manager.getNotificationChannel(channel).importance == NotificationManager.IMPORTANCE_NONE ||
                (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)) return
            for ((tag, house) in due) {
                if (notices.getLong(tag, 0) == house.pauseAtEpochMs) continue
                val date = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(house.pauseAtEpochMs))
                val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                manager.notify(tag, 1, NotificationCompat.Builder(context, channel)
                    .setSmallIcon(R.drawable.ic_notification_greenhouse).setContentTitle(house.name)
                    .setContentText("Monitoring and alerts are scheduled to pause on $date.")
                    .setContentIntent(open).setAutoCancel(true).build())
                notices.edit { putLong(tag, house.pauseAtEpochMs) }
            }
        }

        fun schedule(context: Context) {
            val work = WorkManager.getInstance(context)
            work.enqueueUniquePeriodicWork("greenhouse-plan-refresh", ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<GreenhousePlanWorker>(1, TimeUnit.HOURS).build())
            work.enqueueUniqueWork("greenhouse-plan-on-open", ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<GreenhousePlanWorker>().build())
        }
    }
}
