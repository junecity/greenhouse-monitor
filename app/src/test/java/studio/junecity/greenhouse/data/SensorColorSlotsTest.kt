package studio.junecity.greenhouse.data

import org.junit.Assert.*
import org.junit.Test
import studio.junecity.greenhouse.model.assignSensorSlots
import studio.junecity.greenhouse.ui.sensorSlotColor
import androidx.compose.ui.graphics.toArgb

class SensorColorSlotsTest {
    @Test fun fiftySensorsKeepDistinctColorsWhenAddedReorderedOrRemoved() {
        val ids = List(50) { "sensor-$it" }
        val first = assignSensorSlots(emptyMap(), ids.take(5))
        val all = assignSensorSlots(first, ids.reversed())
        assertTrue(first.all { (id, slot) -> all[id] == slot })
        assertEquals(50, all.values.map { sensorSlotColor(it).toArgb() }.distinct().size)
        assertEquals(all, assignSensorSlots(all, ids.drop(4)))
        assertEquals(all, assignSensorSlots(all, ids))
    }
}
