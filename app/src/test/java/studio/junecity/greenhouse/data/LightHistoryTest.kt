package studio.junecity.greenhouse.data

import org.junit.Assert.*
import org.junit.Test
import studio.junecity.greenhouse.model.*
import java.time.Instant
import java.time.ZoneOffset

class LightHistoryTest {
    private val sunset = Instant.parse("2026-11-10T18:00:00Z").toEpochMilli()
    private fun row(minute: Int, light: String?, time: Long = sunset, temp: Double = 60.0) =
        HistoryReading("s", "Bench", temp, 70.0, time + minute * LIGHT_MINUTE, light)

    @Test fun sustainedDimmingIncludesTwilightThenDarkAndUsesLocalTime() {
        val samples = listOf(row(-10,"Bright"), row(-5,"Bright"), row(0,"Twilight"), row(2,"Dark"), row(6,"Dark"))
        fun changes(zone: ZoneOffset) = LightHistory.changes(LightHistory.spans(samples), zone)
        assertEquals(listOf(LightChange(sunset,false)), changes(ZoneOffset.UTC))
        assertTrue(changes(ZoneOffset.ofHours(10)).isEmpty())
        assertTrue(LightHistory.changes(LightHistory.spans(samples.take(4)), ZoneOffset.UTC).isEmpty())
    }

    @Test fun neitherMissingLightNorRadioOutageEstablishesTransition() {
        for (gap in listOf(listOf(row(-2,null)), emptyList())) {
            val end = if (gap.isEmpty()) -15 else -5
            val samples = listOf(row(end-5,"Bright"),row(end,"Bright")) + gap + listOf(row(0,"Dark"),row(6,"Dark"))
            assertTrue(LightHistory.changes(LightHistory.spans(samples), ZoneOffset.UTC).isEmpty())
        }
    }

    @Test fun morningWarmupStopsAtFirstGap() {
        val morning = sunset - 12 * 3_600_000L
        val samples = listOf(row(-10,"Dark",morning),row(-5,"Dark",morning),row(0,"Bright",morning),
            row(6,"Bright",morning,62.1),row(25,"Bright",morning,68.0))
        val event = LightHistory.changes(LightHistory.spans(samples),ZoneOffset.UTC).single()
        assertTrue(event.brightening)
        val response = LightHistory.response(SensorTimeline(samples),event,50.0)!!
        assertEquals(6,response.minutes)
        assertEquals(6,response.twoDegreesAt)
        assertEquals(2.1,response.deltaF,.001)
    }

    @Test fun watchRequiresThreeComparableDatesWithContinuousCoverageAndWeather() {
        val events = (0..6).map { LightChange(sunset + it * DAY_MS,false) }
        val timeline = SensorTimeline(events.flatMapIndexed { i, e ->
            (0..(if (i==3) 20 else 60) step 5).map { m -> row(m,null,e.time, if (i == 5) 80.0 else 60.0 - m / 10.0) }
        })
        fun compare(prior: List<LightChange>) = LightHistory.compare(prior,events.last(),timeline,ZoneOffset.UTC,57.0) {
            if (it == events[4].time) null else 35.0
        }
        assertNull(compare(events.take(2) + events[0]))
        val match = compare(events + events[0])!!
        assertEquals(3,match.nights)
        assertEquals(3,match.crossings)
        assertTrue(match.coldWatch)
    }
}
