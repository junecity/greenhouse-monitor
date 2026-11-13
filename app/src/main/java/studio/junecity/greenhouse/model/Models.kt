package studio.junecity.greenhouse.model

import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToInt

enum class AppScreen { Dashboard, Zones, History, Cameras, Settings }
enum class Metric { Temperature, Humidity, Vpd, DewPoint }
enum class HistoryRange { Day, Week, Month }
enum class CameraMode { CameraServer, ViewerClient }
enum class CameraStreamQuality { DataSaver, Balanced, High }
enum class GreenhouseShape { Rectangle, TShape, Drawn, LShape, Circle }
data class SensorPosition(val xFeet:Double,val yFeet:Double,val heightFeet:Double=0.0){fun normalized(g:GreenhouseConfig)=NormalizedPosition((xFeet/g.planLengthFeet).coerceIn(0.0,1.0),(yFeet/g.planWidthFeet).coerceIn(0.0,1.0))}
data class NormalizedPosition(val x:Double,val y:Double)
data class GreenhouseConfig(val id:String="main",val name:String="Main Greenhouse",val lengthFeet:Double=16.0,val widthFeet:Double=8.0,val heightFeet:Double?=null,val sensorPositions:Map<String,SensorPosition> = emptyMap(),val shape:GreenhouseShape=GreenhouseShape.Rectangle,val attachedLengthFeet:Double=16.0,val attachedWidthFeet:Double=8.0,val refreshMinutes:Int=1,val vertices:List<NormalizedPosition> = emptyList()){
    val hasStem get() = shape == GreenhouseShape.TShape || shape == GreenhouseShape.LShape
    fun withPreset(preset: GreenhouseShape): GreenhouseConfig {
        require(preset != GreenhouseShape.Drawn)
        return copy(shape=preset, widthFeet=if (preset == GreenhouseShape.Circle) lengthFeet else widthFeet, vertices=emptyList())
    }
    fun outline(): List<NormalizedPosition> = when (shape) {
        GreenhouseShape.Drawn -> vertices
        GreenhouseShape.Circle -> List(48) { i ->
            val angle = 2*kotlin.math.PI*i/48
            NormalizedPosition((1+kotlin.math.cos(angle))/2, (1+kotlin.math.sin(angle))/2)
        }
        GreenhouseShape.Rectangle -> listOf(NormalizedPosition(0.0,0.0),NormalizedPosition(1.0,0.0),
            NormalizedPosition(1.0,1.0),NormalizedPosition(0.0,1.0))
        GreenhouseShape.LShape, GreenhouseShape.TShape -> {
            val bar = widthFeet/planWidthFeet
            val left = crossbarLeftFeet/planLengthFeet
            val right = (crossbarLeftFeet+lengthFeet)/planLengthFeet
            val stem = stemLeftFeet/planLengthFeet
            val end = (stemLeftFeet+attachedWidthFeet)/planLengthFeet
            listOf(left to 0.0,right to 0.0,right to bar,end to bar,end to 1.0,stem to 1.0,stem to bar,left to bar)
                .distinct().map { NormalizedPosition(it.first,it.second) }
        }
    }
    val planLengthFeet get()=if(hasStem)max(lengthFeet,attachedWidthFeet) else lengthFeet
    val planWidthFeet get()=if(hasStem)widthFeet+attachedLengthFeet else if(shape==GreenhouseShape.Circle) lengthFeet else widthFeet
    val crossbarLeftFeet get()=if(shape==GreenhouseShape.TShape)(planLengthFeet-lengthFeet)/2.0 else 0.0
    val stemLeftFeet get()=if(shape==GreenhouseShape.TShape)(planLengthFeet-attachedWidthFeet)/2.0 else 0.0
    val areaSquareFeet get()=if(shape==GreenhouseShape.Circle) kotlin.math.PI*lengthFeet*lengthFeet/4 else if(shape==GreenhouseShape.Drawn) lengthFeet*widthFeet*DrawnPlan.area(vertices) else lengthFeet*widthFeet+if(hasStem)attachedLengthFeet*attachedWidthFeet else 0.0
    fun containsPoint(xFeet:Double,yFeet:Double):Boolean{
        if(shape==GreenhouseShape.Circle) {
            val r = lengthFeet/2
            return kotlin.math.hypot(xFeet-r,yFeet-r) <= r+1e-9
        }
        if(shape==GreenhouseShape.Drawn)return DrawnPlan.contains(NormalizedPosition(xFeet/lengthFeet,yFeet/widthFeet),vertices)
        if(xFeet in crossbarLeftFeet..(crossbarLeftFeet+lengthFeet)&&yFeet in 0.0..widthFeet)return true
        if(!hasStem||yFeet !in widthFeet..planWidthFeet)return false
        return xFeet in stemLeftFeet..(stemLeftFeet+attachedWidthFeet)
    }
    fun coercePoint(xFeet:Double,yFeet:Double):SensorPosition{
        if(shape==GreenhouseShape.Circle) {
            val r = lengthFeet/2
            val scale = r / kotlin.math.hypot(xFeet-r,yFeet-r).coerceAtLeast(r)
            return SensorPosition(r+(xFeet-r)*scale,r+(yFeet-r)*scale)
        }
        if(shape==GreenhouseShape.Drawn && vertices.isNotEmpty()){
            if(containsPoint(xFeet,yFeet))return SensorPosition(xFeet,yFeet)
            val edge=DrawnPlan.nearest(NormalizedPosition(xFeet,yFeet),vertices.map{NormalizedPosition(it.x*lengthFeet,it.y*widthFeet)})
            return SensorPosition(edge.x,edge.y)
        }
        val y=yFeet.coerceIn(0.0,planWidthFeet)
        val range=if(hasStem&&y>widthFeet) stemLeftFeet..(stemLeftFeet+attachedWidthFeet) else crossbarLeftFeet..(crossbarLeftFeet+lengthFeet)
        return SensorPosition(xFeet.coerceIn(range.start,range.endInclusive),y)
    }
    fun reproject(position:SensorPosition,from:GreenhouseConfig):SensorPosition{
        val normalized=position.normalized(from)
        return coercePoint(normalized.x*planLengthFeet,normalized.y*planWidthFeet).copy(heightFeet=position.heightFeet)
    }
}
data class GoveeSensor(val id:String,val sku:String,val name:String,val temperatureF:Double?=null,val humidityPercent:Double?=null,val online:Boolean=false,val updatedAtEpochMs:Long=0,val greenhouseId:String="main",val zone:String="Unassigned"){
    fun isFresh(now:Long=System.currentTimeMillis(),staleAfterMs:Long=10*60_000L)=online&&temperatureF!=null&&humidityPercent!=null&&now-updatedAtEpochMs<=staleAfterMs
    val roundedTemperature get()=temperatureF?.roundToInt();val roundedHumidity get()=humidityPercent?.roundToInt()
    val dewPointF:Double? get(){val t=temperatureF?:return null;val rh=humidityPercent?:return null;val c=(t-32)*5/9;val a=17.625;val b=243.04;val gamma=ln(rh.coerceAtLeast(.1)/100)+a*c/(b+c);return (b*gamma/(a-gamma))*9/5+32}
    val vpdKpa:Double? get(){val t=temperatureF?:return null;val rh=humidityPercent?:return null;val c=(t-32)*5/9;return .6108*kotlin.math.exp(17.27*c/(c+237.3))*(1-rh/100)}
}
data class GreenhouseReading(val temperatureF:Double?,val humidityPercent:Double?,val validSensorCount:Int,val stale:Boolean=false)
data class WeatherReading(val zipCode:String,val location:String,val temperatureF:Double?,val condition:String,val updatedAtEpochMs:Long=0,val stale:Boolean=false)
data class DeviceStatus(val batteryPercent:Int,val charging:Boolean,val wifiConnected:Boolean)
data class HistoryReading(val sensorId:String,val sensorName:String,val temperatureF:Double,val humidityPercent:Double,val timestampEpochMs:Long)
data class NotificationPreferences(val masterEnabled:Boolean=true,val popupsEnabled:Boolean=false,val soundEnabled:Boolean=false,val vibrationEnabled:Boolean=false,val repeatedAlerts:Boolean=false,val repeatMinutes:Int=30,val snoozedUntilEpochMs:Long=0,val temperatureAlerts:Boolean=true,val humidityAlerts:Boolean=true,val offlineAlerts:Boolean=true,val connectionAlerts:Boolean=false,val lowTemperatureF:Double=45.0,val highTemperatureF:Double=95.0,val lowHumidity:Double=30.0,val highHumidity:Double=85.0,val recoveryMargin:Double=2.0)
object DataSyncIntervals {
    val minutes = listOf(1, 5, 10, 15)
    fun normalize(value: Int): Int = minutes.minBy { kotlin.math.abs(it - value) }
}

