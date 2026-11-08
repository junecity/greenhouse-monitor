package studio.junecity.greenhouse.cloud

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import studio.junecity.greenhouse.settings.AppPreferencesStore
import studio.junecity.greenhouse.settings.SecureSettingsStore

class CloudBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!AppPreferencesStore(context).load().autoStart) return
        val configured = runCatching { CloudRuntime.configurationOrNull() != null }.getOrDefault(false)
        val enrollment = SecureSettingsStore(context).cloudEnrollment()
        if (!configured || enrollment == null) return
        CloudAlertService.sync(context, enabled = true)
        if (enrollment.role == CloudDeviceRole.GreenhouseServer) {
            CloudHeartbeatService.sync(context, enabled = true)
        }
        // Camera capture is intentionally not started from a boot receiver. Modern Android
        // prohibits background camera foreground-service startup; it starts after the app opens.
    }
}
