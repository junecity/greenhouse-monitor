package studio.junecity.greenhouse.widget

import org.junit.Assert.*
import org.junit.Test
import studio.junecity.greenhouse.data.ClimateBucket
import studio.junecity.greenhouse.data.SpatialInterpolator
import studio.junecity.greenhouse.model.GoveeSensor
import studio.junecity.greenhouse.model.GreenhouseConfig
import studio.junecity.greenhouse.model.Metric
import studio.junecity.greenhouse.model.NotificationPreferences
import studio.junecity.greenhouse.model.SensorPosition
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

class ClimateCardTest {
    private val minute = 60_000L
    private val now = Instant.parse("2026-11-16T22:30:00Z").toEpochMilli()
    private val sensor = GoveeSensor("s", "H5179", "South", 68.0, 50.0, true, now)
    private fun card(rows: List<GoveeSensor> = listOf(sensor), at: Long = now,
                     trend: List<ClimateBucket> = emptyList(), celsius: Boolean = false,
                     cadence: Int = 1, target: Double? = null, outdoor: WidgetOutdoor? = null,
                     limits: NotificationPreferences = NotificationPreferences(), issue: WidgetFeedStatus? = null) =
        climateCardState("South", rows, trend, at, cadence, celsius, target, outdoor, limits, issue)

    @Test fun actualObservationsAgeAndRecoverIndependentlyOfRefresh() {
        val live = card()
        assertEquals(WidgetFeedStatus.Live, live.status)
        assertEquals(now + 2 * minute + 1, live.nextStatusAt)
        val delayed = card(at = now + 3 * minute)
        assertEquals(WidgetFeedStatus.Delayed, delayed.status)
        assertEquals(now, delayed.measuredAt)
        assertEquals(68.0, delayed.temperatureF!!, 0.0)
        assertNull(delayed.dewPointF)
        assertEquals(WidgetFeedStatus.Offline, card(at = now + 11 * minute).status)
        assertEquals(WidgetFeedStatus.Live, card(at = now + 9 * minute, cadence = 10).status)
        assertEquals(WidgetFeedStatus.Offline, card(listOf(sensor.copy(online = false))).status)
        assertEquals(WidgetFeedStatus.Live, card(listOf(sensor.copy(updatedAtEpochMs = now + 3 * minute)),
            at = now + 3 * minute).status)
        assertNull(card(listOf(sensor.copy(updatedAtEpochMs = now + 1))).measuredAt)
    }

    @Test fun chartDropsFutureOldInvalidAndDuplicateSamplesAndRespectsGaps() {
        val points = (0..10).map { i -> ClimateBucket(now - i * 5 * minute, 68.0 + i * .25, 52.0 - i * .5) }
        val invalid = listOf(points[0], ClimateBucket(now + minute, 100.0, 80.0),
            ClimateBucket(now - minute, Double.NaN, 80.0), ClimateBucket(now - 361 * minute, 10.0, 30.0))
        val state = card(trend = invalid + points)
        assertEquals(points.size, state.trend.size)
        assertEquals(-3.0, state.slopeFPerHour!!, 0.00001)
        assertEquals("47–52% range", state.humidityRangeText)
        assertNull(card(trend = points.filterIndexed { i, _ -> i <= 3 || i >= 7 }).slopeFPerHour)
        assertEquals("Not enough history", card(trend = points.take(1)).humidityRangeText)
        assertEquals("Trend unavailable", card(trend = points.take(3)).rateText)
    }

    @Test fun respectsUnitsDewPointMissingValuesAndTargetBoundaries() {
        val c = card(celsius = true, target = 67.2)
        assertEquals("20°C", c.temperatureText)
        assertEquals("50%", c.humidityText)
        assertEquals(48.67, c.dewPointF!!, .04)
        assertEquals("At target", c.targetStatus)
        assertEquals("Above target", card(target = 66.0).targetStatus)
        assertEquals("Below target", card(target = 70.0).targetStatus)
        assertNull(card().targetStatus)
        val high = card(limits = NotificationPreferences(highTemperatureF = 68.0))
        assertTrue(high.warning)
        assertEquals("High temperature", high.targetStatus)
        assertFalse(card(limits = NotificationPreferences(temperatureAlerts = false, highTemperatureF = 68.0)).warning)
        assertNull(card(listOf(sensor.copy(humidityPercent = 0.0))).dewPointF)
        assertNull(card(listOf(sensor.copy(humidityPercent = null))).dewPointF)
    }

