package studio.junecity.greenhouse.cloud

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import studio.junecity.greenhouse.data.HistoryStore
import studio.junecity.greenhouse.model.inSavedHouses
import studio.junecity.greenhouse.model.HistoryReading
import studio.junecity.greenhouse.model.CameraMode
import studio.junecity.greenhouse.settings.AppPreferencesStore
import studio.junecity.greenhouse.settings.CurrentSensorCodec
import studio.junecity.greenhouse.settings.CurrentSensorStore
import studio.junecity.greenhouse.settings.ZoneAssignmentStore

class GreenhouseBackupStore(private val context: Context, private val historySuffix: String = "") {
    private val preferences = AppPreferencesStore(context)
    private val zones = ZoneAssignmentStore(context)
    private val currentSensors = CurrentSensorStore(context)

    fun snapshot(): JSONObject {
        val zoneValues = JSONObject().also { json -> zones.exportAssignments().forEach(json::put) }
        val readings = JSONArray().also { array -> HistoryStore(context, historySuffix).use { it.recent(1_500) }.asReversed().forEach { reading ->
            array.put(JSONObject().put("sensorId", reading.sensorId).put("sensorName", reading.sensorName)
                .put("temperatureF", reading.temperatureF).put("humidityPercent", reading.humidityPercent)
                .put("timestampEpochMs", reading.timestampEpochMs))
        } }
        // Group by sensor to keep six months within the existing bounded snapshot transport.
        val daily = JSONArray()
        HistoryStore(context, historySuffix).use { it.dailyReadings() }.groupBy { it.sensorId }.forEach { (id, rows) ->
            val samples = JSONArray()
            rows.forEach { samples.put(JSONArray().put(it.timestampEpochMs).put(it.temperatureF).put(it.humidityPercent)) }
            daily.put(JSONObject().put("id", id).put("name", rows.last().sensorName).put("samples", samples))
        }
        return JSONObject().put("dailyLast", daily).put("schema", 2).put("preferences", preferences.exportJson()).put("zones", zoneValues)
            .put("sensors", CurrentSensorCodec.encode(currentSensors.load().inSavedHouses(preferences.load()))).put("history", readings)
    }

    fun restore(value: JSONObject, localCameraMode: CameraMode? = null) {
        require(value.optInt("schema") in 1..2) { "Unsupported greenhouse backup" }
        // Decode the entire payload before touching persisted state. A bad history row can
        // otherwise leave a replacement phone with a mixture of old and restored settings.
        val savedPreferences = value.optJSONObject("preferences")?.let(preferences::decode)
        val effective = (savedPreferences ?: preferences.load()).let {
            if (localCameraMode == null) it else it.copy(cameraMode = localCameraMode)
        }
        val assignments = value.optJSONObject("zones")?.let { json ->
            json.keys().asSequence().associateWith { json.optString(it, "Unassigned") }
        }
        val inventory = value.optJSONArray("sensors")?.let { CurrentSensorCodec.decode(it).inSavedHouses(effective) }
        val readings = value.optJSONArray("history")?.let { array ->
            (0 until array.length()).map { index ->
                val row = array.getJSONObject(index)
                HistoryReading(row.getString("sensorId"), row.getString("sensorName"),
                    row.getDouble("temperatureF"), row.getDouble("humidityPercent"), row.getLong("timestampEpochMs"))
            }
        }
        val daily = buildList {
            val groups = value.optJSONArray("dailyLast") ?: return@buildList
            for (i in 0 until groups.length()) {
                val group = groups.getJSONObject(i)
                val id = group.getString("id")
                val name = group.getString("name")
                val points = group.getJSONArray("samples")
                for (j in 0 until points.length()) {
                    val point = points.getJSONArray(j)
                    add(HistoryReading(id, name, point.getDouble(1), point.getDouble(2), point.getLong(0)))
                }
            }
        }
        if (readings != null || daily.isNotEmpty()) HistoryStore(context, historySuffix).use {
            it.importReadings(readings.orEmpty() + daily, merge = historySuffix.isNotEmpty())
        }
        if (savedPreferences != null || localCameraMode != null) preferences.save(effective)
        if (assignments != null) zones.replaceAssignments(assignments)
        if (inventory != null) currentSensors.save(inventory)
    }
}
