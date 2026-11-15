package studio.junecity.greenhouse.ui

import android.content.pm.ActivityInfo
import android.media.MediaMetadataRetriever
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.rule.GrantPermissionRule
import java.io.File
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import studio.junecity.greenhouse.MainActivity
import studio.junecity.greenhouse.camera.CameraMovies
import studio.junecity.greenhouse.model.GreenhouseUiState

class CameraLibraryTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val permission = GrantPermissionRule.grant(android.Manifest.permission.CAMERA)

    private fun tap(label: String) {
        compose.waitUntil(12000) {
            compose.onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(label).performClick()
    }

    @Test
    fun cameraToLocalLibrary() {
        val root = File(compose.activity.cacheDir, UUID.randomUUID().toString())
        val movies = CameraMovies(root, "test-owner", "test-house")
        try {
            compose.activityRule.scenario.onActivity { activity ->
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                activity.setContent {
                    CameraWorkspace(
                        GreenhouseUiState(),
                        movies,
                        "test-house",
                        null,
                        { (it * 0.55f).dp },
                        { (it * 0.7f).sp },
                        {},
                    )
                }
            }
            tap("Save short clip")
            SystemClock.sleep(2500)
            tap("Finish")
            compose.waitUntil(10000) { movies.library().size == 1 }
            tap("Timelapse")
            tap("Start timelapse")
            SystemClock.sleep(6500)
            tap("Finish")
            compose.waitUntil(12000) { movies.library().size == 2 }
            assertTrue(CameraMovies(root, "other-owner", "test-house").library().isEmpty())
            assertTrue(CameraMovies(root, "test-owner", "other-house").library().isEmpty())
            for (file in CameraMovies(root, "test-owner", "test-house").library()) {
                val decoder = MediaMetadataRetriever()
                try {
                    decoder.setDataSource(file.path)
                    assertTrue(decoder.frameAtTime != null)
                    val duration =
                        decoder
                            .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!
                            .toInt()
                    assertTrue(
                        "Unexpected movie duration $duration",
                        duration > 0 && (file.name.contains("-Clip-") || duration < 3000),
                    )
                } finally {
                    decoder.release()
                }
            }
            tap("Playback")
            compose.onAllNodesWithText("Play")[0].performClick()
            tap("Close")
            compose.onAllNodesWithText("Delete")[0].performClick()
            tap("Keep")
            assertEquals(2, movies.library().size)
            compose.onAllNodesWithText("Delete")[0].performClick()
            compose.onNode(hasText("Delete") and hasAnyAncestor(isDialog())).performClick()
            compose.waitUntil { movies.library().size == 1 }
        } finally {
            root.deleteRecursively()
        }
    }
}
