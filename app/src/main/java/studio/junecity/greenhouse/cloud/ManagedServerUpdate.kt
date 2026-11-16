package studio.junecity.greenhouse.cloud

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.SharedPreferences
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.net.Uri
import android.provider.Settings
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import studio.junecity.greenhouse.BuildConfig
import studio.junecity.greenhouse.R
import studio.junecity.greenhouse.settings.CloudEnrollment
import studio.junecity.greenhouse.settings.SecureSettingsStore
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class GreenhouseDeviceAdmin : DeviceAdminReceiver()

internal object ManagedServerUpdate {
    private val download = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .callTimeout(10, TimeUnit.MINUTES).build()

    fun enabled(context: Context) =
        context.getSystemService(DevicePolicyManager::class.java).isDeviceOwnerApp(context.packageName)

    private fun state(context: Context) = context.getSharedPreferences("managed_server_update", Context.MODE_PRIVATE)

    private const val approvalNotification = 17303

    @Suppress("DEPRECATION")
    fun canEnableDirectUpdates(context: Context): Boolean {
        if (!BuildConfig.DIRECT_SERVER_UPDATES || enabled(context)) return false
        val store = if (Build.VERSION.SDK_INT >= 30)
            context.packageManager.getInstallSourceInfo(context.packageName).installingPackageName
        else context.packageManager.getInstallerPackageName(context.packageName)
        return store != "com.android.vending"
    }

    // Re-pairing creates a fresh secret even when a greenhouse or device ID is reused.
    private fun enrollmentKey(server: CloudEnrollment): String =
        MessageDigest.getInstance("SHA-256").digest(server.toJson().toString().toByteArray())
            .joinToString("") { "%02x".format(it) }

    fun directUpdatesOptedIn(context: Context, server: CloudEnrollment) =
        server.role == CloudDeviceRole.GreenhouseServer && !server.deviceSecret.isNullOrBlank() &&
            state(context).getString("optIn", null) == enrollmentKey(server)

    fun setDirectUpdatesEnabled(context: Context, server: CloudEnrollment, enabled: Boolean) {
        require(server.role == CloudDeviceRole.GreenhouseServer && !server.deviceSecret.isNullOrBlank() &&
            server == SecureSettingsStore(context).cloudEnrollment())
        if (enabled) {
            require(canEnableDirectUpdates(context))
            state(context).edit().putString("optIn", enrollmentKey(server)).apply()
        } else {
            state(context).edit().remove("optIn").apply()
            cancelSession(context)
        }
    }

    fun forgetEnrollment(context: Context) {
        cancelSession(context)
        state(context).edit().clear().apply()
    }

