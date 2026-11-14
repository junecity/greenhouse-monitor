package studio.junecity.greenhouse.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import studio.junecity.greenhouse.model.WeatherReading
import studio.junecity.greenhouse.model.StationHour
import java.time.Instant
import java.util.Locale
import kotlin.math.*

internal fun stationHours(response: JSONObject, now: Long): Map<Long, StationHour> {
    val reports = response.getJSONArray("features")
    val samples = sortedMapOf<Long, Double>()
    for (i in 0 until reports.length()) {
        val p = reports.getJSONObject(i).getJSONObject("properties")
        val t = runCatching { Instant.parse(p.getString("timestamp")).toEpochMilli() }.getOrNull() ?: continue
        val reading = p.optJSONObject("temperature") ?: continue
        if (t > now || reading.optString("unitCode") != "wmoUnit:degC" ||
            reading.optString("qualityControl") in setOf("X","Q","B")) continue
        val c = reading.optDouble("value")
        if (c in -90.0..60.0) samples[t] = c * 1.8 + 32
    }
    return samples.entries.groupBy { it.key / HEATING_HOUR * HEATING_HOUR }.mapValues { (_, rows) ->
        StationHour(rows.map { it.value }.average(), rows.last().key)
    }
}

internal class StationWeather(context: Context) {
    private val cache = context.getSharedPreferences("heating_station_weather", Context.MODE_PRIVATE)
    private fun nearbyStation(lat: Double, lon: Double): String? {
        val point = String.format(Locale.US, "%.4f,%.4f", lat, lon)
        val url = JSONObject(publicEnergyData("https://api.weather.gov/points/$point"))
            .getJSONObject("properties").getString("observationStations")
        require(url.matches(Regex("""https://api\.weather\.gov/gridpoints/[A-Z0-9]+/\d+,\d+/stations""")))
        val stations = JSONObject(publicEnergyData(url)).getJSONArray("features")
        for (i in 0 until stations.length()) {
            val site = stations.getJSONObject(i)
            val xy = site.getJSONObject("geometry").getJSONArray("coordinates")
            val km = hypot(lat - xy.getDouble(1), (lon - xy.getDouble(0)) * cos(Math.toRadians(lat))) * 111
            val id = site.getJSONObject("properties").optString("stationIdentifier")
            if (km <= 50 && id.matches(Regex("[A-Z0-9]{3,8}"))) return id
        }
        return null
    }

    suspend fun update(weather: WeatherReading): WeatherReading = withContext(Dispatchers.IO) {
        val lat = weather.latitude ?: return@withContext weather
        val lon = weather.longitude ?: return@withContext weather
        val key = weather.zipCode
        val now = System.currentTimeMillis()
        val cutoff = now - 7 * 24 * HEATING_HOUR
        val saved = runCatching { JSONObject(cache.getString(key, "{}")!!) }.getOrElse { JSONObject() }
        var id = saved.optString("station")
        val hours = mutableMapOf<Long, StationHour>()
        val rows = saved.optJSONArray("hours") ?: JSONArray()
        for (i in 0 until rows.length()) {
            val row = rows.getJSONArray(i)
            if (row.getLong(0) >= cutoff) hours[row.getLong(0)] = StationHour(row.getDouble(1), row.getLong(2))
        }
        if (now - saved.optLong("checked") !in 0 until 15 * 60_000L) {
            runCatching {
                if (id.isEmpty()) id = nearbyStation(lat, lon).orEmpty()
                if (id.isNotEmpty()) {
                    val feed = JSONObject(publicEnergyData("https://api.weather.gov/stations/$id/observations?limit=500"))
                    // The first hour in a paged response can be truncated. Preserve a previously complete hour.
                    val found = stationHours(feed, now).filterKeys { it >= cutoff }
                    val first = found.keys.minOrNull()
                    found.forEach { (hour, obs) -> if (hour != first || hour !in hours) hours[hour] = obs }
                }
                val data = JSONArray()
                hours.forEach { (hour, obs) -> data.put(JSONArray().put(hour).put(obs.temperatureF).put(obs.lastReportMs)) }
                cache.edit().putString(key, JSONObject().put("station", id).put("checked", now)
                    .put("hours", data).toString()).apply()
            }
        }
        weather.copy(stationHours = hours, stationId = id.takeIf { it.isNotBlank() })
    }
}
