package studio.junecity.greenhouse.model

internal fun originalSensorSlot(id: String) = (kotlin.math.abs(id.hashCode().toLong()) % 4).toInt()

/** Keep previous assignments, including retired sensors whose history is still charted. */
internal fun assignSensorSlots(saved: Map<String, Int>, ids: Iterable<String>): Map<String, Int> {
    val result = saved.toMutableMap()
    val used = saved.values.toMutableSet()
    var next = 4
    for (id in ids) {
        if (id in result) continue
        val original = originalSensorSlot(id)
        if (used.add(original)) result[id] = original
        else {
            while (next in used) next++
            result[id] = next
            used.add(next++)
        }
    }
    return result
}
