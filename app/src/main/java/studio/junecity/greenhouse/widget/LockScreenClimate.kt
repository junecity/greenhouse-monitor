package studio.junecity.greenhouse.widget

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.edit
import androidx.core.app.NotificationCompat
import studio.junecity.greenhouse.MainActivity
import studio.junecity.greenhouse.R
import studio.junecity.greenhouse.settings.CloudEnrollment
import studio.junecity.greenhouse.settings.SecureSettingsStore

/** One explicitly enabled, device-local display for the active greenhouse. */
internal object LockScreenClimate {
    const val CHANNEL_ID = "greenhouse_lock_climate"
    const val NOTIFICATION_ID = 9411
    private const val TAG = "greenhouse.lock.climate"
    private const val OWNER = "owner"

    private fun preferences(context: Context) = context.getSharedPreferences("lock_screen_climate", Context.MODE_PRIVATE)

    fun configured(context: Context): Boolean = preferences(context).contains(OWNER)

    fun enabled(context: Context, owner: CloudEnrollment?): Boolean {
        val saved = preferences(context).getString(OWNER, null) ?: return false
        return owner?.ownsWidget(saved) == true
    }

    fun channelBlocked(context: Context): Boolean =
        context.getSystemService(NotificationManager::class.java).getNotificationChannel(CHANNEL_ID)?.importance ==
            NotificationManager.IMPORTANCE_NONE

    private fun ensureChannel(manager: NotificationManager) {
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(CHANNEL_ID, "Lock screen climate", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Average greenhouse temperature and humidity"
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    fun setEnabled(context: Context, owner: CloudEnrollment, value: Boolean) {
        val applied = SecureSettingsStore(context).withCurrentEnrollment(owner) {
            if (value) {
                ensureChannel(context.getSystemService(NotificationManager::class.java))
                preferences(context).edit { putString(OWNER, owner.widgetScope()) }
            } else forget(context)
            true
        }
        if (applied == true) ClimateWidget.lockScreenChanged(context)
    }

    fun clear(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(TAG, NOTIFICATION_ID)
    }

    fun forget(context: Context) {
        preferences(context).edit { remove(OWNER) }
        clear(context)
    }

    /** Called on the climate update worker. Returns the next time a displayed reading needs aging. */
    fun refresh(context: Context): Long? {
        val secure = SecureSettingsStore(context)
        val owner = secure.cloudEnrollment()
        if (owner == null || !enabled(context, owner)) {
            clear(context)
            return null
        }
        val manager = context.getSystemService(NotificationManager::class.java)
        ensureChannel(manager)
        if (!manager.areNotificationsEnabled() || channelBlocked(context) ||
            (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)) {
            clear(context)
            return null
        }
        val state = ClimateCardRepository(context).load(owner, readingsOnly = true)
        val hasValues = state.measuredAt != null && (state.temperatureF != null || state.humidity != null)
        val title = if (hasValues) "${state.temperatureText} · ${state.humidityText} RH" else state.title
        val text = when {
            !hasValues -> state.statusMessage ?: "Waiting for readings"
            state.status == WidgetFeedStatus.Live -> "Greenhouse averages · ${state.updatedText}"
            else -> "Cached averages · ${state.updatedText}"
        }
        val launch = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val open = PendingIntent.getActivity(context, NOTIFICATION_ID, launch, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_greenhouse)
            .setContentTitle(title)
            .setContentText(text)
            .setSubText(state.title.takeIf { hasValues })
            .setContentIntent(open)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setWhen(state.measuredAt ?: 0L)
            .setShowWhen(hasValues)
            .build()
        return secure.withCurrentEnrollment(owner) {
            if (enabled(context, owner)) {
                manager.notify(TAG, NOTIFICATION_ID, notification)
                state.nextStatusAt
            } else null
        }
    }
}
