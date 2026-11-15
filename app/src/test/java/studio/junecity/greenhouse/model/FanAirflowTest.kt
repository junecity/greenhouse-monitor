package studio.junecity.greenhouse.model

import org.junit.Assert.*
import org.junit.Test

class FanAirflowTest {
    private val house = GreenhouseConfig(shape = GreenhouseShape.TShape, heightFeet = 8.0, attachedHeightFeet = 12.0)
    @Test fun threeSpeedReferenceAndTwoIndependentFans() {
        val result = house.airCirculation(listOf(CirculationFan(FanSpeed.High), CirculationFan(FanSpeed.Low)))
        assertEquals(256.0, result.outlets[0].cfm, 0.001)
        assertEquals(128.0, result.outlets[1].cfm, 0.001)
        assertEquals(384.0, result.combinedCfm, 0.001)
        assertEquals(2304.0, result.volumeFt3, 0.001)
        assertEquals(10.0, result.volumesPerHour, 0.001)
        assertEquals(6.0, result.minutesPerVolume!!, 0.001)
        assertEquals(3.704, result.outlets[0].milesPerHour, 0.01)
        assertEquals(1.64, result.outlets[0].dynamicPressurePa, 0.02)
        assertEquals(192.0, house.airCirculation().outlets.first().cfm, 0.001)
    }
    @Test fun bothOffHasNoCirculationPeriod() {
        val result = house.airCirculation(List(2) { CirculationFan(FanSpeed.Off) })
        assertEquals(0.0, result.volumesPerHour, 0.0)
        assertNull(result.minutesPerVolume)
    }
    @Test fun anglesUsePhysicalAxesAndRejectMissingWing() {
        val start = NormalizedPosition(.5, .5)
        val east = house.fanAim(start, NormalizedPosition(.75, .5))!!
        assertEquals(90.0, east.degrees, 0.001)
        assertEquals(45.0, house.fanAim(start, NormalizedPosition(.875, .25))!!.degrees, 0.001)
        assertEquals(270.0, house.fanAim(start, NormalizedPosition(.1, .5))!!.degrees, 0.001)
        assertNull(house.fanAim(start, start))
        assertNull(house.fanAim(NormalizedPosition(.1, .9), start))
    }
}
