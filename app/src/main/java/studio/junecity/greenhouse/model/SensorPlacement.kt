package studio.junecity.greenhouse.model

/** Start at separate interior points; the grower can then drag each sensor to its actual mounting site. */
internal fun GreenhouseConfig.withNewSensorPositions(ids: List<String>): GreenhouseConfig {
    val missing = ids.distinct().filterNot(sensorPositions::containsKey)
    if (missing.isEmpty()) return this
    val positions = sensorPositions.toMutableMap()
    var index = 0
    fun fraction(n: Int, base: Int): Double {
        var v = n
        var step = 1.0
        var total = 0.0
        while (v > 0) { step /= base; total += (v % base) * step; v /= base }
        return total
    }
    val initial = listOf(.2 to .18, .8 to .18, .4 to .62, .6 to .82)
    val occupied = positions.values.map { it.xFeet to it.yFeet }.toMutableSet()
    for (id in missing) {
        var candidate: SensorPosition
        do {
            val n = index++
            val point = initial.getOrNull(n) ?: (fraction(n + 1, 2) to fraction(n + 1, 3))
            // Projection also handles narrow or concave plans without unbounded interior sampling.
            candidate = coercePoint(point.first * planLengthFeet, point.second * planWidthFeet)
        } while (!occupied.add(candidate.xFeet to candidate.yFeet))
        positions[id] = candidate
    }
    return copy(sensorPositions = positions)
}
