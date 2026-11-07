package studio.junecity.greenhouse.settings

import android.content.Context

class ZoneAssignmentStore(context:Context){
    private val prefs=context.getSharedPreferences("sensor_zones",Context.MODE_PRIVATE)
    fun zone(sensorId:String)=prefs.getString(sensorId,"Unassigned") ?: "Unassigned"
    fun assign(sensorId:String,zone:String)=prefs.edit().putString(sensorId,zone).apply()
}
