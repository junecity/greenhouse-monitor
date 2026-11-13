package studio.junecity.greenhouse.ui

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import studio.junecity.greenhouse.data.SpatialInterpolator
import studio.junecity.greenhouse.data.SpatialSample
import studio.junecity.greenhouse.model.GreenhouseConfig
import studio.junecity.greenhouse.model.Metric
import kotlin.math.*

private val thermal = listOf(Color(0xff568aff),Color(0xff49ccdd),Color(0xffadd989),Color(0xffffbb55),Color(0xfffa714e))
private val moisture = listOf(Color(0xffeabb72),Color(0xffb6d397),Color(0xff5bcabf),Color(0xff519ee6),Color(0xff9885ed))

internal enum class ZoneHeatScale(
    val minimum: Double, val maximum: Double, val unit: String, val colors: List<Color>
) {
    Temperature(40.0, 90.0, "°F", thermal),
    Humidity(35.0, 100.0, "%", moisture),
    VaporPressureDeficit(0.0, 3.0, " kPa", thermal);

    fun color(value: Double): Color {
        val index = ((value - minimum) / (maximum - minimum)).coerceIn(0.0, 1.0) * colors.lastIndex
        val lower = index.toInt().coerceAtMost(colors.lastIndex - 1)
        return lerp(colors[lower], colors[lower + 1], (index - lower).toFloat())
    }

    companion object {
        fun forMetric(metric: Metric) = when (metric) {
            Metric.Temperature, Metric.DewPoint -> Temperature
            Metric.Humidity -> Humidity
            Metric.Vpd -> VaporPressureDeficit
        }
    }
}

@Composable
internal fun ZoneHeatLegend(scale: ZoneHeatScale) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("${scale.minimum.toInt()}${scale.unit}", color = DashboardTokens.Muted, fontSize = 12.sp)
        Spacer(Modifier.width(54.dp).height(5.dp)
            .background(Brush.horizontalGradient(scale.colors), RoundedCornerShape(3.dp)))
        Text("${scale.maximum.toInt()}${scale.unit}", color = DashboardTokens.Muted, fontSize = 12.sp)
        Text("· Estimated nearby", color = DashboardTokens.Muted, fontSize = 12.sp)
    }
}

internal val zoneFloorColor = Color(0xff22343a)

internal fun planHeatLayer(g: GreenhouseConfig, samples: List<SpatialSample>, scale: ZoneHeatScale): Bitmap? {
    if (samples.isEmpty()) return null

    val pixelsPerFoot = 64.0 / max(g.planLengthFeet, g.planWidthFeet)
    val width = ceil(g.planLengthFeet * pixelsPerFoot).toInt().coerceIn(1, 64)
    val height = ceil(g.planWidthFeet * pixelsPerFoot).toInt().coerceIn(1, 64)
    val fadeDistance = min(g.planLengthFeet, g.planWidthFeet).coerceAtLeast(1.0) / 2
    val pixels = IntArray(width * height)
    for (row in 0 until height) for (column in 0 until width) {
        val x = (column + .5) * g.planLengthFeet / width
        val y = (row + .5) * g.planWidthFeet / height
        val value = SpatialInterpolator.estimate(samples, x, y) ?: continue
        val nearest = samples.minOf { hypot(x - it.xFeet, y - it.yFeet) }
        val opacity = .88 / (1 + (nearest / fadeDistance).pow(2))
        pixels[row * width + column] = scale.color(value).copy(alpha = opacity.toFloat()).toArgb()
    }
    return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
}
