package studio.junecity.greenhouse.cloud

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import studio.junecity.greenhouse.data.HistoryStore
import studio.junecity.greenhouse.model.HistoryReading
import studio.junecity.greenhouse.settings.AppPreferencesStore
import studio.junecity.greenhouse.settings.ZoneAssignmentStore

class GreenhouseBackupStore(context: Context) {
    private val preferences = AppPreferencesStore(context)
    private val zones = ZoneAssignmentStore(context)
    private val history = HistoryStore(context)

    fun snapshot(): JSONObject {
        val zoneValues = JSONObject().also { json -> zones.exportAssignments().forEach(json::put) }
        val readings = JSONArray().also { array -> history.recent(1_500).asReversed().forEach { reading ->
            array.put(JSONObject().put("sensorId", reading.sensorId).put("sensorName", reading.sensorName)
                .put("temperatureF", reading.temperatureF).put("humidityPercent", reading.humidityPercent)
                .put("timestampEpochMs", reading.timestampEpochMs))
        } }
        return JSONObject().put("schema", 1).put("preferences", preferences.exportJson()).put("zones", zoneValues).put("history", readings)
    }

    fun restore(value: JSONObject) {
        require(value.optInt("schema") == 1) { "Unsupported greenhouse backup" }
        value.optJSONObject("preferences")?.let(preferences::importJson)
        value.optJSONObject("zones")?.let { json ->
            val values = buildMap { json.keys().forEach { key -> put(key, json.optString(key, "Unassigned")) } }
            zones.importAssignments(values)
        }
        value.optJSONArray("history")?.let { array ->
            history.importReadings((0 until array.length()).map { index -> array.getJSONObject(index).let { reading ->
                HistoryReading(reading.getString("sensorId"), reading.getString("sensorName"), reading.getDouble("temperatureF"), reading.getDouble("humidityPercent"), reading.getLong("timestampEpochMs"))
            } })
        }
    }
}
