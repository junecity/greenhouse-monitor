package studio.junecity.greenhouse.sensors

import studio.junecity.greenhouse.data.GoveeApiClient
import studio.junecity.greenhouse.model.GoveeSensor

data class SensorDevice(
    val providerId: String,
    val deviceId: String,
    val model: String,
    val displayName: String
)

data class SensorMeasurement(
    val device: SensorDevice,
    val temperatureF: Double?,
    val humidityPercent: Double?,
    val batteryPercent: Int? = null,
    val connected: Boolean,
    val recordedAtEpochMs: Long
) {
    fun toLegacySensor(greenhouseId: String = "main", zone: String = "Unassigned") = GoveeSensor(
        id = device.deviceId,
        sku = device.model,
        name = device.displayName,
        temperatureF = temperatureF,
        humidityPercent = humidityPercent,
        online = connected,
        updatedAtEpochMs = recordedAtEpochMs,
        greenhouseId = greenhouseId,
        zone = zone
    )
}

sealed interface SensorProviderCredential {
    data class ApiKey(val value: String) : SensorProviderCredential
    data class DeviceGrant(val value: String) : SensorProviderCredential
}

interface SensorProvider {
    val providerId: String
    suspend fun discover(credential: SensorProviderCredential): List<SensorDevice>
    suspend fun read(credential: SensorProviderCredential, device: SensorDevice): SensorMeasurement
}

class GoveeSensorProvider(private val api: GoveeApiClient = GoveeApiClient()) : SensorProvider {
    override val providerId = "govee"

    override suspend fun discover(credential: SensorProviderCredential): List<SensorDevice> {
        val key = credential.requireApiKey()
        return api.discoverH5179(key).map { SensorDevice(providerId, it.id, it.sku, it.name) }
    }

    override suspend fun read(credential: SensorProviderCredential, device: SensorDevice): SensorMeasurement {
        require(device.providerId == providerId) { "Sensor belongs to ${device.providerId}, not $providerId" }
        val sensor = api.state(credential.requireApiKey(), GoveeSensor(device.deviceId, device.model, device.displayName))
        return SensorMeasurement(device, sensor.temperatureF, sensor.humidityPercent, connected = sensor.online, recordedAtEpochMs = sensor.updatedAtEpochMs)
    }

    private fun SensorProviderCredential.requireApiKey() = (this as? SensorProviderCredential.ApiKey)?.value
        ?.takeIf(String::isNotBlank) ?: error("Govee API key required")
}

