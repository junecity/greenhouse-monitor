package studio.junecity.greenhouse.data

import studio.junecity.greenhouse.model.HistoryReading
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs

internal const val LIGHT_MINUTE = 60_000L
internal data class LightSpan(val level: String, val start: Long, val last: Long, val followsPrevious: Boolean = false)
internal data class LightChange(val time: Long, val brightening: Boolean)
internal data class LightResponse(val deltaF: Double, val minutes: Int, val twoDegreesAt: Int?, val lowAt: Int?)

internal object LightHistory {
    // Ordered observations from one Display. Require repeated observations to establish a transition.
    fun spans(rows: List<HistoryReading>): List<LightSpan> = buildList {
        var run: LightSpan? = null
        for (row in rows) {
            val level = row.lightLevel
            val time = row.timestampEpochMs
            if (run != null && level == run.level && time - run.last <= READING_LIFETIME) {
                run = run.copy(last = time)
            } else {
                run?.let(::add)
                run = if (level == "Bright" || level == "Twilight" || level == "Dark") LightSpan(level, time, time, run != null && time - run.last <= READING_LIFETIME) else null
            }
        }
        run?.let(::add)
    }

    fun changes(spans: List<LightSpan>, zone: ZoneId): List<LightChange> = buildList {
        var previous: LightSpan? = null
        var current: LightSpan? = null
        for (span in spans) {
            val bright = span.level == "Bright"
            val same = current != null && span.followsPrevious && (current.level == "Bright") == bright
            if (same) current = current!!.copy(last = span.last)
            else {
                previous = current.takeIf { span.followsPrevious }
                current = span
            }
            val before = previous ?: continue
            val after = current!!
            if (before.last - before.start < 5 * LIGHT_MINUTE || after.last - after.start < 5 * LIGHT_MINUTE) continue
            val morning = Instant.ofEpochMilli(after.start).atZone(zone).hour < 12
            if (morning == bright) add(LightChange(after.start, bright))
            previous = null
        }
    }

    // Only follow a continuous observed run. Missing packets cannot establish when a threshold was crossed.
    fun response(timeline: SensorTimeline, event: LightChange, limit: Double): LightResponse? {
        val baseline = timeline.at(event.time) ?: return null
        val found = timeline.rows.binarySearchBy(event.time) { it.timestampEpochMs }
        var index = if (found >= 0) found + 1 else -found - 1
        var last = baseline
        var two: Int? = null
        var low: Int? = null
        val end = event.time + 60 * LIGHT_MINUTE
        while (index < timeline.rows.size) {
            val row = timeline.rows[index++]
            if (row.timestampEpochMs > end || row.timestampEpochMs - last.timestampEpochMs > READING_LIFETIME) break
            last = row
            val minutes = ((row.timestampEpochMs - event.time) / LIGHT_MINUTE).toInt()
            val delta = row.temperatureF - baseline.temperatureF
            if (two == null && (if (event.brightening) delta >= 2 else delta <= -2)) two = minutes
            if (low == null && baseline.temperatureF >= limit && row.temperatureF < limit) low = minutes
        }
        if (last.timestampEpochMs <= event.time) return null
        return LightResponse(last.temperatureF - baseline.temperatureF,
            ((last.timestampEpochMs - event.time) / LIGHT_MINUTE).toInt(), two, low)
    }

    data class Comparison(val nights: Int, val crossings: Int, val twoDegreeTimes: List<Int>) {
        val coldWatch get() = nights >= 3 && crossings * 3 >= nights * 2
    }
    fun compare(events: List<LightChange>, event: LightChange, timeline: SensorTimeline, zone: ZoneId,
                limit: Double, outdoorAt: (Long) -> Double?): Comparison? {
        val outside = outdoorAt(event.time) ?: return null
        val inside = timeline.at(event.time)?.temperatureF ?: return null
        fun date(time: Long) = Instant.ofEpochMilli(time).atZone(zone).toLocalDate()
        val today = date(event.time)
        val visits = events.asSequence().filter { it.brightening == event.brightening && date(it.time) < today }
            .mapNotNull { prior ->
                val out = outdoorAt(prior.time) ?: return@mapNotNull null
                val start = timeline.at(prior.time)?.temperatureF ?: return@mapNotNull null
                if (abs(out - outside) > 3 || abs(start - inside) > 3 || (!event.brightening && start < limit)) return@mapNotNull null
                response(timeline, prior, limit)?.takeIf { it.minutes >= 55 }?.let { date(prior.time) to it }
            }.distinctBy { it.first }.map { it.second }.toList()
        return visits.takeIf { it.size >= 3 }?.let { Comparison(it.size, it.count { r -> r.lowAt != null }, it.mapNotNull { r -> r.twoDegreesAt }.sorted()) }
    }
}
