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

    fun removed(): List<GoveeSensor> = CurrentSensorCodec.decode(JSONArray(preferences.getString("removed", "[]")))

    private fun labels() = JSONObject(preferences.getString("labels", "{}"))

    fun rename(id: String, label: String) = synchronized(lock) {
        val name = label.trim()
        val rows = load()
        if (name.isEmpty() || name.length > 64 || rows.none { it.id == id }) return@synchronized
        preferences.edit().putString("labels", labels().put(id, name).toString())
            .putString("sensors", CurrentSensorCodec.encode(rows.map { if (it.id == id) it.copy(name = name) else it }).toString()).apply()
    }

    fun remove(id: String) = synchronized(lock) {
        val rows = load()
        val sensor = rows.find { it.id == id } ?: return@synchronized
        writeMembership(rows.filterNot { it.id == id }, removed().filterNot { it.id == id } + sensor)
    }

    // Govee lists every device on the provider account during routine discovery.
    fun forgottenGovee(): Set<String> = preferences.getStringSet("forgottenGovee", emptySet()).orEmpty().toSet()

    fun excludedIds(): Set<String> = forgottenGovee() + removed().map { it.id }

    fun forget(sensor: GoveeSensor) = synchronized(lock) {
        val id = sensor.id
        val active = load().filterNot { it.id == id }
        val archived = removed().filterNot { it.id == id }
        val editor = preferences.edit()
        if (sensor.sku.equals("H5179", true)) editor.putStringSet("forgottenGovee", forgottenGovee() + id)
        editor.putString("sensors", CurrentSensorCodec.encode(active).toString())
            .putString("removed", CurrentSensorCodec.encode(archived).toString())
            .putString("labels", labels().apply { remove(id) }.toString()).apply()
    }

    fun enroll(sensor: GoveeSensor) = synchronized(lock) {
        val rows = load()
        if (rows.any { it.id == sensor.id }) return@synchronized
        val archived = removed()
        val label = labels().optString(sensor.id, sensor.name)
        writeMembership(rows + sensor.copy(name = label), archived.filterNot { it.id == sensor.id }, forgottenGovee() - sensor.id)
    }

    fun addBack(id: String) = synchronized(lock) {
        val sensor = removed().find { it.id == id } ?: return@synchronized
        enroll(sensor.copy(online = false))
    }

    private fun writeMembership(active: List<GoveeSensor>, archived: List<GoveeSensor>, forgotten: Set<String> = forgottenGovee()) {
        preferences.edit().putString("sensors", CurrentSensorCodec.encode(active).toString())
            .putString("removed", CurrentSensorCodec.encode(archived).toString()).putStringSet("forgottenGovee", forgotten).apply()
    }

    internal fun allowedReadings(readings: List<GoveeSensor>): List<GoveeSensor> {
        val excluded = excludedIds()
        val labels = labels()
        return readings.filterNot { it.id in excluded }.map { it.copy(name = labels.optString(it.id, it.name)) }
    }

    internal fun writeBackup(meta: JSONObject, inventory: (List<GoveeSensor>) -> List<GoveeSensor> = { it }) = synchronized(lock) {
        meta.put("sensors", CurrentSensorCodec.encode(inventory(load()))).put("removedSensors", CurrentSensorCodec.encode(removed()))
            .put("sensorLabels", labels()).put("forgottenGovee", JSONArray(forgottenGovee().toList()))
    }

    internal fun restore(sensors: List<GoveeSensor>, removed: JSONArray?, labels: JSONObject?, forgotten: JSONArray? = null) = synchronized(lock) {
        check(preferences.edit().putString("sensors", CurrentSensorCodec.encode(sensors).toString())
            .putString("removed", (removed ?: JSONArray()).toString())
            .putStringSet("forgottenGovee", CurrentSensorCodec.forgottenIds(forgotten))
            .putString("labels", (labels ?: JSONObject()).toString()).commit())
    }

    fun load(): List<GoveeSensor> = runCatching {
        preferences.getString("sensors", null)?.let { CurrentSensorCodec.decode(JSONArray(it)) }.orEmpty()
    }.getOrDefault(emptyList())

    fun clear() = preferences.edit().clear().apply()
}

object CurrentSensorCodec {
    internal fun forgottenIds(array: JSONArray?): Set<String> =
        if (array == null) emptySet() else (0 until array.length()).map { index ->
            (array.get(index) as String).also { require(it.isNotBlank()) }
        }.toSet()

    fun validateManagement(meta: JSONObject) {
        forgottenIds(meta.optJSONArray("forgottenGovee"))
        meta.optJSONArray("removedSensors")?.let { decode(it) }
        meta.optJSONObject("sensorLabels")?.let { labels ->
            require(labels.keys().asSequence().all { labels.get(it) is String && labels.getString(it).length in 1..64 })
        }
    }

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
                    .put("batteryPercent", sensor.batteryPercent)
                    .put("batteryLow", sensor.batteryLow)
                    .put("batteryObservedAt", sensor.batteryObservedAt)
                    .put("batteryRadioAddress", sensor.batteryRadioAddress)
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
                batteryPercent = sensor.opt("batteryPercent")?.toString()?.toIntOrNull()?.takeIf { it in 0..100 },
                batteryObservedAt = sensor.optLong("batteryObservedAt").takeIf { it > 0L },
                batteryRadioAddress = sensor.optString("batteryRadioAddress").takeIf { it.isNotBlank() && it != "null" },
                batteryLow = sensor.opt("batteryLow") as? Boolean,
                lightLevel = sensor.optString("lightLevel").takeIf { it in setOf("Dark", "Twilight", "Bright") }
            )
        }
    }

    private fun JSONObject.optNullableDouble(name: String): Double? =
        if (!has(name) || isNull(name)) null else optDouble(name).takeUnless(Double::isNaN)
}
