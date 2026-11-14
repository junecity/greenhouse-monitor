package studio.junecity.greenhouse.settings

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import studio.junecity.greenhouse.model.*

class AppPreferencesStore(context:Context,private val suffix:String=""){
    private val prefs=context.getSharedPreferences("greenhouse_preferences_v2$suffix",Context.MODE_PRIVATE)
    fun load():AppPreferences=runCatching{prefs.getString("state",null)?.let{decode(JSONObject(it))}?:AppPreferences()}.getOrElse{AppPreferences()}
    fun save(value:AppPreferences){prefs.edit().putString("state",encode(value).toString()).apply()}
    internal fun restore(value:AppPreferences){check(prefs.edit().putString("state",encode(value).toString()).commit())}
    fun clear(){prefs.edit().clear().apply()}
    fun exportJson():JSONObject=encode(load())
    fun importJson(value:JSONObject){save(decode(value))}
    private fun encode(p:AppPreferences):JSONObject{
        val n=p.notifications
        val notifications=JSONObject().put("master",n.masterEnabled).put("popups",n.popupsEnabled).put("sound",n.soundEnabled).put("vibration",n.vibrationEnabled).put("repeat",n.repeatedAlerts).put("repeatMinutes",n.repeatMinutes).put("snooze",n.snoozedUntilEpochMs).put("temp",n.temperatureAlerts).put("humidity",n.humidityAlerts).put("offline",n.offlineAlerts).put("connection",n.connectionAlerts).put("lowT",n.lowTemperatureF).put("highT",n.highTemperatureF).put("lowH",n.lowHumidity).put("highH",n.highHumidity).put("recoveryMargin",n.recoveryMargin)
        val greenhouses=JSONArray()
        p.greenhouses.forEach{g->
            val positions=JSONObject();g.sensorPositions.forEach{(id,pos)->positions.put(id,JSONObject().put("x",pos.xFeet).put("y",pos.yFeet).put("z",pos.heightFeet).put("floorMount",pos.heightFeet == 0.0))}
            val preset = g.shape.takeIf { it == GreenhouseShape.Circle || it == GreenhouseShape.LShape }
            greenhouses.put(JSONObject().put("id",g.id).put("name",g.name).put("length",if(preset!=null) g.planLengthFeet else g.lengthFeet).put("width",if(preset!=null) g.planWidthFeet else g.widthFeet).put("height",g.heightFeet).put("positions",positions).put("shape",if(preset!=null) GreenhouseShape.Drawn.name else g.shape.name).put("preset",preset?.name).put("sectionLength",if(preset!=null) g.lengthFeet else null).put("sectionWidth",if(preset!=null) g.widthFeet else null).put("vertices",JSONArray().also{points->(if(preset!=null) g.outline() else g.vertices).forEach{points.put(JSONArray().put(it.x).put(it.y))}}).put("attachedLength",g.attachedLengthFeet).put("attachedWidth",g.attachedWidthFeet).put("refreshMinutes",DataSyncIntervals.normalize(g.refreshMinutes)))
        }
        val assignments=JSONObject();p.sensorGreenhouseIds.forEach{(sensor,greenhouse)->assignments.put(sensor,greenhouse)}
        return JSONObject().put("zip",p.zipCode).put("fahrenheit",p.temperatureFahrenheit).put("refresh",p.refreshMinutes).put("retention",p.historyRetentionDays).put("screen",p.keepScreenOn).put("boot",p.autoStart).put("active",p.activeGreenhouseId).put("cameraMode",p.cameraMode.name).put("cameraStreamQuality",p.cameraStreamQuality.name).put("notifications",notifications).put("greenhouses",greenhouses).put("sensorGreenhouses",assignments)
    }
    // Same physical range that the corner editor accepts, including fractional custom plans.
    private fun JSONObject.dimension(key:String, fallback:Double) =
        optDouble(key).takeIf { it in 0.1..480.0 } ?: fallback

