package studio.junecity.greenhouse.ui

import android.content.pm.ActivityInfo
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.runBlocking
import com.android.billingclient.api.Purchase
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import studio.junecity.greenhouse.MainActivity
import studio.junecity.greenhouse.camera.CameraMovies
import studio.junecity.greenhouse.cloud.AuthenticatedCloudGateway
import studio.junecity.greenhouse.cloud.CloudApiConfig
import studio.junecity.greenhouse.cloud.purchaseFor
import studio.junecity.greenhouse.model.GreenhouseUiState
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

class CloudArchiveCheckoutTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private fun gateway(reply: (Request) -> String): AuthenticatedCloudGateway {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val body = reply(request)
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body(body.toResponseBody()).build()
        }.build()
        return AuthenticatedCloudGateway(CloudApiConfig("https://test.invalid", "viewer-test"),
            { "fixture" }, client = client)
    }

    @Test fun cloudLibraryOffersStorageAndRecognizesAccessGrantedOnAnotherPhone() {
        var subscribed = false
        val routes = CopyOnWriteArrayList<String>()
        val api = gateway { request ->
            val path = request.url.encodedPath
            routes.add(path)
            when {
                path.endsWith("/recordings/access") -> JSONObject().put("active", subscribed).put("checkout",
                    JSONObject().put("productId", "camera_storage_monthly").put("basePlanId", "monthly")
                        .put("accountBinding", "customer")).toString()
                path.endsWith("/recordings/list") -> """{"clips":[]}"""
                else -> error("Unexpected route $path")
            }
        }
        val root = File(compose.activity.cacheDir, UUID.randomUUID().toString())
        val library = CameraMovies(root, "viewer", "house")
        try {
            compose.activityRule.scenario.onActivity { activity ->
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                activity.setContent {
                    CameraWorkspace(GreenhouseUiState(), library, "house", api, { (it * 0.55f).dp },
                        { (it * 0.7f).sp }, {}, server = false)
                }
            }
            compose.onNodeWithText("Playback").performClick()
            compose.onNodeWithText("Cloud library").performClick()
            compose.waitUntil(8_000) { compose.onAllNodesWithText("View subscription").fetchSemanticsNodes().isNotEmpty() }
            assertFalse(routes.any { it.endsWith("/recordings/list") })
            compose.runOnIdle { subscribed = true }
            compose.onNodeWithText("Check access").performClick()
            compose.waitUntil(8_000) { compose.onAllNodesWithText("No cloud recordings in the last seven days.").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("View subscription").assertDoesNotExist()
            compose.onNodeWithText("Camera settings").performClick()
            compose.waitUntil(8_000) { compose.onAllNodesWithText("Manage subscription").fetchSemanticsNodes().isNotEmpty() }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun restoreSelectsCompletedStoragePurchaseForSignedInGreenhouseAccount() {
        fun purchase(product: String, binding: String, state: Int, token: String) = Purchase(
            JSONObject().put("productId", product).put("obfuscatedAccountId", binding)
                .put("purchaseState", state).put("purchaseToken", token).toString(), "test")
        val product = "camera_storage_monthly"
        val pending = purchase(product, "greenhouse-owner", 4, "pending")
        val other = purchase(product, "other-person", 1, "other")
        val live = purchase("premium_monthly", "greenhouse-owner", 1, "streaming")
        val completed = purchase(product, "greenhouse-owner", 1, "completed")
        val owned = listOf(other, live, pending)
        assertEquals(Purchase.PurchaseState.PENDING, owned.purchaseFor(product, "greenhouse-owner")?.purchaseState)
        assertNull(owned.purchaseFor(product, "unknown"))
        assertEquals("completed", (owned + completed).purchaseFor(product, "greenhouse-owner")?.purchaseToken)
    }

    @Test fun retryAfterLostUploadResponseReusesClipAndSkipsDuplicateTransfer() {
        val id = UUID.randomUUID()
        val file = File(compose.activity.cacheDir, "1234-Cloud-$id.mp4")
        val approvals = mutableListOf<JSONObject>()
        var uploads = 0
        val api = gateway { request ->
            if (request.method == "PUT") {
                uploads++
                val saved = Buffer().also { request.body!!.writeTo(it) }.readByteArray()
                assertArrayEquals(file.readBytes(), saved)
                assertEquals(approvals.last().getString("md5"), request.header("Content-MD5"))
                throw IOException("Simulated loss of the response after storage accepted the upload")
            }
            assertTrue(request.url.encodedPath.endsWith("/recordings/upload"))
            approvals.add(JSONObject(Buffer().also { request.body!!.writeTo(it) }.readUtf8()))
            if (uploads == 0) """{"url":"https://storage.invalid/clip"}""" else """{"alreadyUploaded":true}"""
        }
        try {
            file.writeBytes(byteArrayOf(1, 2, 3, 4))
            runBlocking {
                try { api.uploadMovie("house", file); fail("Expected a lost response") } catch (_: IOException) {}
                api.uploadMovie("house", file)
            }
            assertEquals(1, uploads)
            assertEquals(listOf(id.toString(), id.toString()), approvals.map { it.getString("requestId") })
            assertEquals(listOf(file.length(), file.length()), approvals.map { it.getLong("bytes") })
            assertEquals(1, approvals.map { it.getString("md5") }.distinct().size)
        } finally { file.delete() }
    }
}
