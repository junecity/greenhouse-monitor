package studio.junecity.greenhouse.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import studio.junecity.greenhouse.model.WeatherReading
import java.net.HttpURLConnection
import java.net.URL

class LiveWeatherProvider{
    suspend fun current(zipCode:String):WeatherReading=withContext(Dispatchers.IO){
        require(Regex("\\d{5}").matches(zipCode)){"Enter a valid five-digit US ZIP code"}
        val geo=get("https://geocoding-api.open-meteo.com/v1/search?name=$zipCode&count=10&countryCode=US&language=en&format=json")
        val results=JSONObject(geo).optJSONArray("results")?:error("ZIP code not found")
        var place:JSONObject?=null;for(i in 0 until results.length()){val candidate=results.getJSONObject(i);val codes=candidate.optJSONArray("postcodes");if(codes!=null&&(0 until codes.length()).any{codes.optString(it)==zipCode}){place=candidate;break}}
        place=place?:results.optJSONObject(0)?:error("ZIP code not found")
        val lat=place.getDouble("latitude");val lon=place.getDouble("longitude");val location="${place.optString("name")}, ${stateAbbreviation(place.optString("admin1"))}"
        val root=JSONObject(get("https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current=temperature_2m,weather_code&temperature_unit=fahrenheit&timezone=auto"));val current=root.getJSONObject("current");val now=System.currentTimeMillis()
        WeatherReading(zipCode,location,current.optDouble("temperature_2m").takeUnless{it.isNaN()},condition(current.optInt("weather_code")),now,false)
    }
    private fun get(url:String):String{val c=URL(url).openConnection() as HttpURLConnection;return try{c.connectTimeout=12_000;c.readTimeout=12_000;if(c.responseCode !in 200..299)error("Weather HTTP ${c.responseCode}");c.inputStream.bufferedReader().use{it.readText()}}finally{c.disconnect()}}
    private fun condition(code:Int)=when(code){0->"Clear Sky";1->"Mainly Clear";2->"Partly Cloudy";3->"Overcast";45,48->"Fog";51,53,55,56,57->"Drizzle";61,63,65,66,67,80,81,82->"Rain";71,73,75,77,85,86->"Snow";95,96,97,99->"Thunderstorm";else->"Current Conditions"}
    private fun stateAbbreviation(s:String)=mapOf("Connecticut" to "CT","New York" to "NY","Massachusetts" to "MA","Rhode Island" to "RI","New Jersey" to "NJ","Pennsylvania" to "PA","California" to "CA","Texas" to "TX","Florida" to "FL")[s]?:s
}
