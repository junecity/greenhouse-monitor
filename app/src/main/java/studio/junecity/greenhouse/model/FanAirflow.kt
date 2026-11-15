package studio.junecity.greenhouse.model

import kotlin.math.PI

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
    val dynamicPressurePa get() = 0.6 * (feetPerMinute * 0.00508) * (feetPerMinute * 0.00508)
}

internal fun GreenhouseConfig.estimatedVolumeFt3(): Double {
    val mainPeak = heightFeet ?: 8.0
    val attachedArea = if (hasStem) attachedLengthFeet * attachedWidthFeet else 0.0
    return .9 * ((areaSquareFeet - attachedArea) * mainPeak + attachedArea * (attachedHeightFeet ?: mainPeak))
}

internal data class AirCirculation(val volumeFt3: Double, val outlets: List<FanFlow>) {
    val combinedCfm = outlets.sumOf { it.cfm }
    val volumesPerHour get() = combinedCfm * 60 / volumeFt3
    val minutesPerVolume get() = if (combinedCfm > 0) volumeFt3 / combinedCfm else null
}

internal fun GreenhouseConfig.airCirculation(fans: List<CirculationFan> = circulationFans): AirCirculation {
    // UConn horizontal airflow sizing guideline: 2 CFM per square foot across both fans.
    val highCfmPerFan = areaSquareFeet * 2.0 / 2
    return AirCirculation(estimatedVolumeFt3(), fans.map { FanFlow(highCfmPerFan * it.speed.flowFraction) })
}
