package studio.junecity.greenhouse.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import studio.junecity.greenhouse.model.GoveeSensor

class CurrentSensorCodecTest {
    @Test fun sensorSnapshotRoundTripsServerMeasurementsAndAssignments() {
        val sensor = GoveeSensor(
            id = "device-123", sku = "H5179", name = "North bench",
            temperatureF = 74.25, humidityPercent = 61.5, online = true,
            updatedAtEpochMs = 123456789L, greenhouseId = "main", zone = "Zone 2"
        )

        assertEquals(listOf(sensor), CurrentSensorCodec.decode(CurrentSensorCodec.encode(listOf(sensor))))
    }

    @Test fun missingMeasurementsRemainUnavailableInsteadOfBecomingZero() {
        val sensor = GoveeSensor("device-456", "H5179", "Unconfigured")

        val decoded = CurrentSensorCodec.decode(CurrentSensorCodec.encode(listOf(sensor))).single()

        assertNull(decoded.temperatureF)
        assertNull(decoded.humidityPercent)
    }
}
