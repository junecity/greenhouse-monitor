package studio.junecity.greenhouse.data

import studio.junecity.greenhouse.model.*
import java.time.Instant
import kotlin.math.*

internal data class ZoneProbe(val sensor: GoveeSensor, val position: SensorPosition)
internal data class AirCell(val x: Double, val y: Double, val z: Double, val value: Double, val opacity: Double)

internal object ZoneClimate {
    fun probes(state: GreenhouseUiState, now: Long): List<ZoneProbe> {
        val house = state.preferences.activeGreenhouse
        return state.sensors.filter { it.greenhouseId == house.id && it.updatedAtEpochMs <= now && it.isFresh(now) &&
            it.temperatureF?.isFinite() == true && it.humidityPercent?.isFinite() == true }
            .mapNotNull { sensor ->
                house.sensorPositions[sensor.id]?.takeIf { house.containsPoint(it.xFeet,it.yFeet) }
                    ?.let { ZoneProbe(sensor,it) }
            }
    }

    fun height(g: GreenhouseConfig) = g.heightFeet ?: max(8.0, g.sensorPositions.values.maxOfOrNull { it.heightFeet ?: 0.0 } ?: 0.0)
    private fun radius(g: GreenhouseConfig) = (min(g.planLengthFeet,g.planWidthFeet)*.42).coerceIn(2.0,7.0)
    private fun distance(x: Double,y: Double,z: Double,p: SensorPosition): Double {
        val h = p.heightFeet ?: return Double.POSITIVE_INFINITY
        return (x-p.xFeet).pow(2)+(y-p.yFeet).pow(2)+((z-h)*1.35).pow(2)
    }

    // Bounded grid, calculated only when inputs change. Neutral space is outside local measurement coverage.
    fun field(g: GreenhouseConfig, probes: List<ZoneProbe>, metric: Metric): List<AirCell> {
        val placed = probes.filter { it.position.heightFeet != null }
        if (placed.isEmpty()) return emptyList()
        val h = height(g)
        val step = maxOf(g.planLengthFeet/25,g.planWidthFeet/25,h/13,.3)
        val range = radius(g).pow(2)
        return buildList {
            for (i in 0 until ceil(g.planLengthFeet/step).toInt()) for (j in 0 until ceil(g.planWidthFeet/step).toInt()) {
                val x=(i+.5)*step; val y=(j+.5)*step
                if (!g.containsPoint(x,y)) continue
                for (k in 0 until ceil(h/step).toInt()) {
                    val z=(k+.5)*step
                    if (z>h) continue
                    var nearest=range; var sum=0.0; var weights=0.0
                    for ((s,p) in placed) {
                        val d=distance(x,y,z,p)
                        if (d>=range) continue
                        val v=if(metric==Metric.Humidity)s.humidityPercent else s.temperatureF
                        if(v==null || !v.isFinite()) continue
                        val w=(1-d/range).pow(2)/(.15+d)
                        sum+=w*v; weights+=w; nearest=min(nearest,d)
                    }
                    if(weights>0) add(AirCell(x,y,z,sum/weights,(1-nearest/range).pow(1.4)))
                }
            }
        }
    }

    private const val HOUR=3_600_000L
    // Match previous readings in the same local night/day period and similar modeled outdoor conditions.
    // Report a span of observed daily medians, never extrapolate an indoor/outdoor transfer coefficient.
    private fun observedRange(rows: List<HistoryReading>, sensor: GoveeSensor, weather: WeatherReading,
                              target: Map.Entry<Long,Double>, now: Long): String? {
        val targetHour=Instant.ofEpochMilli(target.key).atZone(weather.zone).hour
        val matches = rows.asSequence().filter { it.sensorId==sensor.id && it.timestampEpochMs in (now-7*24*HOUR)..(now-HOUR) }
            .filter { row ->
                val h=Instant.ofEpochMilli(row.timestampEpochMs).atZone(weather.zone)
                val offset=abs(h.hour-targetHour)
                val outside=weather.hourlyOutdoorF[h.withMinute(0).withSecond(0).withNano(0).toInstant().toEpochMilli()]
                outside!=null && abs(outside-target.value)<=2 && min(offset,24-offset)<=2 && row.temperatureF.isFinite()
            }.groupBy { Instant.ofEpochMilli(it.timestampEpochMs).atZone(weather.zone).toLocalDate() }
        val medians=matches.values.filter { day ->
            day.maxOf{it.timestampEpochMs}-day.minOf{it.timestampEpochMs}>=30*60_000L
        }.map { day -> day.map { it.temperatureF }.sorted()[day.size/2] }
        if(medians.size<3) return null
        val lo=floor(medians.min()).toInt();val hi=ceil(medians.max()).toInt()
        return if(lo==hi) "${lo}°F" else "$lo–${hi}°F"
    }

    fun insight(state: GreenhouseUiState, probes: List<ZoneProbe>, now: Long): String {
        val g=state.preferences.activeGreenhouse
        val cold=probes.minByOrNull { it.sensor.temperatureF!! }?.sensor ?: return "Waiting for fresh, mapped readings."
        val temp=cold.temperatureF!!
        val limit=state.preferences.notifications.lowTemperatureF
        if(temp<limit) return "${cold.name} is ${temp.roundToInt()}°F, below your ${limit.roundToInt()}°F limit. Check heat in this area."
        val weather=state.weather
        if(!weather.stale && weather.zipCode==state.preferences.zipCode && now-weather.updatedAtEpochMs in 0L..3*HOUR) {
            val outside=weather.temperatureF
            val target=weather.hourlyOutdoorF.entries.filter { it.key in (now+1)..(now+12*HOUR) }.minByOrNull { it.value }
            if(outside!=null && target!=null && outside-target.value>=5) {
                val range=observedRange(state.history,cold,weather,target,now)
                return if(range!=null) "Outside may reach ${target.value.roundToInt()}°F within 12h. In similar conditions, ${cold.name} was $range."
                else "Outside may fall ${(outside-target.value).roundToInt()}°F within 12h. Watch ${cold.name}, currently ${temp.roundToInt()}°F."
            }
        }
        val warm=probes.maxOf { it.sensor.temperatureF!! }
        if(warm-temp>=3) return "${cold.name} is ${(warm-temp).roundToInt()}°F cooler than the warmest sensor. Monitor this spot."
        val mounted=probes.filter { it.position.heightFeet!=null }
        if(mounted.isEmpty()) return "Set sensor heights to see how conditions vary from floor to roof."
        val h=height(g)
        // Find a real, inside-the-footprint sample location furthest from a mounted, reporting sensor.
        var gap=0.0;var location=""
        for(i in 0 until 6) for(j in 0 until 6) {
            val x=g.planLengthFeet*(i+.5)/6; val y=g.planWidthFeet*(j+.5)/6
            if(!g.containsPoint(x,y)) continue
            for(z in listOf(h*.15,h*.8)){
                val nearest=mounted.minOf { distance(x,y,z,it.position) }
                if(nearest>gap){gap=nearest;location="${if(z>h/2)"upper" else "lower"} ${if(j<3)"back" else "front"}-${if(i<3)"left" else "right"} area (floor plan)"}
            }
        }
        return if(gap>radius(g).pow(2)) "Try a sensor in the $location to improve coverage."
        else "Measured zones are within ${ceil(warm-temp).toInt()}°F of one another."
    }
}
