package studio.junecity.greenhouse.streaming

import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import kotlinx.coroutines.awaitCancellation
import studio.junecity.greenhouse.ui.LiveViewEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import studio.junecity.greenhouse.cloud.CloudJsonCodec
import studio.junecity.greenhouse.cloud.resolveClientGreenhouse
import androidx.test.rule.GrantPermissionRule
import org.junit.Rule
import studio.junecity.greenhouse.ui.CloudWebRtcViewer
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import studio.junecity.greenhouse.MainActivity
import studio.junecity.greenhouse.ui.TimedLiveView

@RunWith(AndroidJUnit4::class)
class TimedLiveViewInstrumentedTest {
    @get:Rule val cameraPermission = GrantPermissionRule.grant(android.Manifest.permission.CAMERA)

    @Test fun reopenedClientDecodesVideoDespiteLateAnswerAndPendingOldDelete() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        ReopenCameraChannel(context).use { fixture ->
            val liveViews = AtomicInteger()
            val deviceReply = JSONObject("""{"id":"viewer-device","role":"VIEWER_CLIENT","enabled":true,"greenhouseId":null}""")
            // The old Android decoder treated this response as a greenhouse named "null".
            assertEquals("null", deviceReply.optString("greenhouseId"))
            val registered = CloudJsonCodec.device(deviceReply)
            assertEquals(null, registered.greenhouseId)
            val restoredHouse = runBlocking {
                resolveClientGreenhouse(fixture.enrollment.greenhouseId, registered.greenhouseId) {
                    error("The paired greenhouse should survive reopening")
                }
            }
            fun openClient(house: String) = ActivityScenario.launch(MainActivity::class.java).also { scenario ->
                scenario.onActivity { activity ->
                    activity.setContent {
                        TimedLiveView(Modifier.fillMaxSize(), { it.sp }, { live, _ ->
                            if (live) liveViews.incrementAndGet()
                        }) { report ->
                            CloudWebRtcViewer(fixture.enrollment.copy(greenhouseId = house), fixture.api, { it.dp }, { it.sp },
                                Modifier.fillMaxSize(), report, fixture::signalingTransport)
                        }
                    }
                }
            }
            openClient(fixture.enrollment.greenhouseId).use {
                click("Live View · 15s")
                waitFor(25_000, "First viewer received no decoded frame") { liveViews.get() == 1 }
            }
            assertTrue("First view did not release its session", fixture.firstDelete.await(5, java.util.concurrent.TimeUnit.SECONDS))
            openClient(restoredHouse).use {
                click("Live View · 15s")
                waitFor(25_000, "Reopened viewer received no decoded frame") { liveViews.get() == 2 }
                assertEquals(2, fixture.creates.get())
                assertEquals(1, fixture.lateAnswers.get())
                fixture.releaseDelete.countDown()
            }
        }
    }

    @Test fun timerStopContinueAndBackgroundReleaseTheFeed() {
        val starts = AtomicInteger()
        val stops = AtomicInteger()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    TimedLiveView(Modifier.fillMaxSize(), { it.sp }, { _, _ -> }) { report ->
                        LiveViewEffect(Unit) {
                            starts.incrementAndGet()
                            try {
                                report(true, null)
                                awaitCancellation()
                            } finally {
                                stops.incrementAndGet()
                            }
                        }
                    }
                }
            }
            click("Live View · 15s")
            waitFor(20_000) { stops.get() == 1 }
            assertEquals(1, starts.get())
            click("Continue · 15s")
            waitFor { starts.get() == 2 }
            click("Stop ·")
            waitFor { stops.get() == 2 }
            click("Live View · 15s")
            waitFor { starts.get() == 3 }
            scenario.moveToState(Lifecycle.State.CREATED)
            waitFor { stops.get() == 3 }
            scenario.moveToState(Lifecycle.State.RESUMED)
            waitFor { find("Live View · 15s") != null }
            assertEquals(3, starts.get())
            click("Live View · 15s")
            waitFor { starts.get() == 4 }
        }
        assertEquals(4, stops.get())
    }

    private fun click(text: String) {
        waitFor {
            var node = find(text)
            while (node != null && !node.isClickable) node = node.parent
            node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
        }
    }

    private fun find(label: String): AccessibilityNodeInfo? {
        val root = InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow ?: return null
        fun matching(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.text?.startsWith(label) == true) return node
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { child -> matching(child)?.let { return it } }
            }
            return null
        }
        return matching(root)
    }

    private fun waitFor(timeoutMs: Long = 5_000, message: String = "Timed out waiting for Live View", condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + timeoutMs
        while (!condition() && SystemClock.elapsedRealtime() < end) SystemClock.sleep(100)
        // Do not execute a click predicate twice after it has succeeded.
        assertTrue(message, SystemClock.elapsedRealtime() < end)
    }
}
