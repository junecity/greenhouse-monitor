package studio.junecity.greenhouse.cloud

import org.junit.Assert.assertEquals
import org.junit.Test

class HeartbeatRetryPolicyTest {
    @Test fun successfulHeartbeatUsesOneMinuteCadence() = assertEquals(60_000L, HeartbeatRetryPolicy.delayMs(0))
    @Test fun failuresRetryQuicklyThenCapAtOneMinute() {
        assertEquals(15_000L, HeartbeatRetryPolicy.delayMs(1))
        assertEquals(30_000L, HeartbeatRetryPolicy.delayMs(2))
        assertEquals(60_000L, HeartbeatRetryPolicy.delayMs(20))
    }
}
