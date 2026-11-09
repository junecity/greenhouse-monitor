package studio.junecity.greenhouse.model

import org.junit.Assert.assertEquals
import org.junit.Test

class DataSyncIntervalsTest {
    @Test fun exposesApprovedIntervals() {
        assertEquals(listOf(1, 2, 5, 10, 30), DataSyncIntervals.minutes)
    }

    @Test fun normalizesLegacyValuesToNearestApprovedInterval() {
        assertEquals(1, DataSyncIntervals.normalize(0))
        assertEquals(2, DataSyncIntervals.normalize(3))
        assertEquals(5, DataSyncIntervals.normalize(6))
        assertEquals(30, DataSyncIntervals.normalize(29))
        assertEquals(30, DataSyncIntervals.normalize(99))
    }
}
