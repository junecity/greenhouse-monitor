package studio.junecity.greenhouse.cloud

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper

/** One audio output for all ringing client incidents. Only a settings preview has a time limit. */
internal object CriticalAlarm {
    private const val STREAM = AudioManager.STREAM_ALARM
    private const val PREVIEW_MS = 5_000L
    private val handler = Handler(Looper.getMainLooper())
    private var playing: Ringtone? = null
    private var incidentChannel: NotificationChannel? = null
    private var stopPreview: Runnable? = null
    private fun prefs(context: Context) = context.getSharedPreferences("client_alert_audio", Context.MODE_PRIVATE)

    fun enabled(context: Context) = prefs(context).getBoolean("loud_alarm", true)

    @Synchronized fun setEnabled(context: Context, enabled: Boolean) {
        if (enabled == enabled(context)) return
        prefs(context).edit().putBoolean("loud_alarm", enabled).apply()
        val channel = incidentChannel
        finish(context)
        if (channel != null) startIncident(context, channel)
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
        stopPreview?.let(handler::removeCallbacks)
        stopPreview = null
        incidentChannel = null
        val tone = playing
        playing = null
        try {
            tone?.stop()
        } finally {
            restoreVolume(context)
        }
    }

    private fun alarmsAllowed(manager: NotificationManager): Boolean = when (manager.currentInterruptionFilter) {
        NotificationManager.INTERRUPTION_FILTER_ALL, NotificationManager.INTERRUPTION_FILTER_ALARMS -> true
        NotificationManager.INTERRUPTION_FILTER_PRIORITY -> runCatching {
            val policy = if (Build.VERSION.SDK_INT >= 30) manager.consolidatedNotificationPolicy else manager.notificationPolicy
            policy.priorityCategories and NotificationManager.Policy.PRIORITY_CATEGORY_ALARMS != 0
        }.getOrDefault(false)
        else -> false
    }

    fun soundIssue(context: Context, channel: NotificationChannel, loud: Boolean = enabled(context)): String? {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (!manager.areNotificationsEnabled()) return "Allow Greenhouse Studio notifications in Android settings."
        if (channel.importance < NotificationManager.IMPORTANCE_DEFAULT || channel.sound == null)
            return "This alert channel is silent. Tap Sound settings and choose a sound."
        val defaultType = RingtoneManager.getDefaultType(channel.sound)
        if (!loud && defaultType >= 0 && RingtoneManager.getActualDefaultRingtoneUri(context, defaultType) == null)
            return "The phone’s default sound is set to None. Select Double pulse or choose a sound in Android settings."
        val audio = context.getSystemService(AudioManager::class.java)
        if (loud) {
            if (!alarmsAllowed(manager)) return "Do Not Disturb is blocking the loud alarm. Allow alarms in Android settings to hear it."
            if (audio.isVolumeFixed && audio.getStreamVolume(STREAM) == 0) return "The alarm output is muted. Adjust the volume on the connected audio device."
        } else {
            if (audio.ringerMode != AudioManager.RINGER_MODE_NORMAL ||
                audio.getStreamVolume(AudioManager.STREAM_NOTIFICATION) == 0)
                return "Notification sound is muted. Turn on Override low or muted volume to use the alarm volume."
            if (manager.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL && !channel.canBypassDnd())
                return "Do Not Disturb is silencing notifications. Check your Android sound settings."
        }
        return null
    }

    @Synchronized fun isIncidentPlaying() = incidentChannel != null && playing?.isPlaying == true

    @Synchronized fun stopIncident(context: Context) {
        if (incidentChannel != null) finish(context)
    }

    @Synchronized fun startIncident(context: Context, channel: NotificationChannel): Boolean {
        val app = context.applicationContext
        val loud = enabled(app)
        if (soundIssue(app, channel, loud) != null) {
            stopIncident(app)
            return false
        }
        if (incidentChannel?.id == channel.id && incidentChannel?.sound == channel.sound && isIncidentPlaying()) return true
        if (!start(app, channel, loud)) return false
        incidentChannel = channel
        return true
    }

    @Synchronized fun startPreview(context: Context, channel: NotificationChannel): Boolean {
        val app = context.applicationContext
        if (isIncidentPlaying() || !enabled(app) || soundIssue(app, channel) != null) return false
        if (!start(app, channel, loud = true)) return false
        val preview = playing
        stopPreview = Runnable {
            synchronized(this) {
                if (playing === preview) finish(app)
            }
        }.also { handler.postDelayed(it, PREVIEW_MS) }
        return true
    }

    private fun start(app: Context, channel: NotificationChannel, loud: Boolean): Boolean {
        finish(app)
        val audio = app.getSystemService(AudioManager::class.java)
        return try {
            val before = audio.getStreamVolume(STREAM)
            val peak = audio.getStreamMaxVolume(STREAM)
            if (loud && !audio.isVolumeFixed && before < peak) {
                if (!prefs(app).edit().putInt("previous_alarm_volume", before)
                        .putInt("raised_alarm_volume", peak).commit()) return false
                audio.setStreamVolume(STREAM, peak, 0)
            }
            val usage = if (loud) AudioAttributes.USAGE_ALARM else AudioAttributes.USAGE_NOTIFICATION_EVENT
            // A default-sound URI can point to "None". Android may hand back a Ringtone
            // for that URI even though it has no audio. Resolve it before attempting playback.
            val defaultType = RingtoneManager.getDefaultType(channel.sound)
            val selected = if (defaultType >= 0) RingtoneManager.getActualDefaultRingtoneUri(app, defaultType) else channel.sound
            val tone = selected?.let { startTone(app, it, usage) }
                ?: TemperatureSound.Pulse.bundledUri(app)?.let { startTone(app, it, usage) }
            if (tone == null) {
                finish(app)
                false
            } else {
                playing = tone
                true
            }
        } catch (_: RuntimeException) {
            finish(app)
            false
        }
    }

    private fun startTone(context: Context, uri: Uri, usage: Int): Ringtone? {
        var tone: Ringtone? = null
        try {
            tone = RingtoneManager.getRingtone(context, uri) ?: return null
            tone.audioAttributes = AudioAttributes.Builder().setUsage(usage)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
            tone.isLooping = true
            tone.play()
            if (tone.isPlaying) return tone
        } catch (_: RuntimeException) {
            // A user-selected tone may become unreadable after removable storage or its provider goes away.
        }
        tone?.stop()
        return null
    }
}