    @Test fun averageUsesFreshSensorsAndPermissionAndErrorRetainOnlyCachedValues() {
        val stale = sensor.copy(id = "alt", temperatureF = 92.0, updatedAtEpochMs = now - 6 * minute)
        assertEquals(68.0, card(listOf(stale, sensor)).temperatureF!!, 0.0)
        assertEquals(92.0, card(listOf(stale)).temperatureF!!, 0.0)
        for (issue in listOf(WidgetFeedStatus.Error, WidgetFeedStatus.Permission)) {
            val state = card(listOf(sensor), issue = issue)
            assertEquals(issue, state.status)
            assertEquals(68.0, state.temperatureF!!, 0.0)
            assertNull(state.dewPointF)
            assertNull(state.targetStatus)
        }
        assertEquals(WidgetFeedStatus.Empty, card(emptyList()).status)
    }

    @Test fun weatherFreshnessUsesItsOwnObservationTime() {
        val outside = WidgetOutdoor(42.0, now - 16 * minute)
        assertEquals(OutdoorStatus.Current, card(outdoor = outside).outdoorFreshness)
        assertEquals(OutdoorStatus.Delayed, card(outdoor = outside.copy(failed = true)).outdoorFreshness)
        val old = card(outdoor = outside.copy(measuredAt = now - 62 * minute))
        assertNull(old.outdoorF)
        assertEquals(WidgetFeedStatus.Live, old.status)
        assertNull(card(outdoor = WidgetOutdoor(null, null)).outdoorAt)
        assertNull(card(outdoor = outside.copy(measuredAt = now + minute)).outdoorF)
    }

    @Test fun greenhouseCurrentUsesTheDashboardFloorPlanAndSupportsSingleSensorPins() {
        val rows = listOf(sensor, sensor.copy(id = "b", temperatureF = 61.0),
            sensor.copy(id = "c", temperatureF = 90.0, humidityPercent = 75.0),
            sensor.copy(id = "unplaced", temperatureF = 103.0))
        val house = GreenhouseConfig(sensorPositions = mapOf("s" to SensorPosition(1.0, 1.0),
            "b" to SensorPosition(1.1, 1.1), "c" to SensorPosition(9.0, 3.5)))
        fun load(sensors: List<GoveeSensor>) = climateCardState("Main", sensors, emptyList(), now, 1, false,
            null, null, NotificationPreferences(), house = house)
        val state = load(rows)
        assertEquals(SpatialInterpolator.greenhouseAverage(rows, house, Metric.Temperature, now)!!, state.temperatureF!!, 1e-8)
        assertEquals(SpatialInterpolator.greenhouseAverage(rows, house, Metric.Humidity, now)!!, state.humidity!!, 1e-8)
        assertEquals(SpatialInterpolator.greenhouseAverage(rows, house, Metric.DewPoint, now)!!, state.dewPointF!!, 1e-8)
        assertEquals(90.0, load(listOf(rows[2])).temperatureF!!, 0.0)
    }

    @Test fun mixedOfflineSourcesKeepTheirSampleTimeAndCannotMakeACloudReadLive() {
        val old = now - 3 * minute
        val govee = sensor.copy(temperatureF = 95.0, updatedAtEpochMs = old)
        val radio = sensor.copy(id = "shelly:room", temperatureF = 52.0, updatedAtEpochMs = old)
        val (mixed, issue) = cardSources(listOf(govee, radio), false, internet = true, bluetoothAllowed = true, fetchFailed = true)
        assertNull(issue)
        val mixedState = card(mixed)
        assertEquals(WidgetFeedStatus.Live, mixedState.status)
        assertEquals(52.0, mixedState.temperatureF!!, 0.0)
        assertEquals(old, mixedState.measuredAt)
        for (paired in listOf(true, false)) {
            val (failedRows, problem) = cardSources(listOf(govee), paired, internet = false, bluetoothAllowed = true, fetchFailed = false)
            val cached = card(failedRows, issue = problem)
            assertEquals(WidgetFeedStatus.Offline, cached.status)
            assertEquals(old, cached.measuredAt)
            assertNull(cached.dewPointF)
        }
    }

    @Test fun timeLabelsRespectLocalDatesAndDst() {
        val before = Instant.parse("2026-03-08T06:55:00Z").toEpochMilli()
        val after = Instant.parse("2026-03-08T07:05:00Z").toEpochMilli()
        val ny = ZoneId.of("America/New_York")
        assertTrue(widgetDate(before, after, ny, Locale.US).contains("1:55"))
        assertTrue(widgetDate(after, after, ny, Locale.US).contains("3:05"))
        assertTrue(widgetDate(before, before + 24 * 60 * minute, ny, Locale.US).contains("3/8/26"))
    }
}
