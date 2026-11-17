package studio.junecity.greenhouse.cloud

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import studio.junecity.greenhouse.settings.AppPreferencesStore
import studio.junecity.greenhouse.settings.SecureSettingsStore
import studio.junecity.greenhouse.widget.ClimateWidget
import studio.junecity.greenhouse.widget.LockScreenClimate

class CloudBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val updated = intent.action == Intent.ACTION_MY_PACKAGE_REPLACED
        if (!updated && intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (LockScreenClimate.configured(context)) ClimateWidget.lockScreenChanged(context)
        val shouldRestart = if (updated) ManagedServerUpdate.consumeRestart(context)
            else AppPreferencesStore(context).load().autoStart
        val configured = runCatching { CloudRuntime.configurationOrNull() != null }.getOrDefault(false)
        val enrollment = SecureSettingsStore(context).cloudEnrollment()
        if (!configured || enrollment == null) return
        GreenhousePlanWorker.schedule(context)
        // The dashboard's boot preference applies to the greenhouse phone. A paired viewer
        // must also be listening before its first temperature incident arrives.
        if (!shouldRestart && enrollment.role != CloudDeviceRole.ViewerClient) return
        CloudAlertService.sync(context, enabled = true)
        if (enrollment.role == CloudDeviceRole.GreenhouseServer) {
            CloudHeartbeatService.sync(context, enabled = true)
            if (updated && ManagedServerUpdate.enabled(context)) context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
                context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
        // Camera capture is intentionally not started from a boot receiver. Modern Android
        // prohibits background camera foreground-service startup; it starts after the app opens.
    }
}
