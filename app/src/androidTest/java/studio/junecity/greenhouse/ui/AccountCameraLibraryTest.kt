package studio.junecity.greenhouse.ui

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import studio.junecity.greenhouse.MainActivity
import studio.junecity.greenhouse.cloud.*
import studio.junecity.greenhouse.settings.*
import java.util.concurrent.CopyOnWriteArrayList

class AccountCameraLibraryTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun recordAllTargetsReadyCamerasAndKeepsTheirResultsSeparate() {
        val secure = SecureSettingsStore(compose.activity)
        val saved = secure.cloudEnrollment()
        val owner = CloudEnrollment("fixture-viewer", "north", CloudDeviceRole.ViewerClient, tenantId = "qa-cameras")
        val requested = CopyOnWriteArrayList<String>()
        val list = listOf("north", "south", "annex")
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val segments = request.url.pathSegments
            val house = segments.getOrNull(2)
            val success = !(segments.last() == "record" && house == "south")
            val payload = when (segments.last()) {
                "greenhouse-plan" -> JSONObject().put("extraSlots", 2).put("maxExtraSlots", 10)
                    .put("houses", JSONArray(list.map { JSONObject().put("id", it).put("name", it.uppercase())
                        .put("active", true).put("free", it == "north").put("serverGreenhouseId", it) }))
                "access" -> JSONObject().put("remoteRecordingAvailable", house != "annex")
                    .put("remoteRecordingReason", if (house == "annex") "offline" else "ready")
                "record" -> {
                    requested.add(requireNotNull(house))
                    if (success) JSONObject().put("status", "uploaded") else JSONObject().put("error", "server_busy")
                }
                else -> error("Unexpected route")
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).message("fixture")
                .code(if (success) 200 else 409).body(payload.toString().toResponseBody()).build()
        }.build()
        val api = AuthenticatedCloudGateway(CloudApiConfig("https://fixture.invalid", owner.deviceId), {"test"}, client=http)
        try {
            secure.saveCloudEnrollment(owner)
            compose.activityRule.scenario.onActivity { it.setContent {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    AccountCameraLibrary(api, "north", "North") { house, _ -> Text("Archive for $house") }
                }
            } }
            compose.waitUntil(12_000) { compose.onAllNodesWithText("Record all ready cameras").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Server offline").assertExists()
            compose.onNodeWithText("Record all ready cameras").performScrollTo().performClick()
            compose.waitUntil(12_000) { compose.onAllNodesWithText("Saved to cloud playback").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Server busy or unavailable. Refresh camera status.").assertExists()
            assertEquals(listOf("north", "south"), requested.sorted())
            compose.onAllNodesWithText("View clips")[0].performScrollTo().performClick()
            compose.onNodeWithText("Archive for south").assertExists()
        } finally {
            if (saved == null) secure.clearCloudEnrollment() else secure.saveCloudEnrollment(saved)
        }
    }
}
