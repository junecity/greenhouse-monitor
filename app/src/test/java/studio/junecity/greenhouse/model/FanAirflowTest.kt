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

    private fun fan(speed: FanSpeed, heading: Double, x: Double = .5, y: Double = .25) =
        CirculationFan(speed, FanAim(NormalizedPosition(x,y), heading))

    @Test fun combiningJetsUsesMomentumAndPreservesGrossCirculation() {
        val same = house.airCirculation(listOf(fan(FanSpeed.High, 90.0), fan(FanSpeed.High, 90.0)))
        val opposed = house.airCirculation(listOf(fan(FanSpeed.High, 90.0), fan(FanSpeed.High, 270.0)))
        assertEquals(same.combinedCfm, opposed.combinedCfm, 0.0)
        assertEquals(90.0, same.discharge!!.heading!!, 1e-8)
        assertEquals(same.outlets.first().momentumNewtons * 2, same.discharge!!.newtons, 1e-8)
        assertNull(opposed.discharge!!.heading)
        val mixed = house.airCirculation(listOf(fan(FanSpeed.Low, 90.0), fan(FanSpeed.High, 0.0)))
        assertEquals(14.0362, mixed.discharge!!.heading!!, .001)
        assertEquals(mixed.outlets[1].momentumNewtons * .25, mixed.outlets[0].momentumNewtons, 1e-8)
        assertNull(house.airCirculation(listOf(fan(FanSpeed.High, 0.0), CirculationFan())).discharge)
        assertNotNull(house.airCirculation(listOf(fan(FanSpeed.High, 0.0), CirculationFan(FanSpeed.Off))).discharge)
    }

    @Test fun pathStopsAtFirstWallAndIdentifiesTheSharedOpening() {
        fun path(x: Double, y: Double, angle: Double) = house.airCirculation(listOf(fan(FanSpeed.High, angle, x, y))).placed.single()
        assertEquals(18.0, path(.5, .25, 180.0).wallFeet, 1e-8)
        assertTrue(path(.5, .25, 180.0).acrossJunction)
        assertFalse(path(.1, .25, 180.0).acrossJunction)
        assertEquals(2.0, path(.1, .25, 180.0).wallFeet, 1e-8)
        assertEquals(0.0, path(.5, 0.0, 0.0).wallFeet, 1e-8)
        assertEquals(24.0, path(.5, 0.0, 180.0).wallFeet, 1e-8)
        val circle = house.copy(shape = GreenhouseShape.Circle)
        assertEquals(8.0, circle.airCirculation(listOf(fan(FanSpeed.Medium, 48.0, .5, .5))).placed.single().wallFeet, 1e-8)
        assertTrue(house.airCirculation(listOf(fan(FanSpeed.High, 0.0, .01, .99))).placed.isEmpty())
    }
}
