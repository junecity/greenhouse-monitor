package studio.junecity.greenhouse.backup

import android.content.Context
import androidx.work.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import studio.junecity.greenhouse.cloud.CloudDeviceRole
import studio.junecity.greenhouse.cloud.CloudRuntime
import studio.junecity.greenhouse.settings.*
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

internal fun localBackupScope(enrollment: CloudEnrollment?): BackupScope? {
    if (enrollment == null) return if (CloudRuntime.configurationOrNull() == null) BackupScope("local","","collector") else null
    if (enrollment.tenantId.isBlank()) return null
    return when {
        enrollment.role == CloudDeviceRole.GreenhouseServer -> BackupScope(enrollment.tenantId,enrollment.greenhouseId,"collector")
        enrollment.isStandaloneClient() -> BackupScope(enrollment.tenantId,"","standalone")
        else -> BackupScope(enrollment.tenantId,"","viewer")
    }
}

internal class LocalBackups(context: Context, val scope: BackupScope) {
    private val directory = File(context.noBackupFilesDir, "greenhouse-backups/" +
        MessageDigest.getInstance("SHA-256").digest(listOf(scope.tenant,scope.house,scope.kind).joinToString("\u0000").toByteArray())
            .joinToString("") { "%02x".format(it) })

    fun copies(): List<File> = directory.listFiles { file -> file.extension == "ghbackup" }.orEmpty().sortedByDescending { it.name }
    fun save(backup: CustomerBackup) {
        directory.mkdirs()
        val temp = File.createTempFile("writing-", ".tmp", directory)
        try {
            temp.outputStream().use { backup.write(it, scope) }
            val destination = File(directory,"${System.currentTimeMillis()}.ghbackup")
            Files.move(temp.toPath(),destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            copies().drop(3).forEach { it.delete() }
        } finally { temp.delete() }
    }
}

class LocalBackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context,params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val enrollment = SecureSettingsStore(applicationContext).cloudEnrollment()
        val scope = localBackupScope(enrollment) ?: return@withContext Result.success()
        try {
            LocalBackups(applicationContext,scope).save(CustomerBackup(applicationContext,enrollment))
            Result.success()
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { Result.retry() }
    }
    companion object {
        fun schedule(context: Context, enrollment: CloudEnrollment?) {
            if (localBackupScope(enrollment) == null) return
            val request = PeriodicWorkRequestBuilder<LocalBackupWorker>(24,TimeUnit.HOURS).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("greenhouse-daily-local-backup",ExistingPeriodicWorkPolicy.KEEP,request)
        }
    }
}
