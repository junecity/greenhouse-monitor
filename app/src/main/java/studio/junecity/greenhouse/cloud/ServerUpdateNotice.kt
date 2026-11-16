package studio.junecity.greenhouse.cloud

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import studio.junecity.greenhouse.BuildConfig
import studio.junecity.greenhouse.R

internal enum class ServerVersion { Unknown, Same, Older, Newer }

internal fun compareServerVersion(name: String?, code: Int?, clientName: String = BuildConfig.VERSION_NAME,
    clientCode: Int = BuildConfig.VERSION_CODE): ServerVersion {
    val comparison = if (code != null && code > 0) code.compareTo(clientCode) else {
        fun parts(version: String?) = version?.split('.')?.takeIf { it.size == 3 }?.map { it.toIntOrNull() }
        val server = parts(name) ?: return ServerVersion.Unknown
        val client = parts(clientName) ?: return ServerVersion.Unknown
        if ((server + client).any { it == null }) return ServerVersion.Unknown
        server.indices.map { server[it]!!.compareTo(client[it]!!) }.firstOrNull { it != 0 } ?: 0
    }
    return when {
        comparison < 0 -> ServerVersion.Older
        comparison > 0 -> ServerVersion.Newer
        else -> ServerVersion.Same
    }
}

internal data class ReportedVersion(val label: String, val comparison: ServerVersion, val fromHouseReport: Boolean)

internal data class ServerUpdateControl(val detail: String, val target: Int? = null)

internal enum class InstallMode { MANAGED, SELF_UPDATE, CONFIRM_ON_SERVER, MANUAL }

internal fun RegisteredDevice.installMode(requested: Boolean = false): InstallMode {
    val reported = if (requested) requestedUpdateMode ?: updateMode else updateMode
    if (reported == null) return if (managedUpdates) InstallMode.MANAGED else InstallMode.MANUAL
    return InstallMode.entries.firstOrNull { it.name == reported } ?: InstallMode.MANUAL
}

internal fun remoteSetupHelp(installedBuild: Int?): String =
    if (installedBuild == null || installedBuild < 81)
        "Update Greenhouse Studio on the greenhouse phone first. With the latest direct download, open Settings → Connectivity → Allow remote updates."
    else "On the greenhouse phone, open Settings → Connectivity → Allow remote updates. Google Play installations update through Play."

internal fun serverUpdateControl(device: RegisteredDevice, clientCode: Int = BuildConfig.VERSION_CODE): ServerUpdateControl? {
    val installed = device.appVersionCode ?: return null
    if (!device.enabled || device.role != CloudDeviceRole.GreenhouseServer) return null
    if (device.communicationSuspended) return ServerUpdateControl("Reconnect the server to check for updates.")

    val pending = device.requestedUpdateCode
    if (pending != null && pending > installed) {
        val progress = when {
            device.installMode(requested = true) == InstallMode.MANUAL -> "Install reminder sent to the server phone."
            device.updateStatus == "APPROVAL_REQUIRED" -> "Approve the update on the greenhouse phone in Settings → Connectivity."
            device.updateStatus == "DOWNLOADING" -> "Downloading the update."
            device.updateStatus == "INSTALLING" -> "Installing the update. Monitoring will restart."
            device.updateStatus == "RETRY" -> "Update interrupted. The server will retry."
            else -> "Update queued for the server’s next check-in."
        }
        return ServerUpdateControl("Build $pending · $progress")
    }
    // Old backends only support a notification for an ordinary installation.
    val available = device.latestReleaseCode ?: if (!device.hasReleaseCatalog && !device.managedUpdates) clientCode else null
    if (available == null) return ServerUpdateControl(
        if (device.hasReleaseCatalog) "No server update is published." else "Remote release information is unavailable."
    )
    if (available <= installed) return if (device.hasReleaseCatalog) ServerUpdateControl(
        if (available == installed) "Server is up to date." else "Server has a newer build than the published release."
    ) else null
    val release = device.latestReleaseName?.takeIf { it.isNotBlank() } ?: "build $available"
    val instructions = if (installed < 73) "Install this update on the server phone to enable future update requests."
    else when (device.installMode()) {
        InstallMode.MANAGED -> "One tap installs the update and restarts monitoring when the server checks in."
        InstallMode.SELF_UPDATE -> "The server can install this update when it checks in. Android may ask for approval."
        InstallMode.CONFIRM_ON_SERVER -> "Start the update here, then tap Install on the greenhouse phone."
        InstallMode.MANUAL -> remoteSetupHelp(installed)
    }
    val label = if (device.updateStatus == "DECLINED") "Previous update declined on the server phone. Update available" else "Update available"
    return ServerUpdateControl("$label · $release\n$instructions", available.takeIf { installed >= 73 })
}

