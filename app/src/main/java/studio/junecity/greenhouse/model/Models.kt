package studio.junecity.greenhouse.model

import kotlin.math.ln
import kotlin.math.roundToInt

enum class AppScreen { Dashboard, Zones, History, Cameras, Settings }
enum class Metric { Temperature, Humidity, Vpd, DewPoint }
enum class HistoryRange { Day, Week, Month }
data class SensorPosition(val xFeet:Double,val yFeet:Double,val heightFeet:Double=0.0){fun normalized(g:GreenhouseConfig)=NormalizedPosition((xFeet/g.lengthFeet).coerceIn(0.0,1.0),(yFeet/g.widthFeet).coerceIn(0.0,1.0))}
data class NormalizedPosition(val x:Double,val y:Double)
data class GreenhouseConfig(val id:String="main",val name:String="Main Greenhouse",val lengthFeet:Double=16.0,val widthFeet:Double=8.0,val heightFeet:Double?=null,val sensorPositions:Map<String,SensorPosition> = emptyMap()){val areaSquareFeet get()=lengthFeet*widthFeet}
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
data class AppPreferences(val zipCode:String="06382",val temperatureFahrenheit:Boolean=true,val refreshMinutes:Int=1,val historyRetentionDays:Int=30,val keepScreenOn:Boolean=true,val autoStart:Boolean=false,val greenhouses:List<GreenhouseConfig> = listOf(GreenhouseConfig()),val activeGreenhouseId:String="main",val notifications:NotificationPreferences=NotificationPreferences(),val sensorGreenhouseIds:Map<String,String> = emptyMap()){val activeGreenhouse get()=greenhouses.firstOrNull{it.id==activeGreenhouseId}?:greenhouses.first()}
data class GreenhouseUiState(val screen:AppScreen=AppScreen.Dashboard,val metric:Metric=Metric.Temperature,val sensors:List<GoveeSensor> = emptyList(),val weather:WeatherReading=WeatherReading("06382","Outside (06382)",null,"Unavailable"),val history:List<HistoryReading> = emptyList(),val preferences:AppPreferences=AppPreferences(),val goveeConnected:Boolean=false,val refreshing:Boolean=false,val message:String?=null)
