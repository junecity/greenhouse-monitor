package studio.junecity.greenhouse.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import studio.junecity.greenhouse.model.WeatherReading
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.roundToInt

class LiveWeatherProvider {
    suspend fun current(latitude: Double, longitude: Double): WeatherReading = withContext(Dispatchers.IO) {
        val url = "https://api.open-meteo.com/v1/forecast?latitude=$latitude&longitude=$longitude&current=temperature_2m,weather_code&daily=temperature_2m_min,precipitation_probability_max&temperature_unit=fahrenheit&timezone=auto&forecast_days=1"
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout=12_000; connection.readTimeout=12_000
            val root=JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            val current=root.getJSONObject("current"); val daily=root.getJSONObject("daily")
            val code=current.getInt("weather_code")
            WeatherReading("Local weather",current.getDouble("temperature_2m").roundToInt(),condition(code),daily.getJSONArray("temperature_2m_min").getDouble(0).roundToInt(),daily.getJSONArray("precipitation_probability_max").optInt(0),false)
        } finally { connection.disconnect() }
    }

    private fun condition(code:Int)=when(code){0->"Clear Sky";1->"Mainly Clear";2->"Partly Cloudy";3->"Overcast";45,48->"Fog";51,53,55,56,57->"Drizzle";61,63,65,66,67,80,81,82->"Rain";71,73,75,77,85,86->"Snow";95,96,97,99->"Thunderstorm";else->"Current Conditions"}
}
