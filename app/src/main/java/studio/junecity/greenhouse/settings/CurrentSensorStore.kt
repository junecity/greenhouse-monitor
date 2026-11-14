package studio.junecity.greenhouse.settings

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import studio.junecity.greenhouse.model.GoveeSensor

/** The server's latest sensor snapshot, persisted so cloud sync is independent of the UI process. */
class CurrentSensorStore(context: Context, private val suffix: String = "") {
    private val preferences = context.getSharedPreferences("greenhouse_current_sensors$suffix", Context.MODE_PRIVATE)

    companion object { private val lock = Any() }

    internal fun update(transform: (List<GoveeSensor>) -> List<GoveeSensor>): List<GoveeSensor> = synchronized(lock) {
        val previous = load()
        val changed = transform(previous)
        if (changed != previous) save(changed)
        changed
    }

    fun save(sensors: List<GoveeSensor>) {
        preferences.edit().putString("sensors", CurrentSensorCodec.encode(sensors).toString()).apply()
    }

    internal fun restore(sensors: List<GoveeSensor>) {
        check(preferences.edit().putString("sensors", CurrentSensorCodec.encode(sensors).toString()).commit())
    }

    fun load(): List<GoveeSensor> = runCatching {
        preferences.getString("sensors", null)?.let { CurrentSensorCodec.decode(JSONArray(it)) }.orEmpty()
    }.getOrDefault(emptyList())

    fun clear() = preferences.edit().clear().apply()
}

object CurrentSensorCodec {
    fun encode(sensors: List<GoveeSensor>) = JSONArray().also { array ->
        sensors.forEach { sensor ->
            array.put(
                JSONObject()
                    .put("id", sensor.id)
                    .put("sku", sensor.sku)
                    .put("name", sensor.name)
                    .put("temperatureF", sensor.temperatureF)
                    .put("humidityPercent", sensor.humidityPercent)
                    .put("online", sensor.online)
                    .put("updatedAtEpochMs", sensor.updatedAtEpochMs)
                    .put("greenhouseId", sensor.greenhouseId)
                    .put("zone", sensor.zone)
                    .put("lightLevel", sensor.lightLevel)
            )
        }
    }

    fun decode(array: JSONArray): List<GoveeSensor> = (0 until array.length()).map { index ->
        array.getJSONObject(index).let { sensor ->
            GoveeSensor(
                id = sensor.getString("id"),
                sku = sensor.optString("sku", "H5179"),
                name = sensor.optString("name", "Sensor"),
                temperatureF = sensor.optNullableDouble("temperatureF"),
                humidityPercent = sensor.optNullableDouble("humidityPercent"),
                online = sensor.optBoolean("online"),
                updatedAtEpochMs = sensor.optLong("updatedAtEpochMs"),
                greenhouseId = sensor.optString("greenhouseId", "main"),
                zone = sensor.optString("zone", "Unassigned"),
                lightLevel = sensor.optString("lightLevel").takeIf { it in setOf("Dark", "Twilight", "Bright") }
            )
        }
    }

    private fun JSONObject.optNullableDouble(name: String): Double? =
        if (!has(name) || isNull(name)) null else optDouble(name).takeUnless(Double::isNaN)
}
