package studio.junecity.greenhouse

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class ClientForegroundRefreshTest {
    @Test fun everyForegroundVisitFetchesBeforeWaitingEvenAtLongestInterval() = runBlocking {
        val events = mutableListOf<String>()
        repeat(2) {
            try {
                pollClientReadings(
                    refresh = { events.add("fetch"); true },
                    intervalMinutes = { 15 },
                    wait = { events.add("wait $it"); throw CancellationException() }
                )
            } catch (_: CancellationException) { }
        }
        assertEquals(listOf("fetch", "wait 900000", "fetch", "wait 900000"), events)
    }

    @Test fun transientFailureRetriesAndThenReturnsToConfiguredInterval() = runBlocking {
        var calls = 0
        val waits = mutableListOf<Long>()
        try {
            pollClientReadings(
                refresh = { ++calls == 3 },
                intervalMinutes = { 5 },
                wait = { waits.add(it); if (waits.size == 4) throw CancellationException() }
            )
        } catch (_: CancellationException) { }
        assertEquals(listOf(3000L, 6000L, 300000L, 3000L), waits)
    }
}
