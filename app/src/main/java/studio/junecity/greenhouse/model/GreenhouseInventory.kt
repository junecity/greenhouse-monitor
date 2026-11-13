package studio.junecity.greenhouse.model

/** A dashboard always has an active floor plan. Historical measurements belong to sensor IDs. */
internal fun AppPreferences.removeFloorPlan(id: String, sensors: List<GoveeSensor>): AppPreferences {
    if (greenhouses.size <= 1 || greenhouses.none { it.id == id }) return this
    val released = sensors.filter { sensorHouse(it) == id }.map { it.id } +
        greenhouses.first { it.id == id }.sensorPositions.keys.filter { sensorId ->
            sensorId !in sensorGreenhouseIds && sensors.none { it.id == sensorId }
        }
    val remaining = greenhouses.filterNot { it.id == id }
    return copy(
        greenhouses = remaining,
        activeGreenhouseId = remaining.firstOrNull { it.id == activeGreenhouseId }?.id ?: remaining.first().id,
        sensorGreenhouseIds = sensorGreenhouseIds.mapValues { (_, assigned) -> if (assigned == id) "" else assigned } + released.associateWith { "" }
    )
}

/** Older installations can store the only assignment in the last reading. Empty means awaiting placement. */
internal fun AppPreferences.sensorHouse(sensor: GoveeSensor): String {
    val id = sensorGreenhouseIds[sensor.id] ?: sensor.greenhouseId
    return if (greenhouses.any { it.id == id }) id else ""
}

internal fun List<GoveeSensor>.inSavedHouses(preferences: AppPreferences) =
    map { sensor -> sensor.copy(greenhouseId = preferences.sensorHouse(sensor)) }
