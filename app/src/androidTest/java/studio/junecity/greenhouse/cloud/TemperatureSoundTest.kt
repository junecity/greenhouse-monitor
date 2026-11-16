package studio.junecity.greenhouse.cloud

import android.app.NotificationManager
import android.app.NotificationChannel
import android.os.ParcelFileDescriptor
import android.os.Build
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.os.SystemClock
import android.provider.Settings
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test

class TemperatureSoundTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val audio = context.getSystemService(AudioManager::class.java)
    private var originalFilter = NotificationManager.INTERRUPTION_FILTER_ALL
    private var originalRinger = AudioManager.RINGER_MODE_NORMAL
    private var hadPolicyAccess = false

    private fun shell(command: String) {
        ParcelFileDescriptor.AutoCloseInputStream(
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        ).use { it.readBytes() }
    }

    private fun setFilter(filter: Int) {
        if (Build.VERSION.SDK_INT == 29) {
            manager.setInterruptionFilter(filter)
        } else {
            val mode = when (filter) {
                NotificationManager.INTERRUPTION_FILTER_NONE -> "none"
                NotificationManager.INTERRUPTION_FILTER_PRIORITY -> "priority"
                NotificationManager.INTERRUPTION_FILTER_ALARMS -> "alarms"
                else -> "off"
            }
            shell("cmd notification set_dnd $mode")
        }
        val deadline = SystemClock.elapsedRealtime() + 3000
        while (manager.currentInterruptionFilter != filter && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(25)
        assertEquals(filter, manager.currentInterruptionFilter)
    }

    @Before fun useTestAudioEnvironment() {
        originalFilter = manager.currentInterruptionFilter
        originalRinger = audio.ringerMode
        hadPolicyAccess = manager.isNotificationPolicyAccessGranted
        if (!hadPolicyAccess) shell("cmd notification allow_dnd ${context.packageName}")
        setFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
        audio.ringerMode = AudioManager.RINGER_MODE_NORMAL
    }

    @After fun restoreAudioEnvironment() {
        try {
            audio.ringerMode = originalRinger
            setFilter(originalFilter)
        } finally {
            if (!hadPolicyAccess) shell("cmd notification disallow_dnd ${context.packageName}")
        }
    }

    @Test fun tunesArePlayableAndScopedToClientTemperatureIncidents() {
        val chosen = TemperatureSound.selected(context)
        val alert = MonitoringAlert("fixture", "house", MonitoringAlertType.CriticalTemperature,
            MonitoringAlertPriority.Critical, "Low temperature", 0, true)
        try {
            for (sound in listOf(TemperatureSound.Pulse, TemperatureSound.Sweep)) {
                sound.select(context)
                assertEquals(sound, TemperatureSound.selected(context))
                val channel = sound.channel(context)
                val metadata = MediaMetadataRetriever()
                try {
                    metadata.setDataSource(context, channel.sound)
                    assertTrue(metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toInt() > 2500)
                } finally { metadata.release() }
                assertEquals(channel.id, CloudAlertService.alertChannel(context, alert, CloudDeviceRole.ViewerClient))
                assertEquals(TemperatureSound.Existing.channelId, CloudAlertService.alertChannel(context,
                    alert.copy(type = MonitoringAlertType.ServerOffline), CloudDeviceRole.ViewerClient))
                assertEquals(TemperatureSound.Existing.channelId, CloudAlertService.alertChannel(context, alert, CloudDeviceRole.GreenhouseServer))
            }
            assertEquals(TemperatureSound.Custom.channelId, TemperatureSound.Custom.soundSettings(context)
                .getStringExtra(Settings.EXTRA_CHANNEL_ID))
        } finally { chosen.select(context) }
    }

    @Test fun disabledOverrideKeepsVolumeAndTotalSilenceBlocksBoost() {
        val enabled = CriticalAlarm.enabled(context)
        val previous = audio.getStreamVolume(AudioManager.STREAM_ALARM)
        val channel = TemperatureSound.Sweep.channel(context)
        try {
            CriticalAlarm.setEnabled(context, false)
            assertFalse(CriticalAlarm.play(context, channel))
            assertEquals(previous, audio.getStreamVolume(AudioManager.STREAM_ALARM))
            setFilter(NotificationManager.INTERRUPTION_FILTER_NONE)
            CriticalAlarm.setEnabled(context, true)
            val quiet = audio.getStreamVolume(AudioManager.STREAM_ALARM)
            assertFalse(CriticalAlarm.play(context, channel))
            assertEquals(quiet, audio.getStreamVolume(AudioManager.STREAM_ALARM))
        } finally {
            setFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
            CriticalAlarm.setEnabled(context, enabled)
        }
    }

    @Test fun loudTestRaisesAlarmVolumeAndRestoresItWithoutChangingRinger() {
        val stream = AudioManager.STREAM_ALARM
        val previous = audio.getStreamVolume(stream)
        val ringer = audio.ringerMode
        val enabled = CriticalAlarm.enabled(context)
        try {
            CriticalAlarm.setEnabled(context, true)
            audio.setStreamVolume(stream, audio.getStreamMinVolume(stream), 0)
            val startingLevel = audio.getStreamVolume(stream)
            assertTrue(TemperatureSound.Pulse.test(context).startsWith("Loud test played"))
            assertEquals(audio.getStreamMaxVolume(stream), audio.getStreamVolume(stream))
            SystemClock.sleep(5500)
            assertEquals(startingLevel, audio.getStreamVolume(stream))
            assertEquals(ringer, audio.ringerMode)
            assertTrue(manager.activeNotifications.any { it.tag == "temperature_sound_test" })
        } finally {
            CriticalAlarm.setEnabled(context, false)
            CriticalAlarm.setEnabled(context, enabled)
            audio.setStreamVolume(stream, previous, 0)
            manager.cancel("temperature_sound_test", 1)
        }
    }

    @Test fun realClientDeliveryLoopsOnAlarmStreamOnceWhenNotificationsAreMuted() {
        val selected = TemperatureSound.selected(context)
        val enabled = CriticalAlarm.enabled(context)
        val previousVolume = audio.getStreamVolume(AudioManager.STREAM_ALARM)
        val ringerMode = audio.ringerMode
        val alert = MonitoringAlert("sound-delivery-${System.nanoTime()}", "test-house",
            MonitoringAlertType.CriticalTemperature, MonitoringAlertPriority.Critical,
            "Bench sensor measured 35°F, below the 40°F limit.", System.currentTimeMillis(), true)
        fun alarmIsPlaying() = audio.activePlaybackConfigurations.any { it.audioAttributes.usage == AudioAttributes.USAGE_ALARM }
        try {
            TemperatureSound.Pulse.select(context)
            CriticalAlarm.setEnabled(context, true)
            audio.ringerMode = AudioManager.RINGER_MODE_VIBRATE
            audio.setStreamVolume(AudioManager.STREAM_ALARM, audio.getStreamMinVolume(AudioManager.STREAM_ALARM), 0)
            val low = audio.getStreamVolume(AudioManager.STREAM_ALARM)
            val mutedRinger = audio.ringerMode // Devices without a vibrator select silent instead.
            assertTrue(low < audio.getStreamMaxVolume(AudioManager.STREAM_ALARM))
            assertNotEquals(AudioManager.RINGER_MODE_NORMAL, mutedRinger)
            assertEquals(0, audio.getStreamVolume(AudioManager.STREAM_NOTIFICATION))
            postCloudAlert(context, alert, CloudDeviceRole.ViewerClient)
            assertEquals(audio.getStreamMaxVolume(AudioManager.STREAM_ALARM), audio.getStreamVolume(AudioManager.STREAM_ALARM))
            // The bundled pulse is about three seconds. A loud alert should sound for the full five.
            SystemClock.sleep(3800)
            assertTrue("Android should still be rendering alarm audio", alarmIsPlaying())
            assertTrue(manager.activeNotifications.any { it.id == alert.id.hashCode() })
            SystemClock.sleep(1650)
            assertFalse("Five-second window must close", alarmIsPlaying())
            assertEquals(low, audio.getStreamVolume(AudioManager.STREAM_ALARM))
            postCloudAlert(context, alert, CloudDeviceRole.ViewerClient)
            assertFalse("Polling an active notification must not replay the siren", alarmIsPlaying())
            assertEquals(low, audio.getStreamVolume(AudioManager.STREAM_ALARM))
            assertEquals(mutedRinger, audio.ringerMode)
        } finally {
            manager.cancel(alert.id.hashCode())
            CriticalAlarm.setEnabled(context, false)
            audio.ringerMode = ringerMode
            audio.setStreamVolume(AudioManager.STREAM_ALARM, previousVolume, 0)
            CriticalAlarm.setEnabled(context, enabled)
            selected.select(context)
        }
    }

    @Test fun explainsMutedNotificationsAndHonorsSilentChannel() {
        val quietId = "temperature_qa_silent"
        manager.createNotificationChannel(NotificationChannel(quietId, "QA silent channel", NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(null, null)
        })
        val ringer = audio.ringerMode
        val previouslyEnabled = CriticalAlarm.enabled(context)
        try {
            audio.ringerMode = AudioManager.RINGER_MODE_VIBRATE
            CriticalAlarm.setEnabled(context, false)
            assertTrue(TemperatureSound.Sweep.test(context).contains("Notification sound is muted"))
            CriticalAlarm.setEnabled(context, true)
            val muted = manager.getNotificationChannel(quietId)
            assertTrue(CriticalAlarm.soundIssue(context, muted)!!.contains("channel is silent"))
            assertFalse(CriticalAlarm.play(context, muted))
        } finally {
            CriticalAlarm.setEnabled(context, previouslyEnabled)
            audio.ringerMode = ringer
            manager.deleteNotificationChannel(quietId)
        }
    }
}
