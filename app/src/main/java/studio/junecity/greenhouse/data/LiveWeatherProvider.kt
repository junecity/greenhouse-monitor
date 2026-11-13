package studio.junecity.greenhouse.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import studio.junecity.greenhouse.model.WeatherReading
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.ZoneId
import studio.junecity.greenhouse.model.ForecastDay

class LiveWeatherProvider{
    suspend fun current(zipCode:String):WeatherReading=withContext(Dispatchers.IO){
        require(Regex("\\d{5}").matches(zipCode)){"Enter a valid five-digit US ZIP code"}
        val geo=get("https://geocoding-api.open-meteo.com/v1/search?name=$zipCode&count=10&countryCode=US&language=en&format=json")
        val results=JSONObject(geo).optJSONArray("results")?:error("ZIP code not found")
        var place:JSONObject?=null;for(i in 0 until results.length()){val candidate=results.getJSONObject(i);val codes=candidate.optJSONArray("postcodes");if(codes!=null&&(0 until codes.length()).any{codes.optString(it)==zipCode}){place=candidate;break}}
        place=place?:results.optJSONObject(0)?:error("ZIP code not found")
        val lat=place.getDouble("latitude");val lon=place.getDouble("longitude");val location="${place.optString("name")}, ${stateAbbreviation(place.optString("admin1"))}"
        val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
            "&current=temperature_2m,weather_code&temperature_unit=fahrenheit&timezone=auto&forecast_days=8&timeformat=unixtime" +
            "&hourly=temperature_2m&past_days=7&daily=sunrise,sunset,temperature_2m_min,temperature_2m_max,weather_code,precipitation_probability_max"
        parse(zipCode, location, JSONObject(get(url)))
    }

    internal fun parse(zip: String, location: String, root: JSONObject): WeatherReading {
        val current = root.getJSONObject("current")
        val daily = root.optJSONObject("daily")
        val dates = daily?.optJSONArray("time")
        val zone = ZoneId.of(root.getString("timezone"))
        fun instant(raw: String): Long? {
            raw.toLongOrNull()?.let { return it.takeIf { seconds -> seconds > 0 }?.times(1000L) }
            val local = runCatching { java.time.LocalDateTime.parse(raw) }.getOrNull() ?: return null
            val offset = zone.rules.getValidOffsets(local).singleOrNull() ?: return null
            return local.toInstant(offset).toEpochMilli()
        }
        fun date(raw: String): LocalDate? = instant(raw)?.let {
            java.time.Instant.ofEpochMilli(it).atZone(zone).toLocalDate()
        } ?: runCatching { LocalDate.parse(raw) }.getOrNull()
        val today = date(current.optString("time"))
        val solar = buildMap {
            if (daily != null && dates != null) for (i in 0 until dates.length()) {
                val rise = instant(daily.optJSONArray("sunrise")?.optString(i).orEmpty())
                val set = instant(daily.optJSONArray("sunset")?.optString(i).orEmpty())
                val day = (rise ?: set)?.let { java.time.Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
                    ?: date(dates.optString(i)) ?: continue
                put(day, studio.junecity.greenhouse.model.SolarDay(rise, set))
            }
        }
        val hourly = root.optJSONObject("hourly")
        val times = hourly?.optJSONArray("time")
        val temps = hourly?.optJSONArray("temperature_2m")
        val hourlyF = buildMap {
            if (times != null && temps != null) for (i in 0 until minOf(times.length(),temps.length())) {
                val timestamp = instant(times.optString(i)) ?: continue
                val value = temps.optDouble(i)
                if (value.isFinite()) put(timestamp, value)
            }
        }
        val days = buildList {
            if (daily != null && dates != null) {
                fun value(key: String, index: Int) =
                    daily.optJSONArray(key)?.optDouble(index)?.takeIf(Double::isFinite)
                for (index in 0 until dates.length()) {
                    val date = date(daily.optJSONArray("sunrise")?.optString(index).orEmpty()) ?: date(dates.optString(index)) ?: continue
                    if (today != null && date < today) continue
                    if (size == 7) break
                    add(ForecastDay(date, condition(value("weather_code", index)?.toInt() ?: -1),
                        value("temperature_2m_min", index), value("temperature_2m_max", index),
                        value("precipitation_probability_max", index)?.toInt()?.takeIf { it in 0..100 }))
                }
            }
        }
        return WeatherReading(zip, location, current.optDouble("temperature_2m").takeIf(Double::isFinite),
            condition(current.optInt("weather_code", -1)), System.currentTimeMillis(), days = days,
            zone = zone, hourlyOutdoorF = hourlyF, solarDays = solar)
    }
    private fun get(url:String):String{val c=URL(url).openConnection() as HttpURLConnection;return try{c.connectTimeout=12_000;c.readTimeout=12_000;if(c.responseCode !in 200..299)error("Weather HTTP ${c.responseCode}");c.inputStream.bufferedReader().use{it.readText()}}finally{c.disconnect()}}
    private fun condition(code:Int)=when(code){0->"Clear Sky";1->"Mainly Clear";2->"Partly Cloudy";3->"Overcast";45,48->"Fog";51,53,55,56,57->"Drizzle";61,63,65,66,67,80,81,82->"Rain";71,73,75,77,85,86->"Snow";95,96,97,99->"Thunderstorm";else->"Unavailable"}
    private fun stateAbbreviation(s:String)=mapOf("Connecticut" to "CT","New York" to "NY","Massachusetts" to "MA","Rhode Island" to "RI","New Jersey" to "NJ","Pennsylvania" to "PA","California" to "CA","Texas" to "TX","Florida" to "FL")[s]?:s
}
