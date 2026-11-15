package studio.junecity.greenhouse.cloud

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.net.Uri
import android.provider.Settings
import androidx.core.app.NotificationCompat
import studio.junecity.greenhouse.R

internal enum class TemperatureSound(val channelId: String, val label: String, val rawName: String? = null) {
    Existing("greenhouse_cloud_critical", "Existing critical alert sound"),
    Pulse("temperature_pulse", "Double pulse", "temperature_pulse"),
    Sweep("temperature_sweep", "Rising siren", "temperature_sweep"),
    Custom("temperature_custom", "My phone sound");

    fun channel(context: Context): NotificationChannel {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.getNotificationChannel(channelId)?.let { return it }
        val name = if (this == Existing) "Critical greenhouse alerts" else "Critical temperature · $label"
        val channel = NotificationChannel(channelId, name, NotificationManager.IMPORTANCE_HIGH).apply {
            enableVibration(true)
            if (this@TemperatureSound == Existing) description = "Critical temperature and server-offline safety alerts"
            rawName?.let {
                val uri = Uri.parse("android.resource://${context.packageName}/raw/$it")
                setSound(uri, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            }
        }
        manager.createNotificationChannel(channel)
        return manager.getNotificationChannel(channelId)
    }

    fun select(context: Context) {
        channel(context)
        context.getSharedPreferences("client_alert_audio", Context.MODE_PRIVATE).edit()
            .putString("critical_temperature", channelId).apply()
    }

    fun soundSettings(context: Context): Intent = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        .putExtra(Settings.EXTRA_CHANNEL_ID, channel(context).id)

    fun test(context: Context): String {
        val manager = context.getSystemService(NotificationManager::class.java)
        val current = channel(context)
        if (!manager.areNotificationsEnabled()) return "Allow Greenhouse Studio notifications in Android settings."
        if (current.importance < NotificationManager.IMPORTANCE_DEFAULT || current.sound == null)
            return "This alert is silent. Open Sound settings to enable sound."
        val boosted = CriticalAlarm.play(context, current)
        val notice = NotificationCompat.Builder(context, current.id)
            .setSmallIcon(R.drawable.ic_notification_greenhouse)
            .setContentTitle("Critical temperature · Sound test")
            .setContentText("This is a test from Greenhouse Studio.")
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setSilent(boosted)
            .setTimeoutAfter(10_000).setAutoCancel(true).build()
        manager.notify("temperature_sound_test", 1, notice)
        return if (boosted) "Loud test played. Your previous alarm volume will be restored."
        else "Test sent using Android notification settings."
    }

    companion object {
        fun selected(context: Context): TemperatureSound {
            val id = context.getSharedPreferences("client_alert_audio", Context.MODE_PRIVATE)
                .getString("critical_temperature", null)
            return entries.firstOrNull { it.channelId == id } ?: Existing
        }
    }
}
