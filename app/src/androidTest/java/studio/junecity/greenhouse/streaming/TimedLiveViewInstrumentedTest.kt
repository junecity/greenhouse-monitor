package studio.junecity.greenhouse.streaming

import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import kotlinx.coroutines.awaitCancellation
import studio.junecity.greenhouse.ui.LiveViewEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp
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

    private fun waitFor(timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + timeoutMs
        while (!condition() && SystemClock.elapsedRealtime() < end) SystemClock.sleep(100)
        // Do not execute a click predicate twice after it has succeeded.
        assertTrue("Timed out waiting for Live View", SystemClock.elapsedRealtime() < end)
    }
}
