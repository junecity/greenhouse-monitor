package studio.junecity.greenhouse.data

import org.junit.Assert.*
import org.junit.Test
import studio.junecity.greenhouse.model.*
import kotlin.math.sqrt

class SectionMeasurementsTest {
    private val tee = GreenhouseConfig(shape = GreenhouseShape.TShape, heightFeet = 8.0, attachedHeightFeet = 12.0)

    @Test fun twoCornerSurveyResolvesTheSelectedSectionAndEveryOutsideCorner() {
        val section = tee.planSection(true)
        val point = requireNotNull(section.fromTopCorners(5.0, sqrt(41.0)))
        assertEquals(7.0, point.xFeet, 1e-9)
        assertEquals(12.0, point.yFeet, 1e-9)
        assertTrue(tee.containsPoint(point.xFeet, point.yFeet))
        assertEquals(8, tee.floorCorners().size)
        assertTrue(tee.floorCorners().contains(SensorPosition(4.0, 8.0)))
        assertEquals(5.0, point.floorDistance(SensorPosition(4.0, 8.0)), 1e-9)
        assertNull(section.fromTopCorners(2.0, 2.0))
        assertNull(section.fromTopCorners(40.0, 40.0))
        assertNull(section.fromTopCorners(Double.NaN, 7.0))
        assertEquals(4, GreenhouseConfig().floorCorners().size)
        assertEquals(6, tee.copy(shape=GreenhouseShape.LShape).floorCorners().size)
    }

    @Test fun refineDistanceAlongDragBearingAndRespectTheClosestCorner() {
        val drop = SensorPosition(5.5, 10.0)
        val anchor = requireNotNull(tee.placementReference(drop))
        assertEquals(SensorPosition(4.0, 8.0), anchor)
        assertEquals(SensorPosition(4.75, 9.0), tee.refineDrop(drop, anchor, 1.25))
        assertEquals(anchor, tee.refineDrop(drop, anchor, 0.0))
        assertNull(tee.refineDrop(anchor, anchor, 1.0))
        assertNull(tee.refineDrop(drop, anchor, 12.0))
        assertNull(tee.refineDrop(drop, anchor, -1.0))
        val circle = tee.withPreset(GreenhouseShape.Circle)
        val center = requireNotNull(circle.placementReference(drop))
        assertEquals(SensorPosition(8.0, 8.0), center)
        assertEquals(SensorPosition(8.0, 5.0), circle.refineDrop(SensorPosition(8.0, 7.0), center, 3.0))
        assertNull(circle.refineDrop(drop, center, 9.0))
    }

    @Test fun upperAndLowerFollowTheirOwnRoofWhileMeasuredMountsRemainExact() {
        val low = SensorPosition(4.0, 5.0, level=MountLevel.Lower)
        val high = SensorPosition(8.0, 15.0, level=MountLevel.Upper)
        assertEquals(2.0, ZoneClimate.mountingHeight(tee, low)!!, 1e-9)
        assertEquals(9.0, ZoneClimate.mountingHeight(tee, high)!!, 1e-9)
        assertEquals(6.0, ZoneClimate.mountingHeight(tee.copy(attachedHeightFeet=8.0), high)!!, 1e-9)
        assertEquals(0.0, ZoneClimate.mountingHeight(tee, SensorPosition(4.0, 5.0, 0.0))!!, 1e-9)
        assertNull(ZoneClimate.mountingHeight(tee, SensorPosition(4.0, 5.0)))
        assertEquals(MountLevel.Upper, tee.reproject(high, tee).level)
    }

    @Test fun floorTraceBeginningPartwayAlongTheFirstWallHasOnlyThreeCorners() {
        val trace = listOf(1.0 to 5.0, 6.0 to 0.0, 12.0 to 6.0, 0.0 to 6.0)
            .map { (x,y) -> NormalizedPosition(x,y) }
        val drawn = requireNotNull(DrawnPlan.fromStroke(trace, GreenhouseConfig(), .25))
        assertEquals(3, drawn.vertices.size)
        assertEquals(36.0, drawn.areaSquareFeet, .00001)
    }

    @Test fun heatingUsesTwoVolumesAndOnlyTheExposedStepAtTheJoin() {
        val unit = EnergyUnit.Kwh
        val walls = (48*8 + 48*12 - 2*8*8)*.8
        val roof = 256*1.1
        val volume = 128*(8+12)*.9
        val expected = ((walls+roof)*.95 + .018*volume)/unit.btu
        assertEquals(expected, heatingCoefficient(tee, unit).nominal, 1e-10)
        assertEquals(expected, heatingCoefficient(tee.copy(heightFeet=12.0, attachedHeightFeet=8.0),unit).nominal, 1e-10)
        val legacy = tee.copy(attachedHeightFeet=null)
        assertEquals(heatingCoefficient(legacy,unit), heatingCoefficient(legacy.copy(attachedHeightFeet=8.0),unit))
        assertTrue(heatingCoefficient(legacy,unit).nominal < expected)
        val rectangle=tee.copy(shape=GreenhouseShape.Rectangle)
        assertEquals(heatingCoefficient(rectangle,unit), heatingCoefficient(rectangle.copy(attachedHeightFeet=null),unit))
        assertEquals(8.0, ZoneClimate.sectionHeight(tee, 3.0), 0.0)
        assertEquals(12.0, ZoneClimate.sectionHeight(tee, 15.0), 0.0)
    }
}