data class AppPreferences(val zipCode:String="06382",val temperatureFahrenheit:Boolean=true,val refreshMinutes:Int=1,val historyRetentionDays:Int=30,val keepScreenOn:Boolean=true,val autoStart:Boolean=false,val greenhouses:List<GreenhouseConfig> = listOf(GreenhouseConfig()),val activeGreenhouseId:String="main",val notifications:NotificationPreferences=NotificationPreferences(),val sensorGreenhouseIds:Map<String,String> = emptyMap(),val cameraMode:CameraMode=CameraMode.CameraServer,val cameraStreamQuality:CameraStreamQuality=CameraStreamQuality.Balanced){val activeGreenhouse get()=greenhouses.firstOrNull{it.id==activeGreenhouseId}?:greenhouses.first();val activeRefreshMinutes get()=DataSyncIntervals.normalize(activeGreenhouse.refreshMinutes)}
data class GreenhouseUiState(val screen:AppScreen=AppScreen.Dashboard,val metric:Metric=Metric.Temperature,val sensors:List<GoveeSensor> = emptyList(),val weather:WeatherReading=WeatherReading("06382","Outside (06382)",null,"Unavailable"),val history:List<HistoryReading> = emptyList(),val preferences:AppPreferences=AppPreferences(),val standalone:Boolean=false,val goveeConnected:Boolean=false,val refreshing:Boolean=false,val message:String?=null)