    fun installSourceSettings(context: Context) =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.fromParts("package", context.packageName, null))

    fun mode(context: Context, server: CloudEnrollment?): String {
        if (server?.role != CloudDeviceRole.GreenhouseServer) return "MANUAL"
        if (enabled(context)) return "MANAGED"
        if (!canEnableDirectUpdates(context) || !directUpdatesOptedIn(context, server) ||
            !context.packageManager.canRequestPackageInstalls()) return "MANUAL"
        return if (Build.VERSION.SDK_INT >= 31) "SELF_UPDATE" else "CONFIRM_ON_SERVER"
    }

    fun observeChanges(context: Context, onChanged: () -> Unit): () -> Unit {
        val prefs = state(context)
        val observer = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> onChanged() }
        prefs.registerOnSharedPreferenceChangeListener(observer)
        return { prefs.unregisterOnSharedPreferenceChangeListener(observer) }
    }

    fun status(context: Context, deviceId: String): String? {
        val prefs = state(context)
        val key = prefs.getString("key", null) ?: return null
        return prefs.getString("status", null)?.takeIf { key.startsWith("$deviceId/") }
    }

    fun statusRequestId(context: Context, deviceId: String): String? =
        state(context).getString("requestId", null)?.takeIf { status(context, deviceId) != null }

    fun setStatus(context: Context, value: String) {
        state(context).edit().putString("status", value).apply()
    }

    fun due(context: Context, key: String): Boolean {
        val prefs = state(context)
        if (prefs.getString("key", null) == key &&
            (prefs.contains("sessionId") || prefs.getString("status", null) == "DECLINED")) return false
        val elapsed = System.currentTimeMillis() - prefs.getLong("attempt", 0)
        return prefs.getString("key", null) != key || elapsed !in 0 until 15 * 60_000L
    }

    suspend fun install(context: Context, gateway: AuthenticatedCloudGateway, enrollment: CloudEnrollment,
                        target: Int, artifactClient: OkHttpClient = download, mode: String = "MANAGED", requestId: String? = null) {
        val order = ServerUpdateCommand(target, mode, requestId)
        require(order.authorizes(mode) && mode(context, enrollment) == mode && target > BuildConfig.VERSION_CODE)
        require(SecureSettingsStore(context).cloudEnrollment() == enrollment)
        state(context).edit().putString("key", order.key(enrollment.deviceId)).putString("requestId", requestId)
            .putLong("attempt", System.currentTimeMillis()).putString("status", "DOWNLOADING").apply()
        val release = gateway.serverUpdatePackage()
        check(release.getInt("versionCode") == target && permitMatches(release, requestId))
        val file = File(context.cacheDir, "managed-server-update.apk")
        try {
            downloadRelease(release, file, artifactClient)
            verifyPackage(context, file, target)
            val current = gateway.serverUpdatePackage()
            check(current.getInt("versionCode") == target && permitMatches(current, requestId) &&
                current.getString("sha256") == release.getString("sha256"))
            currentCoroutineContext().ensureActive()
            check(SecureSettingsStore(context).cloudEnrollment() == enrollment && mode(context, enrollment) == mode)
            commit(context, file, target, enrollment, mode, release.getString("sha256"))
        } finally {
            file.delete()
        }
    }

    private fun permitMatches(release: JSONObject, requestId: String?) =
        if (requestId == null) release.isNull("requestedUpdateId") else release.optString("requestedUpdateId") == requestId

    private suspend fun downloadRelease(release: JSONObject, file: File, artifactClient: OkHttpClient) {
        val size = release.getLong("bytes")
        require(size in 1..180L * 1024 * 1024)
        val request = Request.Builder().url(release.getString("url")).build()
        require(request.url.isHttps)
        val digest = MessageDigest.getInstance("SHA-256")
        artifactClient.newCall(request).execute().use { response ->
            val body = checkNotNull(response.body)
            check(response.isSuccessful && body.contentLength() == size)
            body.byteStream().use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(65536)
                    var total = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        check(total <= size)
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                    }
                    check(total == size)
                }
            }
        }
        check(digest.digest().joinToString("") { "%02x".format(it) } == release.getString("sha256"))
    }

    @Suppress("DEPRECATION")
    internal fun verifyPackage(context: Context, file: File, target: Int) {
        val pm = context.packageManager
        val candidate = checkNotNull(pm.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_SIGNATURES))
        val installed = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
        check(candidate.packageName == installed.packageName && candidate.versionCode == target && target > installed.versionCode)
        val signers = installed.signatures.orEmpty().toSet()
        check(signers.isNotEmpty() && candidate.signatures.orEmpty().toSet() == signers)
    }

    private fun commit(context: Context, file: File, target: Int, enrollment: CloudEnrollment,
                       mode: String, digest: String) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(file.length())
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        try {
            installer.openSession(id).use { session ->
                session.openWrite("base.apk", 0, file.length()).use { output ->
                    file.inputStream().use { it.copyTo(output) }
                    session.fsync(output)
                }
                val result = Intent(context, ServerInstallResult::class.java)
                setStatus(context, "INSTALLING")
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                check(state(context).edit().putInt("restartAt", target).putInt("sessionId", id)
                    .putString("sessionOwner", enrollmentKey(enrollment)).putString("sessionMode", mode)
                    .putString("sessionDigest", digest).remove("confirmation").commit())
                check(mode(context, enrollment) == mode && enrollment == SecureSettingsStore(context).cloudEnrollment())
                session.commit(PendingIntent.getBroadcast(context, id, result, flags).intentSender)
            }
        } catch (failure: Exception) {
            if (state(context).getInt("sessionId", -1) == id) clearSession(context, keepRestart = false)
            runCatching { installer.abandonSession(id) }
            throw failure
        }
    }

    // Android's pending-user-action reply leaves the session open. Keep the original
    // system Intent (including its session id) in private storage so Settings also works
    // when notifications are disabled or the app process has been reclaimed.
    @Synchronized
    fun installerResult(context: Context, result: Intent) {
        val prefs = state(context)
        val id = result.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        if (id == -1 || prefs.getInt("sessionId", -1) != id) return
        val outcome = result.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        when (outcome) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val enrollment = SecureSettingsStore(context).cloudEnrollment()
                if (enrollment == null || !sessionMatches(context, id, enrollment)) {
                    cancelSession(context)
                    return
                }
                @Suppress("DEPRECATION")
                val open = result.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                val handler = open?.let { context.packageManager.resolveActivity(it, PackageManager.MATCH_SYSTEM_ONLY) }
                    ?.activityInfo
                if (open == null || handler == null) {
                    cancelSession(context)
                    return
                }
                open.component = ComponentName(handler.packageName, handler.name)
                prefs.edit().putString("confirmation", open.toUri(Intent.URI_INTENT_SCHEME))
                    .putString("status", "APPROVAL_REQUIRED").apply()
                val manager = context.getSystemService(NotificationManager::class.java)
                val channel = "server_app_updates"
                manager.createNotificationChannel(NotificationChannel(channel, "Server app updates", NotificationManager.IMPORTANCE_DEFAULT))
                if (manager.areNotificationsEnabled()) {
                    val action = PendingIntent.getActivity(context, id, reviewIntent(context, id),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                    manager.notify(approvalNotification, NotificationCompat.Builder(context, channel)
                        .setSmallIcon(R.drawable.ic_notification_greenhouse)
                        .setContentTitle("Install Greenhouse Studio update")
                        .setContentText("Tap to review this update requested by your paired client.")
                        .setContentIntent(action).setOnlyAlertOnce(true).build())
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                clearSession(context, keepRestart = true)
            }
            else -> {
                clearSession(context, keepRestart = false)
                val declined = outcome == PackageInstaller.STATUS_FAILURE_ABORTED && prefs.contains("requestId")
                setStatus(context, if (declined) "DECLINED" else "RETRY")
                android.util.Log.w("GreenhouseUpdate", "Installer status $outcome")
            }
        }
    }

    private fun reviewIntent(context: Context, id: Int) = Intent(context, ServerInstallApprovalActivity::class.java)
        .putExtra(PackageInstaller.EXTRA_SESSION_ID, id)

    fun openPendingApproval(context: Context, enrollment: CloudEnrollment): Boolean {
        val id = state(context).getInt("sessionId", -1)
        if (!sessionMatches(context, id, enrollment) || !state(context).contains("confirmation")) return false
        return runCatching { context.startActivity(reviewIntent(context, id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
    }

    private fun sessionMatches(context: Context, id: Int, enrollment: CloudEnrollment): Boolean {
        val prefs = state(context)
        return id >= 0 && prefs.getInt("sessionId", -1) == id &&
            prefs.getString("sessionOwner", null) == enrollmentKey(enrollment) &&
            prefs.getString("sessionMode", null) == mode(context, enrollment)
    }

    suspend fun authorizedConfirmation(context: Context, id: Int, enrollment: CloudEnrollment,
                                      gateway: AuthenticatedCloudGateway): Intent {
        check(sessionMatches(context, id, enrollment))
        val release = gateway.serverUpdatePackage()
        val prefs = state(context)
        check(enrollment == SecureSettingsStore(context).cloudEnrollment() && sessionMatches(context, id, enrollment))
        check(permitMatches(release, prefs.getString("requestId", null)) &&
            release.getInt("versionCode") == prefs.getInt("restartAt", -1) &&
            release.getString("sha256") == prefs.getString("sessionDigest", null))
        check(context.packageManager.packageInstaller.getSessionInfo(id)?.appPackageName == context.packageName)
        return Intent.parseUri(checkNotNull(prefs.getString("confirmation", null)), Intent.URI_INTENT_SCHEME)
    }

    @Synchronized
    fun reconcileSession(context: Context, enrollment: CloudEnrollment, order: ServerUpdateCommand?, mode: String) {
        val prefs = state(context)
        val id = prefs.getInt("sessionId", -1)
        if (id == -1) return
        if (order?.key(enrollment.deviceId) != prefs.getString("key", null) ||
            mode != prefs.getString("sessionMode", null) ||
            !sessionMatches(context, id, enrollment) ||
            context.packageManager.packageInstaller.getSessionInfo(id) == null) cancelSession(context)
    }

    @Synchronized
    fun cancelSession(context: Context) {
        val id = state(context).getInt("sessionId", -1)
        if (id < 0) return
        clearSession(context, keepRestart = false)
        runCatching { context.packageManager.packageInstaller.abandonSession(id) }
    }

    private fun clearSession(context: Context, keepRestart: Boolean) {
        val edit = state(context).edit().remove("sessionId").remove("sessionDigest").remove("confirmation")
            .remove("sessionMode").remove("status")
        if (!keepRestart) edit.remove("restartAt").remove("sessionOwner")
        edit.apply()
        context.getSystemService(NotificationManager::class.java).cancel(approvalNotification)
    }

    fun consumeRestart(context: Context): Boolean {
        val prefs = state(context)
        val matched = prefs.getInt("restartAt", -1) == BuildConfig.VERSION_CODE
        val server = SecureSettingsStore(context).cloudEnrollment()
        val owner = prefs.getString("sessionOwner", null)
        // Older device-owner builds only wrote restartAt.
        val eligible = enabled(context) || (server != null && owner == enrollmentKey(server) &&
            mode(context, server) != "MANUAL")
        clearSession(context, keepRestart = false)
        return matched && eligible
    }
}

class ServerInstallResult : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = ManagedServerUpdate.installerResult(context, intent)
}
