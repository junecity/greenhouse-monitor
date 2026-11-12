package studio.junecity.greenhouse.data

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import studio.junecity.greenhouse.model.HistoryReading

internal const val READING_LIFETIME = 10 * 60_000L

internal data class NightWindow(val start: Long, val end: Long) {
    companion object {
        fun at(now: Long, zone: ZoneId = ZoneId.systemDefault()): NightWindow {
            val local = Instant.ofEpochMilli(now).atZone(zone)
            val date = if (local.hour >= 20) local.toLocalDate() else local.toLocalDate().minusDays(1)
            val start = date.atTime(LocalTime.of(20, 0)).atZone(zone).toInstant().toEpochMilli()
            val end = date.plusDays(1).atTime(LocalTime.of(8, 0)).atZone(zone).toInstant().toEpochMilli()
            return NightWindow(start, minOf(end, now))
        }
    }
}

internal data class NightStats(val coldest: HistoryReading?, val coverageMs: Long, val belowMs: Long)

/** One sensor in chronological order. Only interpolate between consecutive, fresh observations. */
internal class SensorTimeline(records: List<HistoryReading>) {
    val rows = records.filter { it.temperatureF.isFinite() && it.humidityPercent.isFinite() }
        .distinctBy { it.timestampEpochMs }.sortedBy { it.timestampEpochMs }

    fun at(time: Long): HistoryReading? {
        val found = rows.binarySearchBy(time) { it.timestampEpochMs }
        val previous = rows.getOrNull(if (found >= 0) found else -found - 2)
        return previous?.takeIf { time - it.timestampEpochMs <= READING_LIFETIME }
    }

    fun summarize(window: NightWindow, threshold: Double): NightStats {
        val coldest = rows.filter { it.timestampEpochMs in window.start..window.end }.minByOrNull { it.temperatureF }
        var coverage = 0L
        var below = 0L
        rows.zipWithNext { a, b ->
            val span = b.timestampEpochMs - a.timestampEpochMs
            val start = maxOf(a.timestampEpochMs, window.start)
            val end = minOf(b.timestampEpochMs, window.end)
            if (span <= READING_LIFETIME && end > start) {
                coverage += end - start
                fun temp(t: Long) = a.temperatureF + (b.temperatureF - a.temperatureF) * (t - a.timestampEpochMs) / span
                val lo = minOf(temp(start), temp(end))
                val hi = maxOf(temp(start), temp(end))
                val fraction = when {
                    threshold <= lo -> 0.0
                    threshold >= hi -> 1.0
                    else -> (threshold - lo) / (hi - lo)
                }
                below += ((end - start) * fraction).toLong()
            }
        }
        return NightStats(coldest, coverage, below)
    }
}
