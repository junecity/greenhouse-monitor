package studio.junecity.greenhouse.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import studio.junecity.greenhouse.model.ForecastDay
import java.time.*

class ForecastTest {
    private val day = ForecastDay(LocalDate.of(2026, 2, 2), "Snow", 32.0, 90.0, null)

    @Test fun readsSevenLocalDaysWithoutInventingMissingValues() {
        val days = (0..7).map { day.date.plusDays(it.toLong()).toString() }
        val daily = JSONObject().put("time", days).put("temperature_2m_min", listOf(null, 32))
            .put("precipitation_probability_max", listOf(0, 100, null)).put("weather_code", listOf(71))
        val root = JSONObject().put("timezone", "America/New_York").put("daily", daily)
            .put("current", JSONObject().put("temperature_2m", 45).put("weather_code", 3))
        val reading = LiveWeatherProvider().parse("06382", "Uncasville", JSONObject(root.toString()))
        assertEquals(7, reading.days.size)
        assertEquals(ZoneId.of("America/New_York"), reading.zone)
        assertEquals("Snow", reading.days.first().condition)
        assertNull(reading.days.first().lowF)
        assertEquals(0, reading.days.first().rainChance)
        assertEquals(100, reading.days[1].rainChance)
        assertNull(reading.days[2].rainChance)
        assertEquals(day.date.plusDays(6), reading.days.last().date)
    }

    @Test fun thresholdsAreOptInAndIncludeTheBoundary() {
        assertTrue(ForecastRules().matches(day).isEmpty())
        val both = ForecastRules(cold = true, heat = true)
        assertEquals(setOf("cold", "heat"), both.matches(day).keys)
        assertTrue(both.matches(day.copy(lowF = null, highF = null)).isEmpty())
        assertTrue(both.matches(day.copy(lowF = 32.1, highF = 89.9)).isEmpty())
    }

    @Test fun dailyAlarmUsesLocalClockAcrossDaylightSaving() {
        val zone = ZoneId.of("America/New_York")
        val before = ZonedDateTime.of(2026, 3, 7, 12, 0, 0, 0, zone)
        val next = ForecastRules().nextReminder(before)
        assertEquals(LocalDate.of(2026,3,8), next.toLocalDate())
        assertEquals(7, next.hour)
        assertEquals(ZoneOffset.ofHours(-4), next.offset)
        assertEquals(next, ForecastRules().nextReminder(next.minusMinutes(1)))
        assertEquals(next.plusDays(1), ForecastRules().nextReminder(next))
    }
}
