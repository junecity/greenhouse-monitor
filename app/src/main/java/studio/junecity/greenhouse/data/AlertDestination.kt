package studio.junecity.greenhouse.data

import android.app.PendingIntent
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import studio.junecity.greenhouse.MainActivity
import studio.junecity.greenhouse.settings.SecureSettingsStore

internal data class AlertDestination(val greenhouse: String, val sensor: String?, val device: String?, val cloud: Boolean, val time: Long, val notificationId: Int?, val notificationTag: String? = null) {
    /** Read the reason from the app's own posted alert after enrollment and house have been checked. */
    fun postedReason(context: Context): String? {
        if (!cloud || notificationId == null) return null
        val notice = context.getSystemService(NotificationManager::class.java).activeNotifications
            .firstOrNull { it.tag == notificationTag && it.id == notificationId }?.notification ?: return null
        if (notice.extras.getString(HOUSE_KEY) != greenhouse || notice.`when` != time) return null
        return notice.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
    }

    companion object {
        const val ACTION = "studio.junecity.greenhouse.OPEN_ALERT"
        const val HOUSE_KEY = "studio.junecity.greenhouse.alertHouse"
        fun from(intent: Intent?): AlertDestination? {
            if (intent?.action != ACTION) return null
            val greenhouse = intent.getStringExtra("greenhouse") ?: return null
            return AlertDestination(greenhouse, intent.getStringExtra("sensor"), intent.getStringExtra("device"),
                intent.getBooleanExtra("cloud", false), intent.getLongExtra("time", System.currentTimeMillis()),
                if (intent.hasExtra("notificationId")) intent.getIntExtra("notificationId", 0) else null, intent.getStringExtra("notificationTag"))
        }

        fun pending(context: Context, id: Int, greenhouse: String, sensor: String?, cloud: Boolean, time: Long, tag: String? = null): PendingIntent {
            val intent = Intent(context, MainActivity::class.java).setAction(ACTION)
                .setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra("greenhouse", greenhouse).putExtra("sensor", sensor).putExtra("time", time).putExtra("notificationId", id)
                .putExtra("device", SecureSettingsStore(context).cloudEnrollment()?.deviceId).putExtra("cloud", cloud)
            if (tag != null) {
                intent.data = android.net.Uri.Builder().scheme("greenhouse").authority("alarm").appendPath(tag).build()
                intent.putExtra("notificationTag", tag)
            }
            return PendingIntent.getActivity(context, id, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
    }
}
