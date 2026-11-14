package studio.junecity.greenhouse.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import studio.junecity.greenhouse.model.*

class EnergyWeatherPriceTest {
    @Test fun monthlyResidentialElectricityConvertsCentsAndIgnoresPriorYear() {
        val html = """by State, August 2026 and 2025 (Cents per Kilowatthour)
            <th>Residential</th><tr><td>Connecticut</td><td>24.51</td><td>30.17</td></tr>
            <tr><td>U.S. Total</td><td>18.16</td><td>17.57</td></tr>"""
        val prices = parsePrices(EnergyUnit.Kwh, html)
        assertEquals(.2451,prices.getValue("CT").dollars,1e-8)
        assertEquals("2026-08",prices.getValue("US").period)
        assertEquals("NM",usStateCode("New Mexico"))
    }

    @Test fun missingStateGasMonthUsesMostRecentReportedMonthAndProperUnit() {
        val table = """<th class="Series5">Jul-26</th><th class="Series5">Aug-26</th>
            <tr class="DataRow"><td class="DataStub1">Connecticut</td><td class="DataB">20.74</td><td class="Current2">NA</td>
            <tr class="DataRow"><td class="DataStub1"><B>U.S.<B></td><td class="DataB">10</td><td class="Current2">10.37</td>"""
        val prices = parsePrices(EnergyUnit.Therm,table)
        assertEquals("2026-07", prices.getValue("CT").period)
        assertEquals(2.0, prices.getValue("CT").dollars, 1e-8)
        assertEquals("2026-08", prices.getValue("US").period)
        assertEquals(1.0, prices.getValue("US").dollars, 1e-8)
    }

    @Test fun annualWoodAndWeeklyPropaneHaveDifferentBillingBases() {
        val wood = parsePrices(EnergyUnit.Wood, "Data_Status,State,MSN,2023,2024\n2024F,CT,WDRCD,7,8.38\n2024F,US,WDRCD,9,10.45")
        assertEquals(FuelPrice(8.38,"CT","2024"),wood["CT"])
        val propane = parsePrices(EnergyUnit.Propane, """<th class="Series5">11/09/26</th>
            <tr class="DataRow"><td class="DataStub1"><B>U.S.<B></td><td class="Current2">2.826</td>""")
        assertEquals(FuelPrice(2.826,"US","2026-11-09"), propane["US"])
        val house = GreenhouseConfig()
        for (fuel in EnergyUnit.entries) {
            val coeff = heatingCoefficient(house, fuel)
            assertTrue(coeff.low < coeff.nominal && coeff.nominal < coeff.high)
            assertEquals(heatingCoefficient(house,EnergyUnit.Kwh).nominal * EnergyUnit.Kwh.btu,
                coeff.nominal * fuel.btu * fuel.delivery, 1e-8)
        }
    }

    @Test fun onlyElapsedObservedTimeReplacesForecast() {
        val date = LocalDate.parse("2026-11-13")
        val hour = java.time.Instant.parse("2026-11-13T22:00:00Z").toEpochMilli()
        val half = hour + HEATING_HOUR/2
        val json = JSONObject("""{"features":[
            {"properties":{"timestamp":"2026-11-13T22:30:00Z","temperature":{"value":0,"unitCode":"wmoUnit:degC","qualityControl":"V"}}},
            {"properties":{"timestamp":"2026-11-13T22:10:00Z","temperature":{"value":18,"unitCode":"wmoUnit:degC","qualityControl":"X"}}},
            {"properties":{"timestamp":"2026-11-13T23:20:00Z","temperature":{"value":50,"unitCode":"wmoUnit:degC","qualityControl":"V"}}}
        ]}""")
        val observations = stationHours(json, half)
        val weather = WeatherReading("06382","CT",40.0,"Clear", hourlyOutdoorF=mapOf(hour to 40.0, hour+HEATING_HOUR to 40.0),
            solarDays=mapOf(date to SolarDay(null,hour), date.plusDays(1) to SolarDay(hour+2*HEATING_HOUR,null)),
            stationHours=observations)
        assertEquals(1, observations.size)
        val original = NightHeating.night(weather.copy(stationHours=emptyMap()),60.0,date,half)
        val mixed = NightHeating.night(weather,60.0,date,half)
        assertEquals(40.0,original.degreeHours!!,1e-8)
        assertEquals(44.0,mixed.degreeHours!!,1e-8)
        assertEquals(.5,mixed.hours.filter { it.observed }.sumOf { it.hours },1e-8)
        assertEquals(48.0,NightHeating.night(weather,60.0,date,hour+2*HEATING_HOUR).degreeHours!!,1e-8)
    }
}
