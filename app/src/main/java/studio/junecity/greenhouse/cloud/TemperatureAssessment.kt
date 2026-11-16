package studio.junecity.greenhouse.cloud

import android.content.Context
import studio.junecity.greenhouse.model.GoveeSensor
import studio.junecity.greenhouse.sensors.isLocalRadio
import studio.junecity.greenhouse.settings.CloudEnrollment
import kotlin.math.abs

data class TemperatureBreach(
    val sensorName: String,
    val temperatureF: Double,
    val limitF: Double,
    val direction: Direction,
    val measuredAtEpochMs: Long
) {
    enum class Direction { LOW, HIGH }
}

data class TemperatureObservation(
    val sensorId: String,
    val sensorName: String,
    val temperatureF: Double,
    val lowF: Double,
    val highF: Double,
    val measuredAtEpochMs: Long
)

internal data class TemperatureAssessment(
    val sensorConnectionHealthy: Boolean,
    val evaluated: Boolean,
    val breach: TemperatureBreach?,
    val outstandingSensorIds: Set<String>,
    val observations: List<TemperatureObservation>
)

/** Apply the currently configured limits to the selected house's actual observations at heartbeat time. */
internal fun assessTemperature(
    sensors: List<GoveeSensor>, lowF: Double, highF: Double, now: Long,
    previousOffenders: Set<String> = emptySet()
): TemperatureAssessment {
    // Govee background work is scheduled every 15 minutes; a quiet interval is not a radio failure.
    val connected = sensors.any { sensor ->
        val timeout = if (sensor.isLocalRadio()) 10 * 60_000L else 30 * 60_000L
        sensor.online && sensor.updatedAtEpochMs > 0 && sensor.updatedAtEpochMs in (now - timeout)..now
    }
    val inventory = sensors.sortedByDescending { it.updatedAtEpochMs }.distinctBy { it.id }
    val recent = inventory.filter {
        it.online && it.updatedAtEpochMs > 0 && it.updatedAtEpochMs in (now - 10 * 60_000L)..now &&
            it.id.isNotBlank() && it.temperatureF != null && it.temperatureF in -459.67..1000.0
    }
    if (recent.isEmpty() || !lowF.isFinite() || !highF.isFinite() || lowF >= highF) {
        return TemperatureAssessment(connected, false, null, previousOffenders, emptyList())
    }
    // Retain a prior offending sensor until it reports again. A complete inventory can also
    // retire sensors that have been explicitly removed from the house.
    val complete = recent.size == inventory.size
    val outstanding = if (complete) mutableSetOf() else previousOffenders.toMutableSet()
    var worst: TemperatureBreach? = null
    val observations = ArrayList<TemperatureObservation>(recent.size)
    for (sensor in recent) {
        val value = requireNotNull(sensor.temperatureF)
        val name = sensor.name.trim().take(96).ifBlank { "Temperature sensor" }
        observations.add(TemperatureObservation(sensor.id, name, value, lowF, highF, sensor.updatedAtEpochMs))
        val direction = when {
            value < lowF -> TemperatureBreach.Direction.LOW
            value > highF -> TemperatureBreach.Direction.HIGH
            else -> {
                outstanding.remove(sensor.id)
                continue
            }
        }
        outstanding.add(sensor.id)
        val limit = if (direction == TemperatureBreach.Direction.LOW) lowF else highF
        if (worst == null || abs(value - limit) > abs(worst.temperatureF - worst.limitF)) {
            worst = TemperatureBreach(name, value, limit, direction, sensor.updatedAtEpochMs)
        }
    }
    val recovered = complete || (previousOffenders.isNotEmpty() && outstanding.isEmpty())
    return TemperatureAssessment(true, worst != null || recovered, worst, outstanding, observations)
}

/** IDs of thermometers in an unresolved incident. Never reuse thresholds or readings from this store. */
internal class TemperatureIncidentSensors(context: Context, owner: CloudEnrollment, house: String) {
    private val prefs = context.getSharedPreferences("greenhouse_temperature_incidents", Context.MODE_PRIVATE)
    private val key = "${owner.tenantId}/${owner.deviceId}/${owner.greenhouseId}/$house"

    fun load(): Set<String> = prefs.getStringSet(key, emptySet()).orEmpty().toSet()

    fun save(ids: Set<String>) {
        if (ids == load()) return
        val edit = prefs.edit()
        if (ids.isEmpty()) edit.remove(key) else edit.putStringSet(key, ids)
        check(edit.commit()) { "Could not save temperature incident state" }
    }
}
