package studio.junecity.greenhouse.sensors

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import studio.junecity.greenhouse.data.SpatialInterpolator
import studio.junecity.greenhouse.model.*

class CollectReadingsTest {
    private val bench = SensorDevice("govee", "bench", "H5179", "Bench")
    private val door = bench.copy(deviceId="door", displayName="Door")
    private val key = SensorProviderCredential.ApiKey("fixture")
    private val now = System.currentTimeMillis()
    private fun provider(reading: (SensorDevice) -> SensorMeasurement) = object : SensorProvider {
        override val providerId = "govee"
        override suspend fun discover(credential: SensorProviderCredential) = listOf(door, bench, bench)
        override suspend fun read(credential: SensorProviderCredential, device: SensorDevice) = reading(device)
    }

    @Test fun failedNeighborKeepsLastMeasurementAndItsHouseWithoutHidingHealthyReadings() = runBlocking {
        val old = GoveeSensor("door", "H5179", "Door", 58.0, 84.0, true, now-8000, "old", "Zone 1")
        val house = GreenhouseConfig(id="second", name="Greenhouse 2")
        val prefs = AppPreferences(greenhouses=listOf(house), activeGreenhouseId=house.id)
        val sensors = provider {
            if (it == door) error("unavailable")
            SensorMeasurement(it, 69.0, 73.0, connected=true, recordedAtEpochMs=now)
        }.collectReadings(key, prefs, listOf(old)) { "Unassigned" }
        assertEquals(2, sensors.size)
        assertEquals(old.copy(online=false), sensors.first())
        assertEquals(house.id, sensors.last().greenhouseId)
        assertEquals(69.0, SpatialInterpolator.greenhouseAverage(sensors,house,Metric.Temperature,now)!!,0.0)
        assertNull(SpatialInterpolator.greenhouseAverage(sensors,house.copy(id="old"),Metric.Temperature,now))
    }

    @Test fun removedDevicesAreSkippedBeforeReading() = runBlocking {
        val requested = mutableListOf<String>()
        val active = provider {
            requested.add(it.deviceId)
            SensorMeasurement(it, 68.0, 45.0, connected=true, recordedAtEpochMs=now)
        }.collectReadings(key, AppPreferences(), emptyList(), setOf(door.deviceId)) { "Unassigned" }
        assertEquals(listOf("bench"), requested)
        assertEquals(requested, active.map { it.id })
    }

    @Test fun expiredHouseSkipsProviderReadAndKeepsTimestampWhileFreeHouseUpdates() = runBlocking {
        val annex = GreenhouseConfig(id = "annex", monitoringUntilEpochMs = now - 1)
        val prefs = AppPreferences(greenhouses = listOf(GreenhouseConfig(), annex),
            sensorGreenhouseIds = mapOf("door" to annex.id, "bench" to "main"))
        val old = GoveeSensor("door", "H5179", "Door", 60.0, 90.0, true, now - 3_000)
        val requested = mutableListOf<String>()
        val result = provider {
            requested.add(it.deviceId)
            SensorMeasurement(it, 72.0, 80.0, connected = true, recordedAtEpochMs = now)
        }.collectReadings(key, prefs, listOf(old)) { "Unassigned" }
        assertEquals(listOf("bench"), requested)
        assertEquals(old.updatedAtEpochMs, result.first().updatedAtEpochMs)
        val displayed = GreenhouseUiState(preferences = prefs, sensors = result).withMonitoringAccess(now)
        assertNull(displayed.sensors.first().temperatureF)
        assertEquals(72.0, displayed.sensors.last().temperatureF!!, 0.0)
        assertEquals(60.0, result.first().temperatureF!!, 0.0)
    }

    @Test(expected=CancellationException::class) fun cancellationEndsTheBatch() {
        runBlocking {
            provider { throw CancellationException() }.collectReadings(key, AppPreferences(), emptyList()) { "Unassigned" }
        }
    }
}
