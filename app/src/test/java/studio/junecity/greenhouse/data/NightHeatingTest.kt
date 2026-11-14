package studio.junecity.greenhouse.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import studio.junecity.greenhouse.model.*
import java.time.*

class NightHeatingTest {
    private val date = LocalDate.of(2026, 3, 7)
    @Test fun learnsSteadyNightsWithEqualWeightAndRejectsMissingOrDriftingData() {
        fun samples(day: Int, values: List<Double>) = values.mapIndexed { i, f ->
            ThermalPoint(date.plusDays(day.toLong()), day * 24 * HEATING_HOUR + i * HEATING_HOUR / 4, f, 40.0, listOf("a"))
        }
        val stable = samples(1, listOf(62.0, 62.5, 63.0, 62.5, 62.0))
        assertEquals(62.5, learnedTemperature(stable)!!.fahrenheit, 0.0001)
        assertEquals(LearnedTemperature(64.25, 2), learnedTemperature(stable + samples(2, List(25) { 66.0 })))
        assertNull(learnedTemperature(stable.drop(1)))
        assertNull(learnedTemperature(stable.mapIndexed { i, p -> if (i == 2) p.copy(sensors = listOf("b")) else p }))
        assertNull(learnedTemperature(stable.map { it.copy(outdoor = it.indoor) }))
        assertNull(learnedTemperature(samples(1, listOf(67.0, 66.0, 65.0, 64.0, 63.0))))
    }
    @Test fun learnsAcrossThirtyQualifyingNightsWithoutOverweightingOldNights() {
        val history = (0..39).flatMap { day ->
            (0..4).map { quarter -> ThermalPoint(date.plusDays(day.toLong()),
                (day * 24L * HEATING_HOUR) + quarter * HEATING_HOUR / 4,
                if (day < 10) 75.0 else 61.0, 40.0, listOf("a")) }
        }
        assertEquals(LearnedTemperature(61.0, 30), learnedTemperature(history))
    }

    private val zone = ZoneId.of("America/New_York")
    private fun at(day: LocalDate, time: String) = day.atTime(LocalTime.parse(time)).atZone(zone).toInstant().toEpochMilli()
    private val sunsets = (-3L..8L).associate { n ->
        val d = date.plusDays(n)
        d to SolarDay(at(d, "06:15"), at(d, "18:30"))
    }
    private val h = HEATING_HOUR
    private val start = at(date,"18:30")
    private val end = at(date.plusDays(1),"06:15")
    private val hourly = generateSequence(at(date.minusDays(3),"00:00")) { it + h }
        .takeWhile { it < at(date.plusDays(8),"23:00") }.associateWith { 40.0 }
    private val weather = WeatherReading("06382","Test",40.0,"Clear",zone=zone,hourlyOutdoorF=hourly,solarDays=sunsets)
    private fun forecast(w: WeatherReading = weather, now: Long = start) = NightHeating.forecast(w,60.0,now)

