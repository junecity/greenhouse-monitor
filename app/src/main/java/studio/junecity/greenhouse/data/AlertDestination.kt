package studio.junecity.greenhouse.data

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import studio.junecity.greenhouse.MainActivity
import studio.junecity.greenhouse.settings.SecureSettingsStore

internal data class AlertDestination(val greenhouse: String, val sensor: String?, val device: String?, val cloud: Boolean, val time: Long) {
    companion object {
        const val ACTION = "studio.junecity.greenhouse.OPEN_ALERT"
        fun from(intent: Intent?): AlertDestination? {
            if (intent?.action != ACTION) return null
            val greenhouse = intent.getStringExtra("greenhouse") ?: return null
            return AlertDestination(greenhouse, intent.getStringExtra("sensor"), intent.getStringExtra("device"),
                intent.getBooleanExtra("cloud", false), intent.getLongExtra("time", System.currentTimeMillis()))
        }

        fun pending(context: Context, id: Int, greenhouse: String, sensor: String?, cloud: Boolean, time: Long): PendingIntent {
            val intent = Intent(context, MainActivity::class.java).setAction(ACTION)
                .setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra("greenhouse", greenhouse).putExtra("sensor", sensor).putExtra("time", time)
                .putExtra("device", SecureSettingsStore(context).cloudEnrollment()?.deviceId).putExtra("cloud", cloud)
            return PendingIntent.getActivity(context, id, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
    }
}
