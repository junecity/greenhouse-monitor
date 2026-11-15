package studio.junecity.greenhouse.cloud

import android.app.NotificationManager
import android.os.ParcelFileDescriptor
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.os.SystemClock
import android.provider.Settings
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class TemperatureSoundTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val audio = context.getSystemService(AudioManager::class.java)

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
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        fun silence(mode: String) {
            ParcelFileDescriptor.AutoCloseInputStream(
                instrumentation.uiAutomation.executeShellCommand("cmd notification set_dnd $mode")
            ).use { it.readBytes() }
            val expected = if (mode == "none") NotificationManager.INTERRUPTION_FILTER_NONE else NotificationManager.INTERRUPTION_FILTER_ALL
            val deadline = SystemClock.elapsedRealtime() + 3000
            while (manager.currentInterruptionFilter != expected && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(25)
            assertEquals(expected, manager.currentInterruptionFilter)
        }
        try {
            CriticalAlarm.setEnabled(context, false)
            assertFalse(CriticalAlarm.play(context, channel))
            assertEquals(previous, audio.getStreamVolume(AudioManager.STREAM_ALARM))
            silence("none")
            CriticalAlarm.setEnabled(context, true)
            val quiet = audio.getStreamVolume(AudioManager.STREAM_ALARM)
            assertFalse(CriticalAlarm.play(context, channel))
            assertEquals(quiet, audio.getStreamVolume(AudioManager.STREAM_ALARM))
        } finally {
            silence("off")
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
}
