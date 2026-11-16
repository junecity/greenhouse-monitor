package studio.junecity.greenhouse.cloud

import android.app.PendingIntent
import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import studio.junecity.greenhouse.BuildConfig
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

    fun status(context: Context, deviceId: String): String? {
        val prefs = state(context)
        val key = prefs.getString("key", null) ?: return null
        return prefs.getString("status", null)?.takeIf { key.startsWith("$deviceId/") }
    }

    fun setStatus(context: Context, value: String) {
        state(context).edit().putString("status", value).apply()
    }

    fun due(context: Context, key: String): Boolean {
        val prefs = state(context)
        val elapsed = System.currentTimeMillis() - prefs.getLong("attempt", 0)
        return prefs.getString("key", null) != key || elapsed !in 0 until 15 * 60_000L
    }

    suspend fun install(context: Context, gateway: AuthenticatedCloudGateway, enrollment: CloudEnrollment,
                        target: Int, artifactClient: OkHttpClient = download) {
        require(enabled(context) && enrollment.role == CloudDeviceRole.GreenhouseServer && target > BuildConfig.VERSION_CODE)
        state(context).edit().putString("key", "${enrollment.deviceId}/$target")
            .putLong("attempt", System.currentTimeMillis()).putString("status", "DOWNLOADING").apply()
        val release = gateway.serverUpdatePackage()
        check(release.getInt("versionCode") == target)
        val file = File(context.cacheDir, "managed-server-update.apk")
        try {
            downloadRelease(release, file, artifactClient)
            verifyPackage(context, file, target)
            val current = gateway.serverUpdatePackage()
            check(current.getInt("versionCode") == target && current.getString("sha256") == release.getString("sha256"))
            currentCoroutineContext().ensureActive()
            check(SecureSettingsStore(context).cloudEnrollment() == enrollment && enabled(context))
            commit(context, file, target)
        } finally {
            file.delete()
        }
    }

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

    private fun commit(context: Context, file: File, target: Int) {
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
                check(state(context).edit().putInt("restartAt", target).commit())
                session.commit(PendingIntent.getBroadcast(context, id, result, flags).intentSender)
            }
        } catch (failure: Exception) {
            installer.abandonSession(id)
            throw failure
        }
    }

    fun consumeRestart(context: Context): Boolean {
        val prefs = state(context)
        val matched = prefs.getInt("restartAt", -1) == BuildConfig.VERSION_CODE
        prefs.edit().remove("restartAt").remove("status").apply()
        return matched && enabled(context)
    }
}

class ServerInstallResult : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            val id = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
            if (id >= 0) runCatching { context.packageManager.packageInstaller.abandonSession(id) }
        }
        if (status != PackageInstaller.STATUS_SUCCESS) {
            ManagedServerUpdate.setStatus(context, if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) "APPROVAL_REQUIRED" else "RETRY")
            android.util.Log.w("GreenhouseUpdate", "Installer status $status")
        }
    }
}
