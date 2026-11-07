package studio.junecity.greenhouse.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SensorDerivedValuesTest {
    @Test fun computesDewPointAndVpdFromFullPrecisionReading() {
        val sensor = GoveeSensor("id", "H5179", "Sensor", 77.0, 50.0, true, System.currentTimeMillis())
        assertEquals(56.9, sensor.dewPointF!!, 0.2)
        assertEquals(1.58, sensor.vpdKpa!!, 0.03)
    }

    @Test fun freshnessRejectsOldReadings() {
        val old = GoveeSensor("id", "H5179", "Sensor", 70.0, 60.0, true, 1_000L)
        assertTrue(!old.isFresh(now = 1_000L + 10 * 60_000L + 1))
    }
}
