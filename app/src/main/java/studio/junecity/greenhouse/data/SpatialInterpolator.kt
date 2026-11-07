package studio.junecity.greenhouse.data

import studio.junecity.greenhouse.model.*
import kotlin.math.hypot

data class SpatialSample(val xFeet:Double,val yFeet:Double,val value:Double)
object SpatialInterpolator{
    fun estimate(samples:List<SpatialSample>,xFeet:Double,yFeet:Double,power:Double=2.0):Double?{if(samples.isEmpty())return null;samples.firstOrNull{hypot(it.xFeet-xFeet,it.yFeet-yFeet)<.01}?.let{return it.value};var total=0.0;var weights=0.0;samples.forEach{val d=hypot(it.xFeet-xFeet,it.yFeet-yFeet).coerceAtLeast(.05);val w=1.0/Math.pow(d,power);total+=it.value*w;weights+=w};return total/weights}
    fun center(samples:List<SpatialSample>,g:GreenhouseConfig)=estimate(samples,g.lengthFeet/2,g.widthFeet/2)
    fun areaAverage(samples:List<SpatialSample>,g:GreenhouseConfig,columns:Int=40,rows:Int=20):Double?{if(samples.isEmpty())return null;var total=0.0;var count=0;for(y in 0 until rows)for(x in 0 until columns)estimate(samples,(x+.5)*g.lengthFeet/columns,(y+.5)*g.widthFeet/rows)?.let{total+=it;count++};return if(count==0)null else total/count}
    fun samples(sensors:List<GoveeSensor>,g:GreenhouseConfig,metric:Metric)=sensors.filter{it.greenhouseId==g.id&&it.isFresh()}.mapNotNull{s->val p=g.sensorPositions[s.id]?:return@mapNotNull null;val v=when(metric){Metric.Temperature->s.temperatureF;Metric.Humidity->s.humidityPercent;Metric.Vpd->s.vpdKpa;Metric.DewPoint->s.dewPointF}?:return@mapNotNull null;SpatialSample(p.xFeet,p.yFeet,v)}
}
