package studio.junecity.greenhouse.data

import studio.junecity.greenhouse.model.HistoryReading
import studio.junecity.greenhouse.model.WeatherReading
import kotlin.math.expm1

internal data class WaterHeatEstimate(
    val sampledHours: Double,
    val excessBtu: Double,
    val sunsetBtu: Double,
    val avoidedBtu: Double?,
    val savings: Double?,
    val estimatedBill: Double?
)

// A passive, well-mixed barrel exchanging sensible heat with the greenhouse air.
// Use the same nominal shell/infiltration loss and fuel delivery assumptions as NightHeating.
internal object WaterHeat {
    private const val bucketMs = HEATING_HOUR / 12
    private const val responseHours = 12.0
    private data class Sample(val time: Long, val indoor: Double, val barrelAir: Double, val sensors: Set<String>)

    fun estimate(
        readings: List<HistoryReading>, ids: Set<String>, weather: WeatherReading, night: HeatingNight,
        now: Long, targetF: Double, gallons: Double, fuel: EnergyUnit, coefficient: HeatCoefficient, price: Double?, barrelSensorId: String? = null
    ): WaterHeatEstimate? {
        if (!gallons.isFinite() || gallons <= 0 || !targetF.isFinite()) return null
        val day = weather.solarDays[night.date] ?: return null
        val dawn = day.sunriseMs ?: return null
        val dusk = day.sunsetMs ?: return null
        if (dusk <= dawn) return null
        val end = minOf(now, dusk)
        val samples = readings.asSequence().filter {
            it.sensorId in ids && it.timestampEpochMs in dawn..end && it.temperatureF.isFinite()
        }.groupBy { it.timestampEpochMs / bucketMs }.toSortedMap().values.mapNotNull { rows ->
            val sensors = rows.groupBy { it.sensorId }
            val bySensor = sensors.mapValues { (_, group) -> group.map { it.temperatureF }.average() }
            val indoor = bySensor.values.average()
            val barrelAir = if (barrelSensorId == null) indoor else bySensor[barrelSensorId] ?: return@mapNotNull null
            Sample(sensors.values.map { group -> group.map { it.timestampEpochMs }.average() }.average().toLong(),
                indoor, barrelAir, sensors.keys)
        }
        val capacity = gallons * 8.34 // Btu per °F for US gallons of water
        val usefulBtuPerUnit = fuel.btu * fuel.delivery
        val lossPerDegreeHour = coefficient.nominal * usefulBtuPerUnit
        var stored = 0.0
        var excess = 0.0
        var coverage = 0.0
        var last = dawn
        fun exchanged(hours: Double) = -expm1(-hours / responseHours)
        for ((a, b) in samples.zipWithNext()) {
            if (a.sensors != b.sensors || b.time - a.time > 2 * bucketMs) continue
            var t = a.time
            while (t < b.time) {
                val hour = Math.floorDiv(t, HEATING_HOUR) * HEATING_HOUR
                val until = minOf(b.time, hour + HEATING_HOUR)
                val outdoor = weather.stationHours[hour]?.takeIf { it.lastReportMs <= now }?.temperatureF
                    ?: weather.hourlyOutdoorF[hour]
                if (outdoor != null && outdoor.isFinite()) {
                    stored *= 1 - exchanged((t - last).toDouble() / HEATING_HOUR)
                    val dt = (until - t).toDouble() / HEATING_HOUR
                    val fraction = ((t - a.time) + (until - t) * .5) / (b.time - a.time)
                    val indoor = a.indoor + fraction * (b.indoor - a.indoor)
                    val surplus = lossPerDegreeHour * (indoor - maxOf(targetF, outdoor)).coerceAtLeast(0.0) * dt
                    val barrelAir = a.barrelAir + fraction * (b.barrelAir - a.barrelAir)
                    val transfer = ((barrelAir - targetF) * capacity - stored) * exchanged(dt)
                    stored = (stored + minOf(surplus, transfer)).coerceAtLeast(0.0)
                    excess += surplus
                    coverage += dt
                    last = until
                }
                t = until
            }
        }
        if (coverage == 0.0) return null
        // Unobserved hours earn no capture. Assume ambient returns to the learned target.
        val sunset = stored * (1 - exchanged((dusk - last).toDouble() / HEATING_HOUR))
        val demand = night.degreeHours?.times(lossPerDegreeHour)
        var remaining = sunset
        val avoided = demand?.let {
            night.hours.sumOf { hour ->
                val release = remaining * exchanged(hour.hours)
                remaining -= release
                minOf(release, requireNotNull(hour.degreeHours) * lossPerDegreeHour)
            }
        }
        val dollarsPerBtu = price?.takeIf { it.isFinite() && it >= 0 }?.div(usefulBtuPerUnit)
        return WaterHeatEstimate(coverage, excess, sunset, avoided,
            avoided?.let { btu -> dollarsPerBtu?.times(btu) },
            demand?.let { btu -> dollarsPerBtu?.times((btu - requireNotNull(avoided)).coerceAtLeast(0.0)) })
    }
}
