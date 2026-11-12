package studio.junecity.greenhouse

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import studio.junecity.greenhouse.data.*
import studio.junecity.greenhouse.model.HistoryReading

class OvernightInsightsTest {
    private fun point(minute: Int, temp: Double) = HistoryReading("a","Bench",temp,70.0,minute * 60_000L)

    @Test fun replayRejectsFutureAndStaleSamples() {
        val series = SensorTimeline(listOf(point(30, 50.0), point(1, 42.0)))
        assertNull(series.at(0))
        assertEquals(42.0, series.at(11*60_000L)!!.temperatureF, 0.0)
        assertNull(series.at(12*60_000L))
        assertEquals(50.0, series.at(30*60_000L)!!.temperatureF, 0.0)
    }
    @Test fun thresholdsIncludeOnlyObservedIntervalsAndClipAtWindowEdges() {
        val series = SensorTimeline(listOf(point(3, 40.0), point(3, 40.0), point(13, 50.0), point(33, 37.0), point(43, 37.0)))
        val stats = series.summarize(NightWindow(5*60_000L, 38*60_000L), 45.0)
        assertEquals(13*60_000L, stats.coverageMs)
        assertEquals(8*60_000L, stats.belowMs)
        assertEquals(33*60_000L, stats.coldest!!.timestampEpochMs)
    }
    @Test fun exactThresholdIsNotBelowAndIsolatedPointHasNoCoverage() {
        val timeline = SensorTimeline(listOf(point(1, 45.0),point(5,45.0),point(60,32.0)))
        val stats = timeline.summarize(NightWindow(0,61*60_000L),45.0)
        assertEquals(0L, stats.belowMs)
        assertEquals(4*60_000L, stats.coverageMs)
    }
    @Test fun nightHandlesClockChangesAndOngoingEvenings() {
        val ny = ZoneId.of("America/New_York")
        fun night(instant:String) = NightWindow.at(Instant.parse(instant).toEpochMilli(),ny)
        val spring = night("2026-03-08T17:00:00Z")
        assertEquals(11*3600_000L, spring.end-spring.start)
        val fall = night("2026-11-01T17:00:00Z")
        assertEquals(13*3600_000L,fall.end-fall.start)
        val evening = night("2026-11-13T02:00:00Z")
        assertEquals(3600_000L,evening.end-evening.start)
    }
}
