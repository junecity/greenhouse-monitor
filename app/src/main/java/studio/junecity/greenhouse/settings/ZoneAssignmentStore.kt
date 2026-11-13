package studio.junecity.greenhouse.settings

import android.content.Context

class ZoneAssignmentStore(context:Context,private val suffix:String=""){
    private val prefs=context.getSharedPreferences("sensor_zones$suffix",Context.MODE_PRIVATE)
    fun zone(sensorId:String)=prefs.getString(sensorId,"Unassigned") ?: "Unassigned"
    fun assign(sensorId:String,zone:String)=prefs.edit().putString(sensorId,zone).apply()
    fun exportAssignments():Map<String,String> = prefs.all.mapNotNull { (key,value) -> (value as? String)?.let { key to it } }.toMap()
    fun importAssignments(values:Map<String,String>) { prefs.edit().also { editor -> values.forEach(editor::putString) }.apply() }
    fun replaceAssignments(values: Map<String,String>) { check(prefs.edit().clear().also { editor -> values.forEach(editor::putString) }.commit()) }
    fun clear() = prefs.edit().clear().apply()
}
