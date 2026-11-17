package studio.junecity.greenhouse.cloud

import android.app.Notification
import android.app.NotificationManager
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import studio.junecity.greenhouse.settings.CloudEnrollment
import studio.junecity.greenhouse.settings.GreenhousePlanCache
import studio.junecity.greenhouse.settings.SecureSettingsStore

class PlanPauseNoticeTest {
    @Test fun cachedVerifiedPausesWarnOfflineAndClearAfterResumeOrAccountSwitch() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val secure = SecureSettingsStore(context)
        val previous = secure.cloudEnrollment()
        val manager = context.getSystemService(NotificationManager::class.java)
        val owner = CloudEnrollment("device-" + UUID.randomUUID(), "greenhouse", CloudDeviceRole.ViewerClient,
            tenantId = "tenant-" + UUID.randomUUID())
        val now = System.currentTimeMillis()
        val end = now + 3_600_000
        val plan = GreenhousePlanState(
            listOf(PlanHouse("house-a", "Orchid house", true, false, end),
                PlanHouse("house-b", "Tomatoes", true, false, now + 172_800_000)),
            2, "greenhouse_extra_2", end, "binding", true, false, 10, revision = 1)
        fun titles() = manager.activeNotifications.filter { it.tag?.startsWith("plan/") == true }
            .map { it.notification.extras.getString(Notification.EXTRA_TITLE) }
        fun expect(expected: List<String>) {
            repeat(100) {
                if (titles() == expected) return
                SystemClock.sleep(25)
            }
            assertEquals(expected, titles())
        }
        try {
            secure.saveCloudEnrollment(owner)
            val cache = GreenhousePlanCache(context, owner)
            cache.record(plan)
            GreenhousePlanWorker.showScheduledPauses(context, owner)
            expect(listOf("Orchid house"))
            val resumed = plan.copy(houses = plan.houses.map { it.copy(pauseAtEpochMs = 0) }, revision = 2)
            cache.record(resumed)
            cache.record(plan) // A delayed older result cannot reinstate the warning.
            GreenhousePlanWorker.showScheduledPauses(context, owner)
            expect(emptyList())
            cache.record(plan.copy(revision = 3))
            GreenhousePlanWorker.showScheduledPauses(context, owner)
            expect(listOf("Orchid house"))
            secure.saveCloudEnrollment(owner.copy(tenantId = "other-customer"))
            expect(emptyList())
        } finally {
            if (previous == null) secure.clearCloudEnrollment() else secure.saveCloudEnrollment(previous)
        }
    }
}
