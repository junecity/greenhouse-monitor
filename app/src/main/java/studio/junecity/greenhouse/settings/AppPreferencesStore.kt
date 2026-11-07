package studio.junecity.greenhouse.settings

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import studio.junecity.greenhouse.model.*

class AppPreferencesStore(context:Context){
    private val prefs=context.getSharedPreferences("greenhouse_preferences_v2",Context.MODE_PRIVATE)
    fun load():AppPreferences=runCatching{prefs.getString("state",null)?.let{decode(JSONObject(it))}?:AppPreferences()}.getOrElse{AppPreferences()}
    fun save(value:AppPreferences){prefs.edit().putString("state",encode(value).toString()).apply()}
    private fun encode(p:AppPreferences):JSONObject{
        val n=p.notifications
        val notifications=JSONObject().put("master",n.masterEnabled).put("popups",n.popupsEnabled).put("sound",n.soundEnabled).put("vibration",n.vibrationEnabled).put("repeat",n.repeatedAlerts).put("repeatMinutes",n.repeatMinutes).put("snooze",n.snoozedUntilEpochMs).put("temp",n.temperatureAlerts).put("humidity",n.humidityAlerts).put("offline",n.offlineAlerts).put("connection",n.connectionAlerts).put("lowT",n.lowTemperatureF).put("highT",n.highTemperatureF).put("lowH",n.lowHumidity).put("highH",n.highHumidity)
        val greenhouses=JSONArray()
        p.greenhouses.forEach{g->
            val positions=JSONObject();g.sensorPositions.forEach{(id,pos)->positions.put(id,JSONObject().put("x",pos.xFeet).put("y",pos.yFeet).put("z",pos.heightFeet))}
            greenhouses.put(JSONObject().put("id",g.id).put("name",g.name).put("length",g.lengthFeet).put("width",g.widthFeet).put("height",g.heightFeet).put("positions",positions))
        }
        val assignments=JSONObject();p.sensorGreenhouseIds.forEach{(sensor,greenhouse)->assignments.put(sensor,greenhouse)}
        return JSONObject().put("zip",p.zipCode).put("fahrenheit",p.temperatureFahrenheit).put("refresh",p.refreshMinutes).put("retention",p.historyRetentionDays).put("screen",p.keepScreenOn).put("boot",p.autoStart).put("active",p.activeGreenhouseId).put("cameraMode",p.cameraMode.name).put("notifications",notifications).put("greenhouses",greenhouses).put("sensorGreenhouses",assignments)
    }
    private fun decode(o:JSONObject):AppPreferences{
        val array=o.optJSONArray("greenhouses")?:JSONArray();val greenhouses=mutableListOf<GreenhouseConfig>()
        for(i in 0 until array.length()){
            val x=array.getJSONObject(i);val po=x.optJSONObject("positions")?:JSONObject();val positions=mutableMapOf<String,SensorPosition>()
            po.keys().forEach{id->val p=po.getJSONObject(id);positions[id]=SensorPosition(p.optDouble("x"),p.optDouble("y"),p.optDouble("z"))}
            greenhouses+=GreenhouseConfig(x.optString("id","main"),x.optString("name","Main Greenhouse"),x.optDouble("length",16.0),x.optDouble("width",8.0),x.optDouble("height").takeUnless{it.isNaN()},positions)
        }
        if(greenhouses.isEmpty())greenhouses+=GreenhouseConfig()
        val n=o.optJSONObject("notifications")?:JSONObject()
        val assignmentsJson=o.optJSONObject("sensorGreenhouses")?:JSONObject();val assignments=mutableMapOf<String,String>();assignmentsJson.keys().forEach{id->assignments[id]=assignmentsJson.optString(id)}
        val cameraMode=runCatching{CameraMode.valueOf(o.optString("cameraMode",CameraMode.CameraServer.name))}.getOrDefault(CameraMode.CameraServer)
        return AppPreferences(zipCode=o.optString("zip","06382"),temperatureFahrenheit=o.optBoolean("fahrenheit",true),refreshMinutes=o.optInt("refresh",1),historyRetentionDays=o.optInt("retention",30),keepScreenOn=o.optBoolean("screen",true),autoStart=o.optBoolean("boot",false),greenhouses=greenhouses,activeGreenhouseId=o.optString("active",greenhouses.first().id),notifications=NotificationPreferences(n.optBoolean("master",true),n.optBoolean("popups",false),n.optBoolean("sound",false),n.optBoolean("vibration",false),n.optBoolean("repeat",false),n.optInt("repeatMinutes",30),n.optLong("snooze",0),n.optBoolean("temp",true),n.optBoolean("humidity",true),n.optBoolean("offline",true),n.optBoolean("connection",false),n.optDouble("lowT",45.0),n.optDouble("highT",95.0),n.optDouble("lowH",30.0),n.optDouble("highH",85.0)),sensorGreenhouseIds=assignments,cameraMode=cameraMode)
    }
}
