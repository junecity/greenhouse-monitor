package studio.junecity.greenhouse.model

import kotlin.math.*

/** Fractions are scenario assumptions for two equal circulation fans, not hardware commands. */
enum class FanSpeed(val flowFraction: Double) { Off(0.0), Low(0.5), Medium(0.75), High(1.0) }

data class CirculationFan(val speed: FanSpeed = FanSpeed.Medium, val aim: FanAim? = null)

/** Clockwise from the top of the saved plan. The axes have physical dimensions in feet. */
data class FanAim(val origin: NormalizedPosition, val degrees: Double)

internal fun GreenhouseConfig.fanAim(start: NormalizedPosition, toward: NormalizedPosition): FanAim? {
    val dx = (toward.x - start.x) * planLengthFeet
    val dy = (toward.y - start.y) * planWidthFeet
    if (!containsPoint(start.x * planLengthFeet, start.y * planWidthFeet) || kotlin.math.hypot(dx, dy) < 0.01) return null
    return FanAim(start, (Math.toDegrees(kotlin.math.atan2(dx, -dy)) + 360) % 360)
}

internal data class FanFlow(val cfm: Double) {
    // Mean velocity through an assumed circular 12-inch outlet.
    val feetPerMinute get() = cfm / (PI * 0.5 * 0.5)
    val milesPerHour get() = feetPerMinute / 88.0
    // Discharge momentum per second = density × volume flow × outlet velocity (SI).
    val momentumNewtons get() = 1.2 * (cfm * 0.00047194745) * (feetPerMinute * 0.00508)
    val dynamicPressurePa get() = 0.6 * (feetPerMinute * 0.00508) * (feetPerMinute * 0.00508)
}

internal fun GreenhouseConfig.estimatedVolumeFt3(): Double {
    val mainPeak = heightFeet ?: 8.0
    val attachedArea = if (hasStem) attachedLengthFeet * attachedWidthFeet else 0.0
    return .9 * ((areaSquareFeet - attachedArea) * mainPeak + attachedArea * (attachedHeightFeet ?: mainPeak))
}

internal data class DirectedFan(val index: Int, val speed: FanSpeed, val x: Double, val y: Double,
    val dx: Double, val dy: Double, val heading: Double, val wallFeet: Double, val outlet: FanFlow,
    val acrossJunction: Boolean)

internal data class DischargeMomentum(val x: Double, val y: Double) {
    val newtons get() = hypot(x, y)
    val heading get() = if (newtons < 1e-8) null else (Math.toDegrees(atan2(x, -y)) + 360) % 360
}

internal data class AirCirculation(val volumeFt3: Double, val outlets: List<FanFlow>, val placed: List<DirectedFan>) {
    val discharge: DischargeMomentum? get() {
        if (placed.count { it.speed != FanSpeed.Off } != outlets.count { it.cfm > 0 }) return null
        return DischargeMomentum(placed.sumOf { it.dx * it.outlet.momentumNewtons },
            placed.sumOf { it.dy * it.outlet.momentumNewtons })
    }
    val combinedCfm = outlets.sumOf { it.cfm }
    val volumesPerHour get() = combinedCfm * 60 / volumeFt3
    val minutesPerVolume get() = if (combinedCfm > 0) volumeFt3 / combinedCfm else null
}

internal fun GreenhouseConfig.airCirculation(fans: List<CirculationFan> = circulationFans): AirCirculation {
    // UConn horizontal airflow sizing guideline: 2 CFM per square foot across both fans.
    val highCfmPerFan = areaSquareFeet * 2.0 / 2
    val outlets = fans.map { FanFlow(highCfmPerFan * it.speed.flowFraction) }
    val placed = fans.mapIndexedNotNull { index, fan ->
        val aim = fan.aim ?: return@mapIndexedNotNull null
        val x = aim.origin.x * planLengthFeet
        val y = aim.origin.y * planWidthFeet
        if (!containsPoint(x, y) || !aim.degrees.isFinite()) return@mapIndexedNotNull null
        val radians = Math.toRadians(aim.degrees)
        val dx = sin(radians)
        val dy = -cos(radians)
        val wall = distanceToWall(x, y, dx, dy)
        val junction = if (hasStem && abs(dy) > 1e-9) (widthFeet - y) / dy else Double.NaN
        val crosses = junction >= 0 && junction < wall - 1e-8 &&
            x + dx * junction > max(stemLeftFeet, crossbarLeftFeet) &&
            x + dx * junction < min(stemLeftFeet + attachedWidthFeet, crossbarLeftFeet + lengthFeet)
        DirectedFan(index, fan.speed, x, y, dx, dy, aim.degrees, wall, outlets[index], crosses)
    }
    return AirCirculation(estimatedVolumeFt3(), outlets, placed)
}

// Test the intervals between ray/outline crossings. At a reentrant corner a ray can touch
// a vertex and stay inside; a downstream wing must never extend a ray past its first exit.
private fun GreenhouseConfig.distanceToWall(x: Double, y: Double, dx: Double, dy: Double): Double {
    if (shape == GreenhouseShape.Circle) {
        val r = lengthFeet / 2
        val ox = x - r
        val oy = y - r
        val dot = ox * dx + oy * dy
        return (-dot + sqrt(max(0.0, dot * dot + r * r - ox * ox - oy * oy))).coerceAtLeast(0.0)
    }
    val outline = outline()
    val cuts = mutableListOf(0.0, hypot(planLengthFeet, planWidthFeet) + 1.0)
    for (i in outline.indices) {
        val a = outline[i]
        val b = outline[(i + 1) % outline.size]
        val ax = a.x * planLengthFeet - x
        val ay = a.y * planWidthFeet - y
        val ex = (b.x - a.x) * planLengthFeet
        val ey = (b.y - a.y) * planWidthFeet
        val det = dx * ey - dy * ex
        if (abs(det) < 1e-10) continue
        val t = (ax * ey - ay * ex) / det
        val u = (ax * dy - ay * dx) / det
        if (t >= 0 && u in -1e-9..1.000000001) cuts += t
    }
    cuts.sort()
    for (i in 1 until cuts.size) {
        if (cuts[i] - cuts[i-1] < 1e-8) continue
        val mid = (cuts[i] + cuts[i-1]) / 2
        if (!containsPoint(x + dx * mid, y + dy * mid)) return cuts[i-1]
    }
    return 0.0
}
