package studio.junecity.greenhouse.sensors

import kotlinx.coroutines.CancellationException
import studio.junecity.greenhouse.model.AppPreferences
import studio.junecity.greenhouse.model.GoveeSensor

/** Discovery defines the current inventory. A failed state request affects only that device. */
internal suspend fun SensorProvider.collectReadings(
    credential: SensorProviderCredential,
    preferences: AppPreferences,
    previous: List<GoveeSensor>,
    zoneFor: (String) -> String
): List<GoveeSensor> {
    val cached = previous.associateBy { it.id }
    return discover(credential).distinctBy { it.deviceId }.map { device ->
        val old = cached[device.deviceId]
        val house = preferences.sensorGreenhouseIds[device.deviceId] ?: old?.greenhouseId ?: preferences.activeGreenhouse.id
        val zone = zoneFor(device.deviceId).takeUnless { it == "Unassigned" } ?: old?.zone ?: "Unassigned"
        val measurement = try {
            read(credential, device)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        if (measurement?.connected == true && measurement.temperatureF != null && measurement.humidityPercent != null) {
            measurement.toLegacySensor(house, zone)
        } else {
            (old ?: GoveeSensor(device.deviceId, device.model, device.displayName))
                .copy(name = device.displayName, greenhouseId = house, zone = zone, online = false)
        }
    }
}

internal fun List<GoveeSensor>.withCurrentAssignments(preferences: AppPreferences, zoneFor: (String) -> String) =
    map { sensor -> sensor.copy(
        greenhouseId = preferences.sensorGreenhouseIds[sensor.id] ?: sensor.greenhouseId,
        zone = zoneFor(sensor.id).takeUnless { it == "Unassigned" } ?: sensor.zone
    ) }
