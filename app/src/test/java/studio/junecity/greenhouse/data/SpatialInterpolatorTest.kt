package studio.junecity.greenhouse.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import studio.junecity.greenhouse.model.GreenhouseConfig

class SpatialInterpolatorTest {
    private val greenhouse = GreenhouseConfig(lengthFeet = 16.0, widthFeet = 8.0)

    @Test fun exactSensorLocationReturnsItsReading() {
        val samples = listOf(SpatialSample(2.0, 2.0, 71.25), SpatialSample(14.0, 6.0, 80.0))
        assertEquals(71.25, SpatialInterpolator.estimate(samples, 2.0, 2.0)!!, 0.0001)
    }

    @Test fun symmetricSensorsGiveMidpointAverage() {
        val samples = listOf(SpatialSample(0.0, 4.0, 60.0), SpatialSample(16.0, 4.0, 80.0))
        assertEquals(70.0, SpatialInterpolator.center(samples, greenhouse)!!, 0.0001)
        assertEquals(70.0, SpatialInterpolator.areaAverage(samples, greenhouse)!!, 0.05)
    }

    @Test fun noSamplesReturnsNoEstimate() {
        assertNull(SpatialInterpolator.estimate(emptyList(), 8.0, 4.0))
        assertNull(SpatialInterpolator.areaAverage(emptyList(), greenhouse))
    }
}
