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
import studio.junecity.greenhouse.widget.WidgetFeedStatus

@Composable
internal fun MonitoringTrend(state: GreenhouseWidgetUiState, modifier: Modifier, height: Dp) {
    val amber = colorResource(R.color.climate_temperature)
    val muted = colorResource(R.color.climate_secondary)
    val divider = colorResource(R.color.climate_divider)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val samples = state.trend
        val bookends = state.trendEndpoints { state.degrees(it.temperatureF) }
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

        val last = samples.last().at
        val start = state.now - WIDGET_WINDOW_MS
        fun fraction(at: Long) = ((at - start).toDouble() / WIDGET_WINDOW_MS).toFloat().coerceIn(0f, 1f)
        val pulse = remember(state.title, state.sensorId) { Animatable(0f) }
        var previousLast by remember(state.title, state.sensorId) { mutableLongStateOf(last) }
        LaunchedEffect(state.title, state.sensorId, last, state.status) {
            val newObservation = last > previousLast && state.status == WidgetFeedStatus.Live
            previousLast = last
            if (newObservation) {
                pulse.snapTo(1f)
                pulse.animateTo(0f, tween(350, easing = LinearOutSlowInEasing))
            } else pulse.snapTo(0f)
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
                    if (index != coords.lastIndex && samples[index + 1].continuesFrom(samples[index])) continue
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
                    fills.forEach { drawPath(it, wash) }
                    traces.forEach { drawPath(it, amber, style = pen) }
                    isolated.forEach { drawCircle(amber, 2.dp.toPx(), it) }
                    if (pulse.value > 0f) drawCircle(amber.copy(alpha = .35f * pulse.value), margin, end)
                    drawCircle(amber, 3.8.dp.toPx(), end)
                }
            })
    }
}
