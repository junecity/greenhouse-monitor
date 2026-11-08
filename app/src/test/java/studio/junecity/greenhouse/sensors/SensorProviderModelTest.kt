package studio.junecity.greenhouse.sensors

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SensorProviderModelTest {
    @Test fun providerMeasurementMapsToExistingUiModelWithoutPrecisionLoss() {
        val device = SensorDevice("junecity", "sensor-1", "prototype", "North Sensor")
        val measurement = SensorMeasurement(device, 72.456, 58.765, 91, true, 123_456L)
        val legacy = measurement.toLegacySensor("greenhouse-a", "Zone 2")
        assertEquals("sensor-1", legacy.id)
        assertEquals(72.456, legacy.temperatureF!!, 0.0)
        assertEquals(58.765, legacy.humidityPercent!!, 0.0)
        assertEquals("greenhouse-a", legacy.greenhouseId)
        assertEquals("Zone 2", legacy.zone)
        assertTrue(legacy.online)
    }

    @Test fun missingOptionalMeasurementsRemainMissing() {
        val device = SensorDevice("future", "sensor-2", "unknown", "Future Sensor")
        val legacy = SensorMeasurement(device, null, null, null, false, 10L).toLegacySensor()
        assertNull(legacy.temperatureF)
        assertNull(legacy.humidityPercent)
        assertTrue(!legacy.online)
    }
}

