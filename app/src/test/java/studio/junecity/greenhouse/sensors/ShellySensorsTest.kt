package studio.junecity.greenhouse.sensors

import org.junit.Assert.*
import org.junit.Test
import studio.junecity.greenhouse.model.*
import studio.junecity.greenhouse.data.ZoneClimate

class ShellySensorsTest {
    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    @Test fun manualAddressesMatchRadioIdentity() {
        assertEquals("shelly:AA:01:02:03:04:FF", shellyAddressId(" aa-01-02-03-04-ff "))
        assertEquals(shellyAddressId("AA:01:02:03:04:FF"), shellyAddressId("aa01020304ff"))
        listOf("", "AA:01", "0011223344ZZ", "000000000000").forEach { assertNull(shellyAddressId(it)) }
    }

    @Test fun bothShellyModelsAreIdentifiedByManufacturerOrName() {
        assertEquals(ShellyModel.Display, ShellyModel.identify(null, bytes(1,0,0,11,12,0), null))
        assertEquals(ShellyModel.HT, ShellyModel.identify("SBHT-203C", null, null))
        assertEquals(ShellyModel.HT, ShellyModel.identify(null, null, 17))
        assertNull(ShellyModel.identify("other", bytes(11,1), null))
    }

    @Test fun displayBeaconsDecodeAllLightLevelsTemperatureAndHumidity() {
        listOf("Dark", "Twilight", "Bright").forEachIndexed { level, expected ->
            val beacon = ShellyBeacon.parse(bytes(0x40,0,4,1,90,0x15,0,0x1e,1,0x2e,46,0x45,15,1,0x64,level))!!
            assertEquals(90, beacon.batteryPercent)
            assertEquals(false, beacon.batteryLow)
            assertEquals(expected, beacon.light)
            assertEquals(80.78, beacon.temperatureF!!, .001)
            assertEquals(46.0, beacon.humidity!!, .001)
        }
    }

    @Test fun regularBeaconsSupportFreezingTemperaturesWithoutInventingLight() {
        val beacon = ShellyBeacon.parse(bytes(0x40,1,70,0x2e,95,0x3a,1,0x45,0xce,0xff))!!
        assertEquals(70, beacon.batteryPercent)
        assertNull(beacon.batteryLow)
        assertEquals(23.0, beacon.temperatureF!!, .001)
        assertNull(beacon.light)
        assertNull(ShellyBeacon.parse(bytes(0x40,0x45,0xff)))
        assertTrue(ShellyBeacon.parse(bytes(0x41,0xff,0xff))!!.encrypted)
        assertNull(ShellyBeacon.parse(bytes(0x20)))
    }

    @Test fun depletedUnknownAndFlagOnlyBatteriesRemainDistinct() {
        val sensor = GoveeSensor("shelly:demo", ShellyModel.Display.sku, "Bench")
        fun reading(vararg battery: Int): GoveeSensor {
            val beacon = ShellyBeacon.parse(bytes(0x40, *battery, 0x2e, 50, 0x45, 10, 0))!!
            return ShellyAdvertisement(sensor.id, ShellyModel.Display, beacon, 10_000).update(sensor)!!
        }
        assertEquals(0, reading(1, 0, 0x15, 1).batteryPercent)
        assertEquals(true, reading(0x15, 1).batteryLow)
        assertNull(reading(0x15, 1).batteryPercent)
        assertNull(reading(1, 255).batteryPercent)
        assertNull(reading(0x15, 255).batteryLow)
        assertNull(reading().batteryPercent)
    }

    @Test fun fiftyIndividuallyAddedSensorsKeepTheirLocationsAndAllFeedThe3dModel() {
        val now = System.currentTimeMillis()
        val sensors = (1..50).map { GoveeSensor("shelly:$it", "SBHT-103C", "Bench $it",
            temperatureF = 50.0 + it / 2.0, humidityPercent = 70.0, online = true, updatedAtEpochMs = now) }
        var house = GreenhouseConfig(shape = GreenhouseShape.TShape)
        sensors.forEach { house = house.withNewSensorPositions(listOf(it.id)) }
        assertEquals(50, house.sensorPositions.values.toSet().size)
        house = house.copy(sensorPositions = house.sensorPositions.mapValues { (_, pos) -> pos.copy(heightFeet = 4.0) })
        assertEquals(house, house.withNewSensorPositions(sensors.reversed().map { it.id }))
        val probes = ZoneClimate.probes(GreenhouseUiState(sensors=sensors, preferences=AppPreferences(greenhouses=listOf(house))), now)
        assertEquals(50, probes.size)
        assertTrue(ZoneClimate.field(house, probes, Metric.Temperature).isNotEmpty())
    }
}
