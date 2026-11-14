package studio.junecity.greenhouse.data

import studio.junecity.greenhouse.model.GreenhouseConfig
import studio.junecity.greenhouse.model.GreenhouseShape
import studio.junecity.greenhouse.model.HistoryReading
import studio.junecity.greenhouse.model.WeatherReading
import java.time.Instant
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

internal const val HEATING_HOUR = 3_600_000L
internal enum class EnergyUnit(val fuel: String, val label: String, val btu: Double, val delivery: Double, val lowEfficiency: Double, val highEfficiency: Double) {
    Kwh("Electric", "kWh", 3412.1416, 1.0, 1.0, 1.0),
    Therm("Natural gas", "therm", 100000.0, .8, .7, .95),
    Propane("Propane", "gal", 91502.0, .8, .7, .95),
    Wood("Wood", "million Btu", 1000000.0, .65, .5, .8)
}
internal data class HeatingSettings(val unit: EnergyUnit = EnergyUnit.Kwh)
internal data class HeatCoefficient(val nominal: Double, val low: Double, val high: Double)

internal fun heatingCoefficient(house: GreenhouseConfig, unit: EnergyUnit): HeatCoefficient {
    val outline = house.outline()
    val perimeter = if (house.shape == GreenhouseShape.Circle) Math.PI * house.lengthFeet else
        outline.indices.sumOf { i ->
            val a = outline[i]
            val b = outline[(i + 1) % outline.size]
            hypot((a.x - b.x) * house.planLengthFeet, (a.y - b.y) * house.planWidthFeet)
        }
    val height = house.heightFeet ?: 8.0
    val cover = perimeter * height * .8 + house.areaSquareFeet * 1.1
    val volume = height * .9 * house.areaSquareFeet
    // Cover transmission plus sensible infiltration, in Btu per hour per Fahrenheit degree.
    fun units(u: Double, changes: Double, efficiency: Double) =
        (cover * u + .018 * volume * changes) / (unit.btu * efficiency)
    return HeatCoefficient(units(.95, 1.0, unit.delivery),
        units(.7, .5, unit.highEfficiency),
        units(1.2, 2.0, unit.lowEfficiency))
}

internal data class NightHour(val start: Long, val end: Long, val outdoor: Double?, val target: Double, val observed: Boolean = false) {
    val hours get() = (end - start).toDouble() / HEATING_HOUR
    val delta get() = outdoor?.let { target - it }
    val degreeHours get() = delta?.let { max(0.0, it) * hours }
}
internal data class HeatingNight(val date: LocalDate, val hours: List<NightHour>) {
    val degreeHours get() = hours.takeIf { it.isNotEmpty() && it.all { h -> h.degreeHours != null } }
        ?.sumOf { it.degreeHours!! }
    val eightDegreeHours: Double? get() {
        val start = hours.firstOrNull()?.start ?: return null
        val end = start + 8 * HEATING_HOUR
        if (hours.last().end < end) return null
        val window = hours.takeWhile { it.start < end }.map { it.copy(end = min(it.end, end)) }
        return HeatingNight(date, window).degreeHours
    }
    val outdoorMean get() = degreeHours?.let { hours.sumOf { it.outdoor!! * it.hours } / hours.sumOf { it.hours } }
}

internal object NightHeating {
    private fun bounds(date: LocalDate, weather: WeatherReading): LongRange? {
        val sunset = weather.solarDays[date]?.sunsetMs ?: return null
        val sunrise = weather.solarDays[date.plusDays(1)]?.sunriseMs ?: return null
        return (sunset until sunrise).takeIf { sunrise - sunset in 1..24 * HEATING_HOUR }
    }
    fun forecast(weather: WeatherReading, target: Double, now: Long): List<HeatingNight> {
        val today = Instant.ofEpochMilli(now).atZone(weather.zone).toLocalDate()
        val previous = today.minusDays(1)
        val start = if (bounds(previous, weather)?.contains(now) == true) previous else today
        return (0L..6L).map { night(weather, target, start.plusDays(it), now) }
    }

    fun night(weather: WeatherReading, target: Double, date: LocalDate, now: Long): HeatingNight {
        val window = bounds(date, weather) ?: return HeatingNight(date, emptyList())
        val intervals = buildList {
            var t = window.first
            while (t <= window.last) {
                val hour = Math.floorDiv(t, HEATING_HOUR) * HEATING_HOUR
                val end = min(hour + HEATING_HOUR, window.last + 1)
                val observation = weather.stationHours[hour]?.takeIf { it.lastReportMs <= now }
                if (observation != null) {
                    val observedEnd = if (hour + HEATING_HOUR <= now) end else min(end, observation.lastReportMs)
                    if (observedEnd > t) {
                        add(NightHour(t, observedEnd, observation.temperatureF, target, observed = true))
                        t = observedEnd
                    }
                }
                if (t < end) add(NightHour(t, end, weather.hourlyOutdoorF[hour], target))
                t = end
            }
        }
        return HeatingNight(date, intervals)
    }