    internal fun decode(o:JSONObject):AppPreferences{
        val array=o.optJSONArray("greenhouses")?:JSONArray();val greenhouses=mutableListOf<GreenhouseConfig>()
        for(i in 0 until array.length()){
            val x=array.getJSONObject(i);val po=x.optJSONObject("positions")?:JSONObject();val positions=mutableMapOf<String,SensorPosition>()
            po.keys().forEach { id ->
                val p = po.optJSONObject(id) ?: return@forEach
                val px = p.optDouble("x")
                val py = p.optDouble("y")
                if (px.isFinite() && py.isFinite()) {
                    positions[id] = SensorPosition(px, py, p.optDouble("z").takeIf { it in 0.0..480.0 && (it > 0 || p.optBoolean("floorMount")) })
                }
            }
            val points=x.optJSONArray("vertices")?.let{list->(0 until list.length().coerceAtMost(49)).mapNotNull{index->
                list.optJSONArray(index)?.let{NormalizedPosition(it.optDouble(0),it.optDouble(1))}
            }}.orEmpty()
            val shape=runCatching{GreenhouseShape.valueOf(x.optString("shape",GreenhouseShape.Rectangle.name))}.getOrDefault(GreenhouseShape.Rectangle).let{if(it==GreenhouseShape.Drawn && (!DrawnPlan.valid(points) || points.any{v->v.x !in 0.0..1.0 || v.y !in 0.0..1.0})) GreenhouseShape.Rectangle else it}
            val preset = when (x.optString("preset")) {
                "Circle" -> GreenhouseShape.Circle
                "LShape" -> GreenhouseShape.LShape
                else -> null
            }?.takeIf { shape == GreenhouseShape.Drawn }
            val legacyRefresh=DataSyncIntervals.normalize(o.optInt("refresh",1))
            greenhouses+=GreenhouseConfig(x.optString("id","main"),x.optString("name","Main Greenhouse"),if(preset!=null) x.dimension("sectionLength",16.0) else x.dimension("length",16.0),if(preset!=null) x.dimension("sectionWidth",8.0) else x.dimension("width",8.0),x.optDouble("height").takeIf{it in 0.1..480.0},positions,preset ?: shape,x.dimension("attachedLength",16.0),x.dimension("attachedWidth",8.0),DataSyncIntervals.normalize(x.optInt("refreshMinutes",legacyRefresh)),if(preset!=null) emptyList() else points)
        }
        if(greenhouses.isEmpty())greenhouses+=GreenhouseConfig()
        val n=o.optJSONObject("notifications")?:JSONObject()
        val assignmentsJson=o.optJSONObject("sensorGreenhouses")?:JSONObject();val assignments=mutableMapOf<String,String>();assignmentsJson.keys().forEach{id->assignments[id]=assignmentsJson.optString(id)}
        val cameraMode=runCatching{CameraMode.valueOf(o.optString("cameraMode",CameraMode.CameraServer.name))}.getOrDefault(CameraMode.CameraServer)
        val cameraStreamQuality=runCatching{CameraStreamQuality.valueOf(o.optString("cameraStreamQuality",CameraStreamQuality.Balanced.name))}.getOrDefault(CameraStreamQuality.Balanced)
        return AppPreferences(zipCode=o.optString("zip","06382"),temperatureFahrenheit=o.optBoolean("fahrenheit",true),refreshMinutes=DataSyncIntervals.normalize(o.optInt("refresh",1)),historyRetentionDays=HISTORY_DAYS,keepScreenOn=o.optBoolean("screen",true),autoStart=o.optBoolean("boot",false),greenhouses=greenhouses,activeGreenhouseId=o.optString("active",greenhouses.first().id),notifications=NotificationPreferences(n.optBoolean("master",true),n.optBoolean("popups",false),n.optBoolean("sound",false),n.optBoolean("vibration",false),n.optBoolean("repeat",false),n.optInt("repeatMinutes",30),n.optLong("snooze",0),n.optBoolean("temp",true),n.optBoolean("humidity",true),n.optBoolean("offline",true),n.optBoolean("connection",false),n.optDouble("lowT",45.0),n.optDouble("highT",95.0),n.optDouble("lowH",30.0),n.optDouble("highH",85.0),n.optDouble("recoveryMargin",2.0)),sensorGreenhouseIds=assignments,cameraMode=cameraMode,cameraStreamQuality=cameraStreamQuality)
    }
}
