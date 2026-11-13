package studio.junecity.greenhouse.model

import org.junit.Assert.*
import org.junit.Test

class GreenhouseInventoryTest {
    private val first = GreenhouseConfig()
    private val second = GreenhouseConfig(id="house2", name="Greenhouse 2")
    private val third = GreenhouseConfig(id="house3", name="Greenhouse 3")
    private val prefs = AppPreferences(greenhouses=listOf(first,second,third),activeGreenhouseId=second.id,
        sensorGreenhouseIds=mapOf("door" to second.id, "bench" to third.id))
    @Test fun removingInactiveHouseKeepsSelectionAndOtherAssignments() {
        val after=prefs.removeFloorPlan(first.id, emptyList())
        assertEquals(second,after.activeGreenhouse)
        assertEquals(prefs.sensorGreenhouseIds,after.sensorGreenhouseIds)
    }
    @Test fun removingActiveHouseUnassignsBothExplicitAndLegacySensors() {
        val sensors=listOf("door","legacy","bench").map { GoveeSensor(it,"H5179",it,69.0,80.0,true,23,second.id) }
        val after=prefs.removeFloorPlan(second.id, sensors)
        assertEquals("",after.sensorGreenhouseIds["legacy"])
        assertEquals(first,after.activeGreenhouse)
        assertEquals(listOf(first,third),after.greenhouses)
        val moved=sensors.inSavedHouses(after)
        assertEquals(listOf("","",third.id),moved.map { it.greenhouseId })
        assertEquals(sensors.map { it.updatedAtEpochMs },moved.map { it.updatedAtEpochMs })
        assertEquals(moved,moved.inSavedHouses(after))
    }
    @Test fun repeatedRemovalAndLastHouseAreSafe() {
        val last=prefs.removeFloorPlan(first.id, emptyList()).removeFloorPlan(second.id, emptyList())
        assertSame(last,last.removeFloorPlan(second.id, emptyList()))
        assertSame(last,last.removeFloorPlan(third.id, emptyList()))
    }
}
