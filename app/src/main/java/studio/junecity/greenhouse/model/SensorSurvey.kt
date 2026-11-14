package studio.junecity.greenhouse.model

import kotlin.math.hypot
import kotlin.math.sqrt

/** Local floor measurements start at the upper-left of the selected section on the plan. */
internal data class PlanSection(val left: Double, val top: Double, val width: Double, val depth: Double) {
    fun fromTopCorners(leftDistance: Double, rightDistance: Double): SensorPosition? {
        if (!leftDistance.isFinite() || !rightDistance.isFinite() || leftDistance < 0 || rightDistance < 0) return null
        val x = (leftDistance * leftDistance - rightDistance * rightDistance + width * width) / (2 * width)
        val square = leftDistance * leftDistance - x * x
        if (square < -1e-8 || x !in -1e-8..width+1e-8) return null
        val y = sqrt(square.coerceAtLeast(0.0))
        if (y > depth+1e-8) return null
        return SensorPosition(left + x.coerceIn(0.0, width), top + y.coerceIn(0.0, depth))
    }
}

internal fun GreenhouseConfig.planSection(stem: Boolean) = when {
    !hasStem -> PlanSection(0.0, 0.0, planLengthFeet, planWidthFeet)
    stem -> PlanSection(stemLeftFeet, widthFeet, attachedWidthFeet, attachedLengthFeet)
    else -> PlanSection(crossbarLeftFeet, 0.0, lengthFeet, widthFeet)
}

internal fun GreenhouseConfig.floorCorners() = outline().map {
    SensorPosition(it.x * planLengthFeet, it.y * planWidthFeet)
}.distinct().let { corners ->
    // An aligned L seam can introduce a point on the middle of a straight outside wall.
    corners.filterIndexed { i, b ->
        val a = corners[(i + corners.size - 1) % corners.size]
        val c = corners[(i + 1) % corners.size]
        kotlin.math.abs((b.xFeet-a.xFeet)*(c.yFeet-b.yFeet) - (b.yFeet-a.yFeet)*(c.xFeet-b.xFeet)) > 1e-9
    }
}

internal fun SensorPosition.floorDistance(other: SensorPosition) = hypot(xFeet-other.xFeet, yFeet-other.yFeet)

/** The drop supplies the bearing; the optional floor measurement supplies the radius. */
internal fun GreenhouseConfig.placementReference(drop: SensorPosition): SensorPosition? =
    if (shape == GreenhouseShape.Circle) SensorPosition(lengthFeet / 2, lengthFeet / 2)
    else floorCorners().minByOrNull { drop.floorDistance(it) }

internal fun GreenhouseConfig.refineDrop(drop: SensorPosition, reference: SensorPosition, feet: Double): SensorPosition? {
    if (!feet.isFinite() || feet < 0) return null
    if (feet == 0.0) return reference
    val distance = drop.floorDistance(reference)
    if (distance < 1e-8) return null
    val point = SensorPosition(reference.xFeet + (drop.xFeet - reference.xFeet) * feet / distance,
        reference.yFeet + (drop.yFeet - reference.yFeet) * feet / distance)
    if (!containsPoint(point.xFeet, point.yFeet)) return null
    // Keep the highlighted corner consistent with the grower's measurement, including ties.
    if (shape != GreenhouseShape.Circle && floorCorners().any { point.floorDistance(it) + 1e-7 < feet }) return null
    return point
}
