package studio.junecity.greenhouse.cloud

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.Looper

/** An explicitly enabled, bounded alarm for a new critical temperature incident on this phone. */
internal object CriticalAlarm {
    private const val STREAM = AudioManager.STREAM_ALARM
    private const val WINDOW_MS = 5_000L
    private val handler = Handler(Looper.getMainLooper())
    private var playing: Ringtone? = null
    private var stop: Runnable? = null
    private fun prefs(context: Context) = context.getSharedPreferences("client_alert_audio", Context.MODE_PRIVATE)

    fun enabled(context: Context) = prefs(context).getBoolean("loud_alarm", false)

    @Synchronized fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("loud_alarm", enabled).apply()
        if (!enabled) finish(context)
    }

    @Synchronized fun recover(context: Context) {
        if (playing == null) restoreVolume(context)
    }

    private fun restoreVolume(context: Context) {
        val preferences = prefs(context)
        val original = preferences.getInt("previous_alarm_volume", -1)
        if (original < 0) return
        val audio = context.getSystemService(AudioManager::class.java)
        // Leave an intervening user adjustment alone.
        if (audio.getStreamVolume(STREAM) == preferences.getInt("raised_alarm_volume", -1)) {
            try {
                audio.setStreamVolume(STREAM, original, 0)
            } catch (_: SecurityException) {
                return
            }
        }
        preferences.edit().remove("previous_alarm_volume").remove("raised_alarm_volume").commit()
    }

    @Synchronized private fun finish(context: Context) {
        stop?.let(handler::removeCallbacks)
        stop = null
        playing?.stop()
        playing = null
        restoreVolume(context)
    }

    private fun alarmsAllowed(manager: NotificationManager): Boolean = when (manager.currentInterruptionFilter) {
        NotificationManager.INTERRUPTION_FILTER_ALL, NotificationManager.INTERRUPTION_FILTER_ALARMS -> true
        NotificationManager.INTERRUPTION_FILTER_PRIORITY -> runCatching {
            val policy = if (Build.VERSION.SDK_INT >= 30) manager.consolidatedNotificationPolicy else manager.notificationPolicy
            policy.priorityCategories and NotificationManager.Policy.PRIORITY_CATEGORY_ALARMS != 0
        }.getOrDefault(false)
        else -> false
    }

    /** Fall back to ordinary Android notification delivery when policy disallows the override. */
    @Synchronized fun play(context: Context, channel: NotificationChannel): Boolean {
        if (!enabled(context)) return false
        val app = context.applicationContext
        val manager = app.getSystemService(NotificationManager::class.java)
        val uri = channel.sound ?: return false
        if (!manager.areNotificationsEnabled() || channel.importance < NotificationManager.IMPORTANCE_DEFAULT ||
            !alarmsAllowed(manager)) return false
        if (playing?.isPlaying == true) return true
        finish(app)
        val audio = app.getSystemService(AudioManager::class.java)
        return try {
            val tone = RingtoneManager.getRingtone(app, uri) ?: return false
            tone.audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
            val before = audio.getStreamVolume(STREAM)
            val peak = audio.getStreamMaxVolume(STREAM)
            if (!audio.isVolumeFixed && before < peak) {
                if (!prefs(app).edit().putInt("previous_alarm_volume", before)
                        .putInt("raised_alarm_volume", peak).commit()) return false
                audio.setStreamVolume(STREAM, peak, 0)
            }
            playing = tone
            tone.play()
            if (!tone.isPlaying) {
                finish(app)
                false
            } else {
                stop = Runnable {
                    synchronized(this) {
                        if (playing === tone) finish(app)
                    }
                }.also { handler.postDelayed(it, WINDOW_MS) }
                true
            }
        } catch (_: RuntimeException) {
            finish(app)
            false
        }
    }
}
