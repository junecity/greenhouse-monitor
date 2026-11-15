package studio.junecity.greenhouse.data

import org.junit.Assert.*
import org.junit.Test
import studio.junecity.greenhouse.model.*
import java.time.LocalDate

class WaterHeatTest {
    private val day = LocalDate.of(2026, 11, 15)
    private val dawn = day.toEpochDay() * 24 * HEATING_HOUR + 6 * HEATING_HOUR
    private val dusk = dawn + 10 * HEATING_HOUR
    private val night = HeatingNight(day, (0..11).map { i ->
        NightHour(dusk + i * HEATING_HOUR, dusk + (i + 1) * HEATING_HOUR, 40.0, 60.0)
    })
    private val weather = WeatherReading("06382", "Fixture", 40.0, "Clear", solarDays = mapOf(day to SolarDay(dawn,dusk)),
        hourlyOutdoorF = (0..10).associate { dawn + it * HEATING_HOUR to 45.0 })
    private val history = (0..120).map { HistoryReading("inside","Inside",80.0,60.0,dawn + it * HEATING_HOUR / 12) }
    private fun simulate(rows: List<HistoryReading> = history, n: HeatingNight = night,
        w: WeatherReading = weather, fuel: EnergyUnit = EnergyUnit.Kwh, price: Double? = .25, gallons: Double = 55.0) =
        WaterHeat.estimate(rows, setOf("inside"), w, n, dusk, 60.0, gallons, fuel,
            heatingCoefficient(GreenhouseConfig(),fuel), price)

    @Test fun tenHourDayAndTwelveHourNightConserveHeat() {
        val r = requireNotNull(simulate())
        val charged = 55 * 8.34 * 20 * (1 - Math.exp(-10.0/12))
        val used = charged * (1 - Math.exp(-1.0))
        assertEquals(10.0, r.sampledHours, 1e-8)
        assertEquals(charged, r.sunsetBtu, 1e-6)
        assertEquals(used, r.avoidedBtu!!, 1e-6)
        assertTrue(r.sunsetBtu <= r.excessBtu)
        for (fuel in EnergyUnit.entries) {
            val result = requireNotNull(simulate(fuel = fuel))
            assertEquals(used * .25 / (fuel.btu * fuel.delivery), result.savings!!, 1e-8)
            assertEquals(night.degreeHours!! * heatingCoefficient(GreenhouseConfig(), fuel).nominal * .25,
                result.savings + result.estimatedBill!!, 1e-6)
        }
    }
    @Test fun localSensorChangesCaptureWhileWholeHouseLossStaysFixed() {
        val rows = history + history.map { it.copy(sensorId = "cool", temperatureF = 65.0) }
        fun location(id: String?) = WaterHeat.estimate(rows, setOf("inside", "cool"), weather, night, dusk,
            60.0, 55.0, EnergyUnit.Kwh, heatingCoefficient(GreenhouseConfig(), EnergyUnit.Kwh), .25, id)
        val hot = requireNotNull(location("inside"))
        val average = requireNotNull(location(null))
        val cool = requireNotNull(location("cool"))
        assertTrue(hot.sunsetBtu > average.sunsetBtu && average.sunsetBtu > cool.sunsetBtu)
        assertEquals(hot.excessBtu, cool.excessBtu, 0.0)
        assertNull(location("missing"))
    }
    @Test fun supplyAndHourlyHeatingDemandLimitSavings() {
        val warm = night.copy(hours = night.hours.map { it.copy(outdoor = 80.0) })
        assertEquals(0.0, simulate(n = warm)!!.savings!!, 0.0)
        val capped = simulate(gallons = 1_000_000.0)!!
        assertTrue(capped.sunsetBtu <= capped.excessBtu + 1e-6)
        assertTrue(capped.estimatedBill!! >= 0)
        val hotOutside = weather.copy(hourlyOutdoorF = weather.hourlyOutdoorF.mapValues { 85.0 })
        assertEquals(0.0, simulate(w = hotOutside)!!.excessBtu, 0.0)
    }
    @Test fun gapsForeignSensorsAndNighttimeReadingsCannotCreateHeat() {
        val invalid = history.map { it.copy(sensorId = "foreign") } + history.map { it.copy(timestampEpochMs = dusk + HEATING_HOUR) }
        assertNull(simulate(rows = invalid))
        assertNull(simulate(rows = listOf(history.first(),history.last())))
        assertNull(simulate(w = weather.copy(hourlyOutdoorF = emptyMap())))
        val partial = simulate(rows = history.take(13) + invalid)!!
        assertEquals(1.0, partial.sampledHours, 1e-8)
        assertTrue(partial.sunsetBtu < simulate()!!.sunsetBtu)
        assertNull(simulate(price = null)!!.savings)
        assertNull(simulate(n = night.copy(hours = night.hours.map { it.copy(outdoor = null) }))!!.avoidedBtu)
    }
}
