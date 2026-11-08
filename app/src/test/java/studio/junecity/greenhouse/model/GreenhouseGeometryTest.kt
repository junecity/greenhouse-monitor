package studio.junecity.greenhouse.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GreenhouseGeometryTest {
    @Test fun rectangleAreaAndFootprintAreAccurate() {
        val greenhouse = GreenhouseConfig(lengthFeet = 16.0, widthFeet = 8.0)
        assertEquals(128.0, greenhouse.areaSquareFeet, 0.0)
        assertEquals(16.0, greenhouse.planLengthFeet, 0.0)
        assertEquals(8.0, greenhouse.planWidthFeet, 0.0)
    }

    @Test fun twoEightBySixteenSectionsProduceAccurateTPlan() {
        val greenhouse = GreenhouseConfig(
            lengthFeet = 16.0,
            widthFeet = 8.0,
            shape = GreenhouseShape.TShape,
            attachedLengthFeet = 16.0,
            attachedWidthFeet = 8.0
        )
        assertEquals(256.0, greenhouse.areaSquareFeet, 0.0)
        assertEquals(16.0, greenhouse.planLengthFeet, 0.0)
        assertEquals(24.0, greenhouse.planWidthFeet, 0.0)
        assertTrue(greenhouse.containsPoint(1.0, 4.0))
        assertTrue(greenhouse.containsPoint(8.0, 20.0))
        assertFalse(greenhouse.containsPoint(1.0, 20.0))
    }

    @Test fun widerStemIsCenteredAndIncludedInFootprint() {
        val greenhouse = GreenhouseConfig(
            lengthFeet = 8.0,
            widthFeet = 8.0,
            shape = GreenhouseShape.TShape,
            attachedLengthFeet = 16.0,
            attachedWidthFeet = 12.0
        )
        assertEquals(256.0, greenhouse.areaSquareFeet, 0.0)
        assertEquals(12.0, greenhouse.planLengthFeet, 0.0)
        assertEquals(2.0, greenhouse.crossbarLeftFeet, 0.0)
        assertEquals(0.0, greenhouse.stemLeftFeet, 0.0)
        assertFalse(greenhouse.containsPoint(1.0, 4.0))
        assertTrue(greenhouse.containsPoint(1.0, 20.0))
    }
}
