package studio.junecity.greenhouse.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import studio.junecity.greenhouse.model.GoveeSensor
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

class GoveeApiClient {
    private val baseUrl = "https://openapi.api.govee.com/router/api/v1"

    suspend fun discoverH5179(apiKey: String): List<GoveeSensor> = withContext(Dispatchers.IO) {
        val response = request("$baseUrl/user/devices", apiKey)
        val root = JSONObject(response)
        if (root.optInt("code") != 200) error(root.optString("message", "Govee rejected the request"))
        val data = root.optJSONArray("data") ?: return@withContext emptyList()
        buildList {
            for (i in 0 until data.length()) {
                val item = data.getJSONObject(i)
                if (item.optString("sku").equals("H5179", true)) add(GoveeSensor(item.getString("device"), "H5179", item.optString("deviceName", "H5179 Sensor")))
            }
        }
    }

    suspend fun state(apiKey: String, sensor: GoveeSensor): GoveeSensor = withContext(Dispatchers.IO) {
        val body = JSONObject().put("requestId", UUID.randomUUID().toString()).put("payload", JSONObject().put("sku", sensor.sku).put("device", sensor.id)).toString()
        val root = JSONObject(request("$baseUrl/device/state", apiKey, body))
        if (root.optInt("code") != 200) error(root.optString("msg", "Unable to read ${sensor.name}"))
        val capabilities = root.optJSONObject("payload")?.optJSONArray("capabilities")
        var temperature: Double? = null; var humidity: Double? = null; var online = false
        if (capabilities != null) for (i in 0 until capabilities.length()) {
            val capability = capabilities.getJSONObject(i)
            val value = capability.optJSONObject("state")?.opt("value")
            when (capability.optString("instance")) {
                "online" -> online = value == true
                "sensorTemperature" -> temperature = (value as? Number)?.toDouble()
                "sensorHumidity" -> humidity = (value as? Number)?.toDouble()
            }
        }
        sensor.copy(temperatureF = temperature, humidityPercent = humidity, online = online, updatedAtEpochMs = System.currentTimeMillis())
    }

    private fun request(url: String, apiKey: String, body: String? = null): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 12_000; connection.readTimeout = 12_000
            connection.setRequestProperty("Govee-API-Key", apiKey)
            connection.setRequestProperty("Content-Type", "application/json")
            if (body != null) { connection.requestMethod = "POST"; connection.doOutput = true; connection.outputStream.use { it.write(body.toByteArray()) } }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) error("Govee HTTP $code: ${text.take(160)}")
            text
        } finally { connection.disconnect() }
    }
}
