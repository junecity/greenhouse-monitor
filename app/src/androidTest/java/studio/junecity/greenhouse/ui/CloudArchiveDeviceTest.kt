package studio.junecity.greenhouse.ui

import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.os.Bundle
import android.util.Base64
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.util.UUID
import java.security.cert.CertificateFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import studio.junecity.greenhouse.camera.CameraMovies
import studio.junecity.greenhouse.camera.MovieKind
import studio.junecity.greenhouse.cloud.*

/** Opt-in two-device test. The fixture uses a loopback-only certificate and synthetic accounts. */
class CloudArchiveDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val args = InstrumentationRegistry.getArguments()
    private val app = instrumentation.targetContext
    private val house = "archive-qa-house"

    private fun connection(server: Boolean): Pair<AuthenticatedCloudGateway, OkHttpClient> {
        val port = args.getString("archiveFixturePort")?.toIntOrNull()
        assumeTrue("Requires a running local archive fixture", port != null && port in 1024..65535)
        val certificate = CertificateFactory.getInstance("X.509").generateCertificate(
            Base64.decode(args.getString("archiveFixtureCert"), Base64.DEFAULT).inputStream()
        )
        val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null)
            setCertificateEntry("loopback", certificate)
        }
        val manager = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
        val tls = SSLContext.getInstance("TLS").apply { init(null, manager.trustManagers, null) }
        val client = OkHttpClient.Builder()
            .sslSocketFactory(tls.socketFactory, manager.trustManagers.single() as X509TrustManager).build()
        val config = CloudApiConfig("https://localhost:$port", if (server) "fixture-server" else "fixture-viewer")
        val api = if (server) AuthenticatedCloudGateway(config, deviceSecret = "local-fixture-server-credential-for-qa", client = client)
            else AuthenticatedCloudGateway(config, { "local-viewer" }, client = client)
        return api to client
    }

    private suspend fun clips(api: AuthenticatedCloudGateway): Set<String> {
        val rows = api.archiveRequest(house, "list").getJSONArray("clips")
        return (0 until rows.length()).map { rows.getJSONObject(it).getString("id") }.toSet()
    }

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test fun authorizeViewerWithFixturePurchase() = runBlocking {
        val (api, _) = connection(false)
        val status = api.archiveRequest(house, "access")
        assertFalse(status.getBoolean("active"))
        assertEquals("camera_storage_monthly", status.getJSONObject("checkout").getString("productId"))
        assertTrue(api.archiveRequest(house, "verify", JSONObject().put("purchaseToken", "local-play-test-purchase")).getBoolean("active"))
    }

    @Test fun uploadOneMinuteOnServer() = runBlocking {
        val (api, _) = connection(true)
        assertTrue(api.archiveRequest(house, "access").getBoolean("active"))
        val before = clips(api)
        val root = File(app.cacheDir, "archive-record-${UUID.randomUUID()}")
        try {
            val movies = CameraMovies(root, "archive-smoke", house)
            var i = 0
            val movie = movies.record(MovieKind.Cloud, {
                Bitmap.createBitmap(80, 48, Bitmap.Config.ARGB_8888).apply {
                    eraseColor(Color.rgb(i++ % 256, 100, 160))
                }
            }, {}, { false })
            api.uploadMovie(house, movie)
            api.uploadMovie(house, movie)
            val id = (clips(api) - before).single()
            instrumentation.sendStatus(0, Bundle().apply {
                putString("archiveClip", id)
                putString("archiveSha", sha(movie.readBytes()))
            })
        } finally { root.deleteRecursively() }
        Unit
    }

    @Test fun playAndDeleteOnViewer() = runBlocking {
        val (api, client) = connection(false)
        val id = requireNotNull(args.getString("archiveClip"))
        val expectedSha = requireNotNull(args.getString("archiveSha"))
        assertTrue(id in clips(api))
        val request = JSONObject().put("id", id)
        val url = api.archiveRequest(house, "play", request).getString("url")
        val temp = File.createTempFile("archive-play-", ".mp4", app.cacheDir)
        try {
            client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                assertTrue(response.isSuccessful)
                response.body!!.byteStream().use { input -> temp.outputStream().use { input.copyTo(it) } }
            }
            assertEquals(expectedSha, sha(temp.readBytes()))
            val decoder = MediaMetadataRetriever()
            try {
                decoder.setDataSource(temp.path)
                assertTrue(decoder.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong() in 58_000L..62_000L)
                assertNotNull(decoder.getFrameAtTime(30_000_000L, MediaMetadataRetriever.OPTION_CLOSEST))
            } finally { decoder.release() }
            assertTrue(api.archiveRequest(house, "delete", request).getBoolean("deleted"))
            assertFalse(id in clips(api))
        } finally { temp.delete() }
        Unit
    }
}
