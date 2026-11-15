package studio.junecity.greenhouse.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import studio.junecity.greenhouse.model.DirectedFan
import studio.junecity.greenhouse.model.FanSpeed
import kotlin.math.*

private const val FAN_MOUNT_FEET = 5

@Composable internal fun VolumeFanMenu(fans: List<DirectedFan>, visible: Boolean, setVisible: (Boolean) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        InsightButton("Fans") { open = true }
        DropdownMenu(open, onDismissRequest = { open = false }) {
            Text("Modeled airflow · mounted ${FAN_MOUNT_FEET} ft above floor", Modifier.padding(horizontal = 14.dp, vertical = 5.dp))
            fans.forEach { Text("Fan ${it.index + 1} · ${it.speed.name} · ${it.heading.roundToInt() % 360}°",
                Modifier.padding(horizontal = 14.dp, vertical = 3.dp),
                color = if (it.index == 0) DashboardTokens.Amber else DashboardTokens.Blue) }
            if (fans.size < 2) Text("Place and aim fans in Dashboard → FAN.", Modifier.padding(14.dp))
            if (fans.isNotEmpty()) DropdownMenuItem(text = { Text(if (visible) "Hide fans" else "Show fans") },
                onClick = { setVisible(!visible); open = false })
        }
    }
}

@Composable internal fun FanMapOverlay(
    fans: List<DirectedFan>,
    project: DrawScope.(Double, Double, Double) -> Offset
) {
    if (fans.isEmpty()) return
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val motionMillis = accessibleMotionDuration(1000)
    val running = fans.any { it.speed != FanSpeed.Off }
    val seconds = remember { mutableDoubleStateOf(0.0) }
    LaunchedEffect(lifecycle, running, motionMillis) {
        if (running && motionMillis > 0) lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            val start = android.os.SystemClock.elapsedRealtime()
            while (true) {
                seconds.doubleValue = (android.os.SystemClock.elapsedRealtime() - start).toDouble() / motionMillis
                delay(50)
            }
        }
    }
    val description = remember(fans) {
        fans.joinToString("; ") { "Fan ${it.index + 1} ${it.speed.name}, ${it.heading.roundToInt() % 360} degrees from plan top" }
    }
    Canvas(Modifier.fillMaxSize().semantics { contentDescription = description }) {
        val time = seconds.doubleValue
        val radius = 10.dp.toPx()
        for (fan in fans) {
            val height = FAN_MOUNT_FEET.toDouble()
            val origin = project(fan.x, fan.y, height)
            val floor = project(fan.x, fan.y, 0.0)
            val tip = project(fan.x + fan.dx * fan.wallFeet, fan.y + fan.dy * fan.wallFeet, height)
            val delta = tip - origin
            val length = delta.getDistance()
            val active = fan.speed != FanSpeed.Off
            val color = when {
                !active -> DashboardTokens.Muted
                fan.index == 0 -> DashboardTokens.Amber
                else -> DashboardTokens.Blue
            }
            if (origin != floor) {
                drawLine(color.copy(alpha = .38f), origin, floor, 1.dp.toPx())
                drawCircle(color.copy(alpha = .65f), 2.dp.toPx(), floor)
            }
            if (active && length > 1f) {
                val axis = delta / length
                val side = Offset(-axis.y, axis.x)
                val wing = min(4.dp.toPx(), length / 4)
                drawLine(color.copy(alpha = .55f), origin, tip, 1.5.dp.toPx(), StrokeCap.Round)
                fun chevron(point: Offset, alpha: Float) {
                    val back = point - axis * wing * 1.5f
                    drawLine(color.copy(alpha = alpha), back + side * wing, point, 2.dp.toPx(), StrokeCap.Round)
                    drawLine(color.copy(alpha = alpha), back - side * wing, point, 2.dp.toPx(), StrokeCap.Round)
                }
                chevron(tip, 1f)
                // Illustrative motion scaled to the modeled outlet speed. Bound arrow count on large plans.
                val count = (length / 32.dp.toPx()).toInt().coerceIn(1, 7)
                for (i in 0 until count) {
                    val distance = (time * fan.outlet.feetPerMinute / 60 + fan.wallFeet * (i + .5) / count) % fan.wallFeet
                    val fraction = (distance / fan.wallFeet).toFloat()
                    if (fraction * length > radius && (1 - fraction) * length > 2 * wing)
                        chevron(origin + delta * fraction, .85f)
                }
            }
            drawCircle(DashboardTokens.Background, radius, origin)
            drawCircle(color, radius, origin, style = Stroke(1.3.dp.toPx()))
            val phase = time * fan.speed.flowFraction * PI * 1.5
            for (blade in 0..2) {
                val angle = phase + blade * 2 * PI / 3
                fun polar(r: Float, offset: Double) = origin + Offset(cos(angle + offset).toFloat(), sin(angle + offset).toFloat()) * (radius * r)
                val near = polar(.18f, -.15)
                val outer = polar(.82f, .48)
                val back = polar(.65f, 1.15)
                val control = polar(.95f, .89)
                val bladePath = Path().apply {
                    moveTo(near.x, near.y)
                    lineTo(outer.x, outer.y)
                    quadraticBezierTo(control.x, control.y, back.x, back.y)
                    close()
                }
                drawPath(bladePath, color)
            }
            drawCircle(color, radius * .16f, origin)
        }
    }
}
