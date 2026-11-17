package studio.junecity.greenhouse.ui

import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import studio.junecity.greenhouse.MainActivity
import studio.junecity.greenhouse.R
import studio.junecity.greenhouse.data.ClimateBucket
import studio.junecity.greenhouse.widget.GreenhouseWidgetUiState
import studio.junecity.greenhouse.widget.OutdoorStatus
import studio.junecity.greenhouse.widget.WidgetFeedStatus
import studio.junecity.greenhouse.widget.widgetSparkline
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

class MonitoringCardTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val snapshotTime = LocalDate.of(2026, 11, 16).atTime(19, 17).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    private val demo = GreenhouseWidgetUiState(
        title = "Main Greenhouse", status = WidgetFeedStatus.Live, temperatureF = 61.0, humidity = 84.0,
        measuredAt = snapshotTime, now = snapshotTime, slopeFPerHour = -1.2,
        outdoorF = 28.0, outdoorAt = snapshotTime, outdoorFreshness = OutdoorStatus.Current,
        targetF = 60.0, dewPointF = 56.0, targetStatus = "Above target",
        trend = (0..72).map { index ->
            val t = index / 72.0
            // Known monotone readings for checking line width, endpoint placement and presentation.
            ClimateBucket(snapshotTime - (72 - index) * 300_000L, 69 - 8 * (t * t * (3 - 2 * t)), 80 + 4 * t)
        }
    )
    private val state = mutableStateOf(demo)
    private var refreshes = 0
    private var closes = 0

    private fun show(width: Int, height: Int, scale: Float = 1f) {
        compose.activityRule.scenario.onActivity { activity ->
            activity.requestedOrientation = if (width >= 620) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            activity.setContent {
                val density = LocalDensity.current.density
                CompositionLocalProvider(LocalDensity provides Density(density, scale)) {
                    Box(Modifier.fillMaxSize().background(colorResource(R.color.climate_canvas)), contentAlignment = Alignment.Center) {
                        Box(Modifier.width(width.dp).heightIn(max = height.dp).testTag("reference")
                            .background(colorResource(R.color.climate_canvas)).padding(4.dp)) {
                            MonitoringCard(state.value, { refreshes++ }, { closes++ }, Modifier.fillMaxWidth())
                        }
                    }
                }
            }
        }
    }

    private fun screenshot(variant: String) {
        compose.onNodeWithTag("monitor-$variant").assertIsDisplayed()
        compose.onNodeWithText("61°F", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("84%", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("LIVE", useUnmergedTree = true).assertIsDisplayed()
        val bitmap = compose.onNodeWithTag("reference").captureToImage().asAndroidBitmap()
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(ctx.getExternalFilesDir(null), "monitoring-card-qa").apply { mkdirs() }
        File(directory, "dashboard-$variant.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }

    @Test fun dialogWindowAndGraphStayInPlaceAsTheFeedLoadsAndRecovers() {
        state.value = GreenhouseWidgetUiState(statusMessage = "Waiting for readings")
        compose.activityRule.scenario.onActivity { activity ->
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            activity.setContent { MonitoringCardDialog(state.value, { refreshes++ }, {}) }
        }
        compose.waitUntil(8_000) { compose.onAllNodesWithTag("monitor-wide").fetchSemanticsNodes().isNotEmpty() }
        val panel = SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "Greenhouse readings")
        fun panelBounds() = compose.onNode(panel).fetchSemanticsNode().let {
            it.positionOnScreen to it.size
        }
        fun position(label: String) = compose.onNodeWithText(label, useUnmergedTree = true).fetchSemanticsNode().positionOnScreen
        val bounds = panelBounds()
        val temperature = position("Temperature")
        val trend = position("Temperature · 6h")
        val delayed = demo.copy(status = WidgetFeedStatus.Delayed, statusMessage = "Readings delayed",
            slopeFPerHour = null, outdoorF = null, outdoorFreshness = OutdoorStatus.Unavailable, targetStatus = null)
        for (update in listOf(demo, delayed, demo.copy(temperatureF = 73.0))) {
            compose.runOnIdle { state.value = update }
            compose.onNodeWithText(update.status.label).assertIsDisplayed()
            assertEquals("Modal moved on ${update.status}", bounds, panelBounds())
            assertEquals("Reading moved on ${update.status}", temperature, position("Temperature"))
            assertEquals("Chart moved on ${update.status}", trend, position("Temperature · 6h"))
        }
    }

    @Test fun compactCardOmitsGraphAndShowsReadingTime() {
        show(320, 230)
        compose.onNodeWithTag("monitor-chart").assertDoesNotExist()
        compose.onNodeWithText(demo.updatedText).assertIsDisplayed()
        screenshot("compact")
    }

    @Test fun standardCardMatchesRealValuesAndShowsSecondaryReadings() {
        show(382, 528)
        compose.onNodeWithText("DEW POINT", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("monitor-chart").assertIsDisplayed()
        screenshot("standard")
    }

    @Test fun wideCardShowsHistoryBesideReadings() {
        show(780, 350)
        // Activity orientation is applied asynchronously by WindowManager on real devices.
        compose.waitUntil(8_000) { compose.onAllNodesWithTag("monitor-wide").fetchSemanticsNodes().isNotEmpty() }
        val metrics = compose.onNodeWithText("Temperature", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val chart = compose.onNodeWithTag("monitor-chart").getUnclippedBoundsInRoot()
        assertTrue("Wide history belongs to the right of the metrics", chart.left > metrics.right)
        screenshot("wide")
    }

    @Test fun enlargedTextKeepsStatusControlsAndMissingHistoryAccessible() {
        state.value = demo.copy(status = WidgetFeedStatus.Offline, statusMessage = "Sensor feed offline",
            trend = emptyList(), measuredAt = snapshotTime - 3_600_000, dewPointF = null, targetStatus = null)
        show(372, 536, scale = 1.5f)
        compose.onNodeWithText("OFFLINE").assertIsDisplayed()
        compose.onNodeWithText("LIVE").assertDoesNotExist()
        compose.onNodeWithText("Retry").performScrollTo().performClick()
        compose.onNodeWithText("Temperature · 6h").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("monitor-chart").assertDoesNotExist()
        compose.onNodeWithText(state.value.updatedText).performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Close live readings").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(1, refreshes)
            assertEquals(1, closes)
        }
    }

    @Test fun sixHourCurveLeavesMissingIntervalsUnpaintedAndMarksLatestPoint() {
        // A four-hour interruption is easily visible between two blocks of genuine samples.
        state.value = demo.copy(trend = demo.trend.filterIndexed { i, _ -> i in 0..10 || i in 61..72 })
        show(382, 528)
        val graph = compose.onNodeWithTag("monitor-chart").captureToImage().asAndroidBitmap()
        fun amberPixels(left: Int, right: Int): Int {
            var count = 0
            for (x in left until right) for (y in 0 until graph.height) {
                val p = graph.getPixel(x, y)
                if (android.graphics.Color.red(p) > 200 && android.graphics.Color.green(p) in 120..210 &&
                    android.graphics.Color.blue(p) < 130) count++
            }
            return count
        }
        assertTrue("First history segment visible", amberPixels(0, graph.width / 5) > 5)
        assertEquals("Missing history is never joined", 0, amberPixels(graph.width / 3, graph.width * 2 / 3))
        assertTrue("Latest segment visible", amberPixels(graph.width * 4 / 5, graph.width) > 5)
    }

    @Test fun detailsAndWidgetEndAtTheObservationAndBreakOnSensorLoss() {
        val contributors = setOf("roof", "bench")
        val last = snapshotTime - 2 * 3_600_000L
        val samples = listOf(ClimateBucket(last - 600_000, 66.0, 84.0, contributors),
            ClimateBucket(last - 300_000, 66.0, 84.0, contributors),
            ClimateBucket(last, 58.0, 77.0, setOf("bench")))
        state.value = demo.copy(trend = samples, status = WidgetFeedStatus.Delayed, measuredAt = last)
        show(382, 528)
        compose.onNodeWithText("Latest 58°F", useUnmergedTree = true).assertIsDisplayed()
        assertEquals("Latest 77%", state.value.trendEndpoints { "${it.humidity.toInt()}%" })
        fun rendered(): List<Bitmap> = listOf(
            compose.onNodeWithTag("monitor-chart").captureToImage().asAndroidBitmap(),
            widgetSparkline(state.value.trend, snapshotTime, 640, 160, android.graphics.Color.rgb(255, 173, 54)) { it.temperatureF },
            widgetSparkline(state.value.trend, snapshotTime, 640, 160, android.graphics.Color.rgb(84, 213, 245)) { it.humidity }
        )
        fun hasTrace(graph: Bitmap, left: Float, right: Float, top: Float = 0f, bottom: Float = 1f): Boolean {
            for (x in (graph.width * left).toInt() until (graph.width * right).toInt()) {
                for (y in (graph.height * top).toInt() until (graph.height * bottom).toInt()) {
                    val pixel = graph.getPixel(x, y)
                    val amber = android.graphics.Color.red(pixel) > 200 && android.graphics.Color.blue(pixel) < 130
                    val cyan = android.graphics.Color.red(pixel) < 130 && android.graphics.Color.blue(pixel) > 200
                    if (android.graphics.Color.alpha(pixel) > 190 && (amber || cyan)) return true
                }
            }
            return false
        }
        for (graph in rendered()) {
            assertTrue("Show the last observed point at its time", hasTrace(graph, .62f, .71f, .75f))
            assertTrue("Leave the two-hour stale tail empty", !hasTrace(graph, .72f, 1f))
            assertTrue("A change in sensor coverage must break the stroke", !hasTrace(graph, 0f, 1f, .35f, .65f))
        }
        // The same temperatures from the same sensors represent a genuine change.
        compose.runOnIdle {
            state.value = state.value.copy(trend = samples.dropLast(1) + ClimateBucket(last, 58.0, 77.0, contributors))
        }
        compose.onNodeWithText("66°F → 58°F", useUnmergedTree = true).assertIsDisplayed()
        for (graph in rendered()) assertTrue("Keep a real change visible", hasTrace(graph, .60f, .71f, .35f, .65f))
    }
}