    @Test fun fractionalSunsetAndSunriseAcrossClockChangeAndSevenNights() {
        val week = forecast()
        assertEquals(7, week.size)
        assertEquals(10.75*20,week.first().degreeHours!!,.00001)
        assertEquals(160.0,week.first().eightDegreeHours!!,.00001)
        assertEquals(start,week.first().hours.first().start)
        assertEquals(end,week.first().hours.last().end)
        assertEquals(date.plusDays(6),week.last().date)
        assertNotNull(week.last().degreeHours)
        assertEquals(date, forecast(now=end-1).first().date)
        assertEquals(date.plusDays(1),forecast(now=end).first().date)
    }
    @Test fun positiveDeficitIntegratedHourByHourIgnoresHeatSurplusAndDaytime() {
        val mixed = weather.copy(hourlyOutdoorF=hourly.mapValues { (t,_) ->
            when { t in (start+h/2) until (start+3*h/2) -> 20.0; t < start-h/2 -> -40.0; else -> 80.0 }
        })
        assertEquals(40.0,forecast(mixed).first().degreeHours!!,.00001)
        assertEquals(40.0,forecast(mixed).first().eightDegreeHours!!,.00001)
    }
    @Test fun missingRowsNeverBecomeZeroCostAndShortNightHasNoEightHourTotal() {
        val missing = weather.copy(hourlyOutdoorF=hourly - (start+h/2))
        assertNull(forecast(missing).first().degreeHours)
        assertNull(forecast(missing).first().eightDegreeHours)
        val lateGap = weather.copy(hourlyOutdoorF=hourly - (end-h/4))
        assertNull(forecast(lateGap).first().degreeHours)
        assertNotNull(forecast(lateGap).first().eightDegreeHours)
        assertNull(forecast(weather.copy(solarDays=emptyMap())).first().degreeHours)
        val short = weather.copy(solarDays=sunsets + (date.plusDays(1) to SolarDay(start+6*h, end+12*h)))
        assertNull(forecast(short).first().eightDegreeHours)
        assertEquals(120.0,forecast(short).first().degreeHours!!,.001)
    }
    @Test fun envelopeUsesPhysicalBoundaryAndFuelUnits() {
        val g = GreenhouseConfig()
        val k = heatingCoefficient(g,EnergyUnit.Kwh)
        val gas = heatingCoefficient(g,EnergyUnit.Therm)
        val ua = (48*6.4 + 128*1.1)*.95 + .018*128*7.2
        assertEquals(ua/3412.1416,k.nominal,1e-8)
        assertEquals(ua/80000.0,gas.nominal,1e-8)
        assertTrue(k.low < k.nominal && k.nominal < k.high)
        assertTrue(heatingCoefficient(g.copy(shape=GreenhouseShape.TShape), EnergyUnit.Kwh).nominal > k.nominal)
        assertTrue(heatingCoefficient(g.copy(heightFeet=16.0),EnergyUnit.Kwh).nominal > k.nominal)
    }
    @Test fun nighttimeProfileBalancesSensorsSkipsDaytimeGapsAndOtherHouses() {
        val readings = buildList {
            for (night in listOf(date.minusDays(1),date)) {
                val t = at(night,"19:00")
                listOf(60.0,59.0,60.0,59.0,60.0).forEachIndexed { i,v ->
                    val tick = t + i*h/4
                    add(HistoryReading("one","",v-1,80.0,tick))
                    add(HistoryReading("one","",v-1,80.0,tick+100))
                    add(HistoryReading("two","",v+1,80.0,tick))
                    add(HistoryReading("other","",110.0,80.0,tick))
                }
                add(HistoryReading("one","",100.0,80.0,at(night,"13:00")))
            }
        }
        val points = NightHeating.observations(weather,readings,setOf("one","two"),end)
        assertEquals(10,points.size)
        val profile = nightBehavior(points,60.0,40.0)!!
        assertEquals(2,profile.nights)
        assertEquals(2.0,profile.hours,.001)
        assertEquals(1.0,profile.nearTarget,.001)
        assertEquals(4.0,profile.cooling!!,.001)
        assertEquals(4.0,profile.recovery!!,.001)
        assertEquals(1.0,profile.swing,.001)
        assertNull(nightBehavior(points,60.0,60.0))
        assertNull(nightBehavior(points.filterIndexed { i,_ -> i % 2 == 0 },60.0))
    }
    @Test fun epochWeatherHandlesBothRepeatedHoursAndSolarDates() {
        val fall = LocalDate.of(2026,11,1)
        val repeated = at(fall,"01:00")/1000
        val json = JSONObject().put("timezone",zone.id)
            .put("current",JSONObject().put("time",repeated))
            .put("daily",JSONObject().put("time",listOf(at(fall,"00:00")/1000))
                .put("sunrise",listOf(at(fall,"06:15")/1000)).put("sunset",listOf(at(fall,"18:30")/1000)))
            .put("hourly",JSONObject().put("time",listOf(repeated,repeated+3600))
                .put("temperature_2m", listOf(40,42)))
        val parsed = LiveWeatherProvider().parse("06382","Local",JSONObject(json.toString()))
        assertEquals(2,parsed.hourlyOutdoorF.size)
        assertEquals(SolarDay(at(fall,"06:15"),at(fall,"18:30")),parsed.solarDays[fall])
        assertEquals(fall,parsed.days.single().date)
    }
}
