package studio.junecity.greenhouse.data

import org.junit.Assert.*
import org.junit.Test
import studio.junecity.greenhouse.model.*

class ClimateTrendTest {
    private val minute = 60_000L
    private val edge = 2_000 * WIDGET_BUCKET_MS
    private val house = GreenhouseConfig()
    private fun sensor(id: String, temp: Double, at: Long, rh: Double = 80.0) =
        GoveeSensor(id, "H5179", id, temp, rh, true, at)
    private fun row(s: GoveeSensor, at: Long = s.updatedAtEpochMs) =
        HistoryReading(s.id, s.name, s.temperatureF!!, s.humidityPercent!!, at)

    @Test fun staggeredReportsAcrossABucketBoundaryDoNotPullTheEndpointToOneThermometer() {
        for (reporting in listOf(58.0, 74.0)) {
            val first = sensor("a", reporting, edge + 1_000)
            val other = sensor("shelly:b", 132.0 - reporting, edge - 1_000)
            val history = listOf(row(first, edge - minute), row(other))
            // The current sensor store can receive the new reading before a history import.
            val trend = climateTrend(listOf(first, other), house, history, edge + minute)
            assertEquals(listOf(edge - 1_000, edge + 1_000), trend.map { it.at })
            assertEquals(listOf(66.0, 66.0), trend.map { it.temperatureF })
            assertTrue(trend.last().continuesFrom(trend.first()))
            assertEquals(trend, climateTrend(listOf(first, other), house, history, edge + 25 * minute))
        }
    }

    @Test fun aDropoutAndAReturnStartNewTracesWhileAnActualTemperatureChangeIsPreserved() {
        val warm = sensor("warm", 82.0, edge)
        val cool = sensor("cool", 50.0, edge)
        val coldOnly = edge + 11 * minute
        val returned = edge + 16 * minute
        val history = listOf(row(warm), row(cool), row(cool, edge + 5 * minute), row(cool, coldOnly),
            row(warm, returned), row(cool, returned))
        val latest = listOf(warm.copy(updatedAtEpochMs = returned),
            cool.copy(temperatureF = 35.0, updatedAtEpochMs = returned + 5 * minute))
        val points = climateTrend(latest, house, history.reversed(), returned + 5 * minute)
        assertEquals(listOf(66.0, 66.0, 50.0, 66.0, 58.5), points.map { it.temperatureF })
        assertFalse(points[2].continuesFrom(points[1]))
        assertFalse(points[3].continuesFrom(points[2]))
        assertTrue(points[4].continuesFrom(points[3]))
        val pin = climateTrend(listOf(latest.last()), house, history, returned + 5 * minute)
        assertEquals(35.0, pin.last().temperatureF, 0.0)
        assertTrue(pin.last().continuesFrom(pin[pin.lastIndex - 1]))
        assertTrue(pin.all { it.contributors == setOf("cool") })
    }

    @Test fun floorPlanWeightsMatchDashboardWithFiftySensorsAndExcludeUnplacedUpdates() {
        val sensors = (0 until 50).map { i ->
            sensor("sensor$i", 40.0 + i, edge - i * 4_000, 20.0 + i)
        }
        val positions = (0 until 49).associate { i ->
            sensors[i].id to SensorPosition(1.0 + i / 8, .5 + (i % 8))
        }
        val floor = house.copy(sensorPositions = positions)
        val history = sensors.map { row(it, it.updatedAtEpochMs - WIDGET_BUCKET_MS) }
        val state = climateTrend(sensors, floor, history, edge + minute)
        assertEquals(SpatialInterpolator.greenhouseAverage(sensors, floor, Metric.Temperature, edge)!!,
            state.last().temperatureF, 1e-8)
        assertEquals(SpatialInterpolator.greenhouseAverage(sensors, floor, Metric.Humidity, edge)!!,
            state.last().humidity, 1e-8)
        assertEquals(49, state.last().contributors.size)
        val unplaced = sensors.last().copy(temperatureF = 110.0, updatedAtEpochMs = edge + 5 * minute)
        assertEquals(state, climateTrend(sensors.dropLast(1) + unplaced, floor, history, edge + 6 * minute))
    }

    @Test fun leftEdgeUsesRecentPreWindowObservationsAndSkipsInvalidAndForeignSamples() {
        val left = edge - WIDGET_WINDOW_MS
        val warm = sensor("warm", 76.0, left - minute)
        val cool = sensor("cool", 58.0, left + 1_000)
        val other = sensor("other", 130.0, edge).copy(greenhouseId = "other-house")
        val future = sensor("future", 97.0, edge + 1)
        val invalid = listOf(row(cool).copy(temperatureF = Double.NaN, timestampEpochMs = edge),
            row(warm).copy(humidityPercent = 150.0, timestampEpochMs = edge), row(other), row(future))
        val points = climateTrend(listOf(warm, cool, other, future), house, invalid, edge)
        assertEquals(listOf(ClimateBucket(left + 1_000, 67.0, 80.0, setOf("warm", "cool"))), points)
    }
}