internal fun resolveServerVersion(item: AccountDevice, report: studio.junecity.greenhouse.model.DeviceStatus?): ReportedVersion {
    fun version(name: String?, code: Int?, fromHouseReport: Boolean): ReportedVersion? {
        val comparison = compareServerVersion(name, code)
        if (comparison == ServerVersion.Unknown) return null
        val label = name?.takeIf { compareServerVersion(it, null) != ServerVersion.Unknown } ?: "build $code"
        return ReportedVersion(label, comparison, fromHouseReport)
    }

    val device = item.device
    version(device.appVersion, device.appVersionCode, false)?.let { return it }
    if (device.enabled && !device.communicationSuspended && device.role == CloudDeviceRole.GreenhouseServer &&
        report != null && !device.greenhouseId.isNullOrBlank() && report.cloudGreenhouseId == device.greenhouseId) {
        // Older snapshots identify only the house. Keep their origin visible and reject pre-registration data.
        val matches = if (report.sourceDeviceId != null) report.sourceDeviceId == device.id
            else item.createdAtEpochMs > 0 && report.reportedAt > item.createdAtEpochMs
        if (matches) version(report.appVersion, report.appVersionCode, report.sourceDeviceId == null)?.let { return it }
    }
    return ReportedVersion("—", ServerVersion.Unknown, false)
}

internal object ServerUpdateNotice {
    private const val channelId = "server_app_updates"
    private const val notificationId = 17302
    // Sideloaded installations share the same signed APK release file.
    private const val apkUrl = "https://drive.google.com/file/d/19NBhnl5JRIscX6MaR5q7nFPM_a2plNXR/view"

    fun sync(context: Context, deviceId: String, target: Int?) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val prefs = context.getSharedPreferences("server_update_notice", Context.MODE_PRIVATE)
        if (target == null || target <= BuildConfig.VERSION_CODE) {
            if (prefs.contains("last")) {
                manager.cancel(notificationId)
                prefs.edit().remove("last").apply()
            }
            return
        }
        val key = "$deviceId/$target"
        if (prefs.getString("last", null) == key || !manager.areNotificationsEnabled()) return
        manager.createNotificationChannel(NotificationChannel(channelId, "Server app updates", NotificationManager.IMPORTANCE_DEFAULT))
        if (manager.getNotificationChannel(channelId).importance == NotificationManager.IMPORTANCE_NONE) return
        @Suppress("DEPRECATION")
        val installer = if (Build.VERSION.SDK_INT >= 30)
            context.packageManager.getInstallSourceInfo(context.packageName).installingPackageName
        else context.packageManager.getInstallerPackageName(context.packageName)
        val url = if (installer == "com.android.vending") "https://play.google.com/store/apps/details?id=${context.packageName}" else apkUrl
        val open = PendingIntent.getActivity(context, notificationId, Intent(Intent.ACTION_VIEW, Uri.parse(url)),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notice = NotificationCompat.Builder(context, channelId).setSmallIcon(R.drawable.ic_notification_greenhouse)
            .setContentTitle("Greenhouse Studio update requested")
            .setContentText("Your paired client requested an update. Tap to review and install.")
            .setContentIntent(open).setAutoCancel(true).build()
        manager.notify(notificationId, notice)
        prefs.edit().putString("last", key).apply()
    }
}
