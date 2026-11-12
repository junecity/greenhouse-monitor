package studio.junecity.greenhouse.model

import org.junit.Assert.*
import org.junit.Test

class DrawnPlanTest {
    private fun point(x:Int,y:Int) = NormalizedPosition(x.toDouble(),y.toDouble())
    private fun draw(vararg points:NormalizedPosition) = DrawnPlan.fromStroke(points.toList(),GreenhouseConfig(),.4)

    @Test fun diagonalDragMakesRightAnglesAndPreservesFeet() {
        val g = requireNotNull(draw(point(22,30),point(6,22)))
        assertEquals(128.0,g.areaSquareFeet,.001)
        assertEquals(16.0,g.planLengthFeet,.001)
        assertEquals(8.0,g.planWidthFeet,.001)
        assertEquals(4,g.vertices.size)
    }

    @Test fun triangularWallsKeepTheirDiagonalAndSensorsSnapToNearestWall() {
        val g = requireNotNull(draw(point(0,8),point(8,0),point(16,8),point(0,8)))
        assertEquals(64.0,g.areaSquareFeet,.001)
        assertFalse(g.containsPoint(0.0,0.0))
        val sensor=g.coercePoint(0.0,0.0)
        assertEquals(4.0,sensor.xFeet,.001)
        assertEquals(4.0,sensor.yFeet,.001)
        assertTrue(g.containsPoint(sensor.xFeet,sensor.yFeet))
    }

    @Test fun concaveCornersAndInvalidStrokes() {
        val g=requireNotNull(draw(point(0,0),point(16,0),point(16,8),point(8,8),point(8,16),point(0,16)))
        assertEquals(192.0,g.areaSquareFeet,.001)
        assertFalse(g.containsPoint(12.0,12.0))
        assertNull(draw(point(0,0),point(8,8),point(0,8),point(8,0)))
        assertNull(draw(point(2,2),point(2,8)))
        assertNull(draw(point(2,2)))
    }

    @Test fun cornerEditsKeepPhysicalScaleAndSensorOffsets() {
        val original = GreenhouseConfig(sensorPositions=mapOf("bench" to SensorPosition(4.0,3.0,2.0)))
        val walls=listOf(NormalizedPosition(-2.5,0.0),point(16,0),point(16,8),point(0,8))
        val saved=requireNotNull(DrawnPlan.fromCorners(walls,original))
        assertEquals(18.5,saved.planLengthFeet,.0001)
        assertEquals(138.0,saved.areaSquareFeet,.0001)
        assertEquals(SensorPosition(6.5,3.0,2.0),saved.sensorPositions["bench"])
        assertFalse(saved.containsPoint(.4,7.5))
        assertEquals(5,DrawnPlan.fromCorners(walls.toMutableList().apply { add(2,point(14,4)) },original)!!.vertices.size)
    }

    @Test fun snappedAndFreeMeasurementsRemainDistinct() {
        val near=NormalizedPosition(11.33,0.12)
        assertEquals(point(11,0),DrawnPlan.snapCorner(near,point(0,0),point(16,8),.25))
        val stroke=listOf(point(0,0),near,point(0,8))
        assertEquals(45.32,DrawnPlan.fromStroke(stroke,GreenhouseConfig(),.1,snap=false)!!.areaSquareFeet,.0001)
        assertEquals(44.0,DrawnPlan.fromStroke(stroke,GreenhouseConfig(),.1)!!.areaSquareFeet,.0001)
    }
}
