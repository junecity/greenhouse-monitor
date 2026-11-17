package studio.junecity.greenhouse.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import studio.junecity.greenhouse.R
import studio.junecity.greenhouse.data.WIDGET_WINDOW_MS
import studio.junecity.greenhouse.widget.GreenhouseWidgetUiState
import studio.junecity.greenhouse.widget.WIDGET_CHART_GAP_MS

@Composable
internal fun MonitoringTrend(state: GreenhouseWidgetUiState, modifier: Modifier, height: Dp) {
    val amber = colorResource(R.color.climate_temperature)
    val muted = colorResource(R.color.climate_secondary)
    val divider = colorResource(R.color.climate_divider)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val samples = state.trend
        val bookends = if (samples.size >= 2)
            "${state.degrees(samples.first().temperatureF)} → ${state.degrees(samples.last().temperatureF)}" else null
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween) {
            ReadingText("Temperature · 6h", Modifier.weight(1f), size = 13, color = muted)
            if (bookends != null) {
                Spacer(Modifier.width(6.dp))
                ReadingText(bookends, size = 13, color = amber, weight = FontWeight.SemiBold)
            }
        }
        if (bookends == null) {
            Box(Modifier.fillMaxWidth().height(height), contentAlignment = Alignment.Center) {
                ReadingText("Not enough history", size = 13, color = muted)
            }
            return@Column
        }

        val first = samples.first().at
        val last = samples.last().at
        val start = state.now - WIDGET_WINDOW_MS
        fun fraction(at: Long) = ((at - start).toDouble() / WIDGET_WINDOW_MS).toFloat().coerceIn(0f, 1f)
        val reveal = remember(state.title, state.sensorId) { Animatable(0f) }
        var previousLast by remember(state.title, state.sensorId) { mutableStateOf<Long?>(null) }
        // Compose's animation clock observes the system animation duration scale (including zero).
        LaunchedEffect(state.title, state.sensorId, last) {
            val previous = previousLast?.takeIf { it in first..last }
            previousLast = last
            val baseFraction = fraction(previous ?: first)
            // The reveal ends at the most recent sample, even if it is hours old.
            val endFraction = fraction(last)
            reveal.snapTo(if (endFraction > 0f) baseFraction / endFraction else 0f)
            reveal.animateTo(1f, tween(if (previous == null) 500 else 350, easing = LinearOutSlowInEasing))
        }

        val min = samples.minOf { it.temperatureF }
        val max = samples.maxOf { it.temperatureF }
        val desc = "Six-hour temperature trend, ${samples.size} observations, ${state.degrees(min)} to ${state.degrees(max)}."
        Box(Modifier.fillMaxWidth().height(height).testTag("monitor-chart").semantics { contentDescription = desc }
            .drawWithCache {
                val margin = 5.dp.toPx()
                val bottom = size.height - margin
                val plotWidth = (size.width - margin * 2).coerceAtLeast(1f)
                val span = (max - min).coerceAtLeast(1.0) * 1.12
                val center = (min + max) / 2
                val coords = samples.map { sample ->
                    Offset(margin + fraction(sample.at) * plotWidth,
                        margin + (bottom - margin) * (.5 - (sample.temperatureF - center) / span).toFloat())
                }
                val traces = mutableListOf<Path>()
                val fills = mutableListOf<Path>()
                val isolated = mutableListOf<Offset>()
                var segmentStart = 0
                for (index in coords.indices) {
                    if (index != coords.lastIndex && samples[index + 1].at - samples[index].at <= WIDGET_CHART_GAP_MS) continue
                    if (segmentStart == index) isolated.add(coords[index])
                    else {
                        val line = Path().apply {
                            moveTo(coords[segmentStart].x, coords[segmentStart].y)
                            for (i in (segmentStart + 1)..index) lineTo(coords[i].x, coords[i].y)
                        }
                        traces.add(line)
                        fills.add(Path().apply {
                            addPath(line)
                            lineTo(coords[index].x, bottom)
                            lineTo(coords[segmentStart].x, bottom)
                            close()
                        })
                    }
                    segmentStart = index + 1
                }
                val wash = Brush.verticalGradient(listOf(amber.copy(alpha = .22f), amber.copy(alpha = 0f)),
                    startY = 0f, endY = size.height)
                val pen = Stroke(2.3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                onDrawBehind {
                    drawLine(divider, Offset(margin, bottom), Offset(size.width - margin, bottom), 1.dp.toPx())
                    val end = coords.last()
                    clipRect(right = margin + (end.x - margin) * reveal.value + 4.dp.toPx()) {
                        fills.forEach { drawPath(it, wash) }
                        traces.forEach { drawPath(it, amber, style = pen) }
                        isolated.forEach { drawCircle(amber, 2.dp.toPx(), it) }
                        drawCircle(amber, 3.8.dp.toPx(), end)
                    }
                }
            })
    }
}
