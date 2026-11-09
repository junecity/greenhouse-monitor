package studio.junecity.greenhouse.model

import org.junit.Assert.assertEquals
import org.junit.Test

class DataSyncIntervalsTest {
    @Test fun exposesApprovedIntervals() {
        assertEquals(listOf(1, 5, 10, 15), DataSyncIntervals.minutes)
    }

    @Test fun normalizesLegacyValuesToNearestApprovedInterval() {
        assertEquals(1, DataSyncIntervals.normalize(0))
        assertEquals(1, DataSyncIntervals.normalize(3))
        assertEquals(5, DataSyncIntervals.normalize(6))
        assertEquals(15, DataSyncIntervals.normalize(29))
        assertEquals(15, DataSyncIntervals.normalize(99))
    }
}
