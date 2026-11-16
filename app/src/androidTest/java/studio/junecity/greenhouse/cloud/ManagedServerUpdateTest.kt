package studio.junecity.greenhouse.cloud

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import studio.junecity.greenhouse.BuildConfig
import studio.junecity.greenhouse.settings.CloudEnrollment
import studio.junecity.greenhouse.settings.SecureSettingsStore
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.buffer
import okio.source
import org.json.JSONObject

class ManagedServerUpdateTest {
    @Test fun refusesReinstallingCurrentBuild() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue(runCatching {
            ManagedServerUpdate.verifyPackage(context, File(context.applicationInfo.sourceDir), BuildConfig.VERSION_CODE)
        }.isFailure)
    }

    // Explicitly run only on a disposable, offline device-owner emulator with a staged higher build.
    // The host checks the new installed version, retained enrollment and monitoring after Android replaces this process.
    @Test fun upgradeDisposableOwner() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("disposableOwner") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(ManagedServerUpdate.enabled(context))
        check(SecureSettingsStore(context).cloudEnrollment() == null)
        val artifact = File(context.cacheDir, "fixture-upgrade.apk")
        val target = BuildConfig.VERSION_CODE + 1
        val enrollment = CloudEnrollment("server-ota-fixture", "house-ota-fixture",
            CloudDeviceRole.GreenhouseServer, "offline-fixture-secret")
        SecureSettingsStore(context).saveCloudEnrollment(enrollment)
        val digest = MessageDigest.getInstance("SHA-256")
        artifact.inputStream().use { input ->
            val buf = ByteArray(65536)
            while (true) {
                val count = input.read(buf)
                if (count < 0) break
                digest.update(buf, 0, count)
            }
        }
        val hash = digest.digest().joinToString("") { "%02x".format(it) }
        var permitCount = 0
        var downloads = 0
        val transport = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val body = when (request.url.host) {
                "api.example.test" -> {
                    check(request.url.encodedPath == "/v1/device/devices/${enrollment.deviceId}/update-package")
                    check(request.header("x-greenhouse-device-secret") == enrollment.deviceSecret)
                    permitCount++
                    JSONObject().put("versionCode", target).put("sha256", hash).put("bytes", artifact.length())
                        .put("url", "https://release.example.test/android/$target.apk").toString().toResponseBody()
                }
                "release.example.test" -> {
                    check(request.header("x-greenhouse-device-secret") == null && request.header("Authorization") == null)
                    downloads++
                    artifact.source().buffer().asResponseBody(contentLength = artifact.length())
                }
                else -> error("Unexpected request")
            }
            Response.Builder().request(request).code(200).message("OK").protocol(Protocol.HTTP_1_1).body(body).build()
        }.build()
        val gateway = AuthenticatedCloudGateway(CloudApiConfig("https://api.example.test", enrollment.deviceId),
            deviceSecret = enrollment.deviceSecret, client = transport)
        runBlocking { ManagedServerUpdate.install(context, gateway, enrollment, target, transport) }
        check(permitCount == 2 && downloads == 1)
    }
}
