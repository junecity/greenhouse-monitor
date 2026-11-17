package studio.junecity.greenhouse.cloud

import android.content.Context
import android.os.SystemClock
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import org.json.JSONObject
import studio.junecity.greenhouse.camera.CameraMovies
import studio.junecity.greenhouse.camera.recordServerArchiveClip
import studio.junecity.greenhouse.settings.CloudEnrollment
import studio.junecity.greenhouse.settings.SecureSettingsStore
import studio.junecity.greenhouse.streaming.CloudCameraLease

/** One claimed archive order per paired camera. Only a newly claimed order can start physical capture. */
internal class ServerArchiveRecorder(
    private val context: Context,
    private val owner: CloudEnrollment,
    private val api: AuthenticatedCloudGateway,
    private val notice: (String) -> Unit,
) {
    private val secure = SecureSettingsStore(context)
    private val state = context.getSharedPreferences("remote_clip_orders", Context.MODE_PRIVATE)
    private val scope = "${owner.tenantId}/${owner.deviceId}/${owner.greenhouseId}"
    private val orderKey = UUID.nameUUIDFromBytes(scope.toByteArray()).toString()
    private val movies = CameraMovies(File(context.cacheDir, "remote-clips"), scope, owner.greenhouseId)
    private var nextRetryAt = 0L
    val retryDue: Boolean get() = state.contains(orderKey) && SystemClock.elapsedRealtime() >= nextRetryAt

    suspend fun process(signal: Boolean) {
        val saved = state.getString(orderKey, null)
        if (saved == null && !signal || !sameOwner()) return
        val raw = saved ?: api.archiveRequest(owner.greenhouseId, "poll-record", JSONObject())
            .optString("requestId").takeIf { it.isNotBlank() } ?: return
        val id = UUID.fromString(raw)
        require(id.toString() == raw)
        if (saved == null) check(state.edit().putString(orderKey, raw)
            .putLong(orderKey + ".started", System.currentTimeMillis()).commit())
        val args = JSONObject().put("requestId", raw)
        var file = movies.library().firstOrNull { it.name.endsWith("-Cloud-$id.mp4") }
        var success = false
        try {
            val status = api.archiveRequest(owner.greenhouseId, "record-status", args)
            if (status.optString("status") != "recording" || status.optString("requestId") != raw) {
                forget(file)
                return
            }
            // A killed process may retry an existing MP4. Never silently restart a partial take.
            if (file == null && saved == null) {
                CloudCameraLease.setRemoteActive(true)
                try {
                    notice("Recording a one-minute cloud clip")
                    delay(500) // Let the local preview release its CameraX use cases.
                    var nextCheck = 0L
                    file = recordServerArchiveClip(context, movies, id) {
                        val now = SystemClock.elapsedRealtime()
                        if (now >= nextCheck) {
                            checkAuthorized(args)
                            nextCheck = now + 8_000
                        }
                    }
                } finally {
                    CloudCameraLease.setRemoteActive(false)
                }
            }
            val completed = checkNotNull(file) { "Recording was interrupted" }
            notice("Uploading the cloud clip")
            checkAuthorized(args)
            api.uploadMovie(owner.greenhouseId, completed)
            success = true
            check(sameOwner())
            api.archiveRequest(owner.greenhouseId, "finish-record", args.put("status", "uploaded"))
            forget(file)
        } catch (cancel: CancellationException) {
            if (cancel !is TimeoutCancellationException) throw cancel
            fail(args, file)
        } catch (e: Exception) {
            android.util.Log.w("GreenhouseCamera", "Remote recording could not finish", e)
            // On an ambiguous PUT or finish reply, use the same bytes and reservation ID on retry.
            val window = System.currentTimeMillis() - state.getLong(orderKey + ".started", 0)
            if (window in 0..600_000 && (e is IOException || success ||
                    e is CloudApiException && (e.statusCode == 429 || e.statusCode >= 500))) {
                nextRetryAt = SystemClock.elapsedRealtime() + 15_000
            } else fail(args, file)
        } finally { notice("Waiting for an authorized viewer") }
    }

    private suspend fun checkAuthorized(args: JSONObject) {
        check(sameOwner())
        val reply = api.archiveRequest(owner.greenhouseId, "record-status", args)
        check(reply.optString("requestId") == args.getString("requestId") && reply.optString("status") == "recording")
    }

    private suspend fun fail(args: JSONObject, file: File?) {
        if (sameOwner()) {
            try { api.archiveRequest(owner.greenhouseId, "finish-record", args.put("status", "failed")) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* The claim also expires on the server. */ }
        }
        forget(file)
    }

    private fun sameOwner() = secure.cloudEnrollment() == owner

    private fun forget(file: File?) {
        file?.let(movies::delete)
        state.edit().remove(orderKey).remove(orderKey + ".started").apply()
    }
}
