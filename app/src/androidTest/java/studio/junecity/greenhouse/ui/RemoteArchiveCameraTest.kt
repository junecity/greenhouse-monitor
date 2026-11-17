package studio.junecity.greenhouse.ui

import android.media.MediaMetadataRetriever
import android.net.ConnectivityManager
import android.os.Build
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.buffer
import okio.sink
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import studio.junecity.greenhouse.MainActivity
import studio.junecity.greenhouse.cloud.*
import studio.junecity.greenhouse.settings.CloudEnrollment
import studio.junecity.greenhouse.settings.SecureSettingsStore

/** Run on a disposable, offline emulator with an emulated front lens and runtime camera permission. */
class RemoteArchiveCameraTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val owner = CloudEnrollment("server-fixture", "house-fixture", CloudDeviceRole.GreenhouseServer, "synthetic-secret")
    private val id = UUID.randomUUID().toString()

    @Test fun cameraAtHomeCreatesPlayableMinuteAndRetriesSameUpload() = runBlocking {
        exercise(revoke = false)
    }

    @Test fun canceledLeaseStopsCaptureAndUploadsNothing() = runBlocking {
        exercise(revoke = true)
    }

    private suspend fun exercise(revoke: Boolean) {
        val store = SecureSettingsStore(context)
        assumeTrue("Disposable emulator only", Build.HARDWARE in setOf("ranchu", "goldfish") && store.cloudEnrollment() == null)
        assumeTrue("Disconnect emulator Internet for the test enrollment", context.getSystemService(ConnectivityManager::class.java).activeNetwork == null)
        val mp4 = File.createTempFile("archive-camera-test", ".mp4", context.cacheDir)
        val uploads = mutableListOf<String>()
        var finished: String? = null
        var checks = 0
        var claims = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val response = JSONObject()
            if (request.method == "PUT") {
                if (uploads.size == 1) throw IOException("Interrupted test upload")
                mp4.sink().buffer().use { request.body!!.writeTo(it) }
            } else {
                val body = JSONObject(Buffer().also { request.body!!.writeTo(it) }.readUtf8())
                val action = request.url.pathSegments.last()
                when (action) {
                    "poll-record" -> { claims++; response.put("requestId", id) }
                    "record-status" -> {
                        assertEquals(id, body.getString("requestId"))
                        checks++
                        response.put("requestId", id).put("status", if (revoke && checks >= 4) "canceled" else "recording")
                    }
                    "upload" -> { uploads += body.getString("requestId"); response.put("url", "https://storage.invalid/clip") }
                    "finish-record" -> { assertEquals(id, body.getString("requestId")); finished = body.getString("status") }
                    else -> error("Unexpected endpoint $action")
                }
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(response.toString().toResponseBody()).build()
        }.build()
        val api = AuthenticatedCloudGateway(CloudApiConfig("https://cloud.invalid", owner.deviceId),
            deviceSecret=owner.deviceSecret, client=client)
        val scene = ActivityScenario.launch(MainActivity::class.java)
        try {
            scene.onActivity {
                store.saveCloudEnrollment(owner)
                CloudCameraServerService.sync(it, true)
            }
            withTimeout(12_000) { while (!CloudCameraServerService.isRecordingReady(owner)) delay(100) }
            android.os.ParcelFileDescriptor.AutoCloseInputStream(
                instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_HOME")
            ).use { it.readBytes() }
            delay(800)
            ServerArchiveRecorder(context, owner, api) {}.process(true)
            if (revoke) {
                assertEquals("failed", finished)
                assertTrue(uploads.isEmpty())
            } else {
                assertNull(finished)
                // Simulate recreation after a failed PUT. Capture and quota reservation remain idempotent.
                ServerArchiveRecorder(context, owner, api) {}.process(false)
                assertEquals("uploaded", finished)
                assertEquals(listOf(id, id), uploads)
                val decoder = MediaMetadataRetriever()
                try {
                    decoder.setDataSource(mp4.path)
                    assertTrue(decoder.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong() in 58_000L..62_000L)
                    assertNull(decoder.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO))
                    assertNotNull(decoder.getFrameAtTime(30_000_000, MediaMetadataRetriever.OPTION_CLOSEST))
                } finally { decoder.release() }
            }
            assertEquals(1, claims)
            assertTrue(checks >= 4)
        } finally {
            store.clearCloudEnrollment()
            CloudCameraServerService.sync(context, false)
            CloudHeartbeatService.sync(context, false)
            scene.close()
            mp4.delete()
        }
    }

}
