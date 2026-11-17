package studio.junecity.greenhouse.data

import studio.junecity.greenhouse.model.CURRENT_READING_MAX_AGE_MS
import studio.junecity.greenhouse.model.GoveeSensor
import studio.junecity.greenhouse.model.GreenhouseConfig
import studio.junecity.greenhouse.model.HistoryReading

internal const val WIDGET_WINDOW_MS = 6 * 3_600_000L
internal const val WIDGET_BUCKET_MS = 5 * 60_000L

internal data class ClimateBucket(
    val at: Long, val temperatureF: Double, val humidity: Double, val contributors: Set<String> = emptySet()
) {
    fun continuesFrom(previous: ClimateBucket) =
        at - previous.at in 1..CURRENT_READING_MAX_AGE_MS && contributors == previous.contributors
}

/** Sample the Dashboard's spatial mean at real observation times, at most once per five minutes. */
internal fun climateTrend(sensors: List<GoveeSensor>, house: GreenhouseConfig,
                          history: List<HistoryReading>, now: Long): List<ClimateBucket> {
    val selected = sensors.filter { it.greenhouseId == house.id }.associateBy { it.id }
    if (selected.isEmpty()) return emptyList()
    val start = now - WIDGET_WINDOW_MS
    val current = selected.values.mapNotNull { sensor ->
        val temp = sensor.temperatureF ?: return@mapNotNull null
        val rh = sensor.humidityPercent ?: return@mapNotNull null
        HistoryReading(sensor.id, sensor.name, temp, rh, sensor.updatedAtEpochMs)
    }
    val buckets = (history.asSequence() + current.asSequence()).filter { row ->
        row.sensorId in selected && row.timestampEpochMs > 0 && row.timestampEpochMs in (start - CURRENT_READING_MAX_AGE_MS)..now &&
            row.temperatureF.isFinite() && row.humidityPercent in 0.0..100.0
    }.groupBy { it.timestampEpochMs / WIDGET_BUCKET_MS }.toSortedMap()

    val latest = mutableMapOf<String, HistoryReading>()
    val weightsByGroup = mutableMapOf<List<String>, DoubleArray?>()
    return buildList {
        for (observations in buckets.values) {
            for (row in observations) {
                if (row.timestampEpochMs >= (latest[row.sensorId]?.timestampEpochMs ?: 0L)) latest[row.sensorId] = row
            }
            val newest = observations.maxOf { it.timestampEpochMs }
            val placed = latest.values.filter { it.sensorId in house.sensorPositions && newest - it.timestampEpochMs <= CURRENT_READING_MAX_AGE_MS }
            val at = placed.maxOfOrNull { it.timestampEpochMs } ?: newest
            if (at < start || at <= (lastOrNull()?.at ?: 0L)) continue
            // An unplaced sensor reporting after a placed sample cannot advance its timestamp
            // or expire a peer early. Evaluate everyone at the actual plotted instant.
            val contributors = selected.keys.filter { id ->
                (placed.isEmpty() || id in house.sensorPositions) &&
                    latest[id]?.let { at - it.timestampEpochMs in 0..CURRENT_READING_MAX_AGE_MS } == true
            }
            val weights = weightsByGroup.getOrPut(contributors) {
                when {
                    contributors.size == 1 -> doubleArrayOf(1.0)
                    placed.isEmpty() -> DoubleArray(contributors.size) { 1.0 / contributors.size }
                    else -> SpatialInterpolator.areaWeights(contributors.map { house.sensorPositions.getValue(it) }, house)
                }
            } ?: continue
            var temperature = 0.0
            var humidity = 0.0
            for ((index, id) in contributors.withIndex()) {
                val reading = latest.getValue(id)
                temperature += weights[index] * reading.temperatureF
                humidity += weights[index] * reading.humidityPercent
            }
            add(ClimateBucket(at, temperature, humidity.coerceIn(0.0, 100.0), contributors.toSet()))
        }
    }
}
