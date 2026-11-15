package studio.junecity.greenhouse.cloud

import android.app.NotificationManager
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import studio.junecity.greenhouse.BuildConfig

class ServerUpdateNoticeTest {
    @Test fun updateRequestPostsOneActionableNoticeAndClearsAfterCompletion() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = context.getSystemService(NotificationManager::class.java)
        val target = BuildConfig.VERSION_CODE + 1
        fun notices() = manager.activeNotifications.filter { it.notification.channelId == "server_app_updates" }
        fun waitFor(count: Int) {
            val deadline = SystemClock.elapsedRealtime() + 3000
            while (notices().size != count && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(25)
            assertEquals(count, notices().size)
        }
        try {
            ServerUpdateNotice.sync(context, "fixture", null)
            ServerUpdateNotice.sync(context, "fixture", target)
            waitFor(1)
            val posted = notices().single()
            assertNotNull(posted.notification.contentIntent)
            ServerUpdateNotice.sync(context, "fixture", target)
            assertEquals(posted.postTime, notices().single().postTime)
            ServerUpdateNotice.sync(context, "fixture", BuildConfig.VERSION_CODE)
            waitFor(0)
        } finally {
            ServerUpdateNotice.sync(context, "fixture", null)
        }
    }
}
