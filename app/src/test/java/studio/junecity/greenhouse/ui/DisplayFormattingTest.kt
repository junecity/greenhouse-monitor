package studio.junecity.greenhouse.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class DisplayFormattingTest {
    @Test fun roundsOnlyAtDisplayBoundary() {
        assertEquals("72°F", formatTemp(72.4))
        assertEquals("73°F", formatTemp(72.5))
        assertEquals("58%", formatHumidity(57.6))
        assertEquals("—", formatTemp(null))
    }
}