    // One vote per sensor per quarter-hour. Compare rates only across consecutive bins with the same sensors.
    fun observations(weather: WeatherReading, readings: List<HistoryReading>, sensorIds: Set<String>, now: Long): List<ThermalPoint> {
        val windows = weather.solarDays.keys.mapNotNull { date -> bounds(date, weather)?.let { date to it } }.sortedBy { it.second.first }
        val bins = mutableMapOf<Pair<LocalDate, Long>, MutableMap<String, MutableList<Double>>>()
        for (r in readings) {
            if (r.sensorId !in sensorIds || !r.temperatureF.isFinite() || r.timestampEpochMs > now) continue
            val day = windows.firstOrNull { r.timestampEpochMs in it.second }?.first ?: continue
            val bucket = Math.floorDiv(r.timestampEpochMs, HEATING_HOUR / 4) * (HEATING_HOUR / 4)
            bins.getOrPut(day to bucket) { mutableMapOf() }.getOrPut(r.sensorId) { mutableListOf() }.add(r.temperatureF)
        }
        return bins.mapNotNull { (key, sensors) ->
            val hour = Math.floorDiv(key.second, HEATING_HOUR) * HEATING_HOUR
            val outdoor = weather.stationHours[hour]?.temperatureF ?: weather.hourlyOutdoorF[hour] ?: return@mapNotNull null
            ThermalPoint(key.first, key.second, sensors.values.map { it.average() }.average(), outdoor, sensors.keys.sorted())
        }.sortedBy { it.time }
    }
}

internal data class ThermalPoint(val date: LocalDate, val time: Long, val indoor: Double, val outdoor: Double, val sensors: List<String>)
internal data class LearnedTemperature(val fahrenheit: Double, val nights: Int)

// Give each night one vote. Overlapping windows must not give a long night more weight.
internal fun learnedTemperature(points: List<ThermalPoint>): LearnedTemperature? {
    fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        return (sorted[(sorted.size - 1) / 2] + sorted[sorted.size / 2]) / 2
    }
    val nightly = points.groupBy { it.date }.toSortedMap(compareByDescending { it }).values.mapNotNull { night ->
        val centers = night.sortedBy { it.time }.windowed(5).mapNotNull window@{ window ->
            val first = window.first()
            if (window.any { !it.indoor.isFinite() || !it.outdoor.isFinite() || it.sensors.isEmpty() ||
                    it.sensors != first.sensors || it.indoor - it.outdoor < 2.0 }) return@window null
            if (window.zipWithNext().any { (a, b) -> b.time - a.time != HEATING_HOUR / 4 }) return@window null
            val temps = window.map { it.indoor }
            if (temps.max() - temps.min() > 2.0 || abs(temps.last() - temps.first()) > 1.0) null else median(temps)
        }
        if (centers.isEmpty()) null else median(centers)
    }.take(30)
    return if (nightly.isEmpty()) null else LearnedTemperature(median(nightly), nightly.size)
}

internal data class NightBehavior(val nights: Int, val hours: Double, val nearTarget: Double, val cooling: Double?,
    val recovery: Double?, val swing: Double, val indoorDelta: Double)
internal fun nightBehavior(points: List<ThermalPoint>, target: Double, comparableOutdoor: Double? = null): NightBehavior? {
    val nights = points.groupBy { it.date }.values.mapNotNull { night ->
        val intervals = night.sortedBy { it.time }.zipWithNext().filter { (a, b) ->
            b.time - a.time == HEATING_HOUR / 4 && a.sensors == b.sensors
        }
        // Require at least one observed hour per night before using it for comparisons.
        intervals.takeIf { it.size >= 4 && (comparableOutdoor == null ||
            abs(it.map { (a,b) -> (a.outdoor+b.outdoor)/2 }.average() - comparableOutdoor) <= 5) }
    }
    if (nights.isEmpty()) return null
    val pairs = nights.flatten()
    val rates = pairs.map { (a,b) -> (b.indoor - a.indoor) * 4 }
    fun List<Double>.meanOrNull() = takeIf { it.isNotEmpty() }?.average()
    return NightBehavior(nights.size, pairs.size * .25,
        pairs.count { (a,b) -> abs(a.indoor-target) <= 2 && abs(b.indoor-target) <= 2 }.toDouble()/pairs.size,
        rates.filter { it < 0 }.map { -it }.meanOrNull(), rates.filter { it > 0 }.meanOrNull(),
        nights.map { night -> val values = night.flatMap { listOf(it.first.indoor, it.second.indoor) }
            values.max() - values.min() }.average(),
        pairs.map { (a,b) -> (a.indoor-a.outdoor+b.indoor-b.outdoor)/2 }.average())
}
