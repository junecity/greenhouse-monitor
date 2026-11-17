package studio.junecity.greenhouse.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HeartbeatRetryPolicyTest {
    @Test fun successfulHeartbeatUsesOneMinuteCadence() = assertEquals(60_000L, HeartbeatRetryPolicy.delayMs(0))
    @Test fun failuresRetryQuicklyThenCapAtOneMinute() {
        assertEquals(15_000L, HeartbeatRetryPolicy.delayMs(1))
        assertEquals(30_000L, HeartbeatRetryPolicy.delayMs(2))
        assertEquals(60_000L, HeartbeatRetryPolicy.delayMs(20))
    }

    @Test fun slowSnapshotDoesNotPostponeTheNextMinuteReading() {
        val started = 120_000L
        val wallClockAtStart = 1_795_000_000_000L
        val completed = PublishedSnapshot(started, wallClockAtStart + 8_000L)
        assertEquals(wallClockAtStart + 8_000L, completed.uploadedAtEpochMs)
        assertFalse(completed.nextUploadDue(started + 59_999, 1))
        assertTrue(completed.nextUploadDue(started + 60_000, 1))
        assertFalse(completed.nextUploadDue(started + 60_000, 5))
        assertTrue(completed.nextUploadDue(started + 300_000, 5))
    }
}
