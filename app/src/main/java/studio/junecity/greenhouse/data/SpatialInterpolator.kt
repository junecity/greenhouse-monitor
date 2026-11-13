package studio.junecity.greenhouse.data

import studio.junecity.greenhouse.model.*
import kotlin.math.hypot

data class SpatialSample(val xFeet:Double,val yFeet:Double,val value:Double)
object SpatialInterpolator{
    fun estimate(samples:List<SpatialSample>,xFeet:Double,yFeet:Double,power:Double=2.0):Double?{if(samples.isEmpty())return null;samples.firstOrNull{hypot(it.xFeet-xFeet,it.yFeet-yFeet)<.01}?.let{return it.value};var total=0.0;var weights=0.0;samples.forEach{val d=hypot(it.xFeet-xFeet,it.yFeet-yFeet).coerceAtLeast(.05);val w=1.0/Math.pow(d,power);total+=it.value*w;weights+=w};return total/weights}
    fun center(samples:List<SpatialSample>,g:GreenhouseConfig)=estimate(samples,g.planLengthFeet/2,g.planWidthFeet/2)
    fun areaAverage(samples:List<SpatialSample>,g:GreenhouseConfig,columns:Int=40,rows:Int=20):Double?{if(samples.isEmpty())return null;var total=0.0;var count=0;for(y in 0 until rows)for(x in 0 until columns){val px=(x+.5)*g.planLengthFeet/columns;val py=(y+.5)*g.planWidthFeet/rows;if(g.containsPoint(px,py))estimate(samples,px,py)?.let{total+=it;count++}};return if(count==0)null else total/count}
    private fun value(sensor: GoveeSensor, metric: Metric) = when(metric) {
        Metric.Temperature -> sensor.temperatureF
        Metric.Humidity -> sensor.humidityPercent
        Metric.Vpd -> sensor.vpdKpa
        Metric.DewPoint -> sensor.dewPointF
    }

    fun samples(sensors: List<GoveeSensor>, g: GreenhouseConfig, metric: Metric, at: Long=System.currentTimeMillis()) =
        sensors.filter { it.greenhouseId == g.id && it.isFresh(at) }.mapNotNull { sensor ->
            val position = g.sensorPositions[sensor.id] ?: return@mapNotNull null
            value(sensor, metric)?.let { SpatialSample(position.xFeet, position.yFeet, it) }
        }

    fun greenhouseAverage(sensors: List<GoveeSensor>, g: GreenhouseConfig, metric: Metric, at: Long=System.currentTimeMillis()): Double? {
        val mapped = samples(sensors, g, metric, at)
        if (mapped.isNotEmpty()) return areaAverage(mapped, g)
        val readings = sensors.filter { it.greenhouseId == g.id && it.isFresh(at) }.mapNotNull { value(it, metric) }
        return readings.takeIf { it.isNotEmpty() }?.average()
    }
}
