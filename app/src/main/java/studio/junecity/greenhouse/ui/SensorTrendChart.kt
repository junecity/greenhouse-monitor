package studio.junecity.greenhouse.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import studio.junecity.greenhouse.data.READING_LIFETIME
import studio.junecity.greenhouse.data.SensorTimeline
import studio.junecity.greenhouse.model.Metric
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.*

internal val trendPalette = listOf(DashboardTokens.Amber, DashboardTokens.Blue, DashboardTokens.Green,
    Color(0xFFC69AFF), Color(0xFFFF8891), Color(0xFFFFE596))

internal data class TrendSensor(val id: String, val name: String, val timeline: SensorTimeline, val color: Color)
private data class Sample(val time: Long, val value: Double)
internal fun Metric.displayName() = when (this) {
    Metric.Vpd -> "VPD"
    Metric.DewPoint -> "Dew point"
    else -> name
}
private fun Metric.unit() = when (this) {
    Metric.Vpd -> "kPa"
    Metric.Humidity -> "%"
    else -> "°F"
}
internal fun chartTime(time: Long, pattern: String): String =
    DateTimeFormatter.ofPattern(pattern).withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(time))

// Round to a readable interval and keep constant readings visible.
private fun axis(values: List<List<Sample>>, metric: Metric): List<Double> {
    val low = values.minOfOrNull { s -> s.minOfOrNull { it.value } ?: Double.POSITIVE_INFINITY } ?: 0.0
    val high = values.maxOfOrNull { s -> s.maxOfOrNull { it.value } ?: Double.NEGATIVE_INFINITY } ?: 0.0
    if (!low.isFinite() || !high.isFinite()) return emptyList()
    val minimum = if (metric == Metric.Vpd) .1 else 1.0
    val raw = max((high - low) / 3.0, minimum)
    val magnitude = 10.0.pow(floor(log10(raw)))
    val step = listOf(1.0, 2.0, 2.5, 5.0, 10.0).first { it * magnitude >= raw } * magnitude
    val bottom = floor(low / step) * step - if (high == low) step else 0.0
    val count = max(2, ceil((high - bottom) / step).toInt())
    return (0..count).map { bottom + it * step }
}

@Composable internal fun SensorTrendChart(
    series: List<TrendSensor>, metric: Metric, start: Long, end: Long,
    selected: Long?, onSelect: (Long) -> Unit, modifier: Modifier
) {
    val lines = remember(series, metric, start, end) {
        series.map { sensor -> sensor.timeline.rows.asSequence().filter { it.timestampEpochMs in start..end }
            .map { Sample(it.timestampEpochMs, historyMetric(it, metric)) }.filter { it.value.isFinite() }.toList() }
    }
    val ticks = remember(lines, metric) { axis(lines, metric) }
    if (ticks.isEmpty()) {
        Box(modifier, contentAlignment = Alignment.Center) { Text("No readings in this period", color = DashboardTokens.Muted) }
        return
    }
    val span = (end - start).coerceAtLeast(1L)
    val selectedFraction = ((selected ?: end) - start).toFloat() / span
    val action by rememberUpdatedState(onSelect)
    val duration = accessibleMotionDuration(AppMotion.ChartRevealMs)
    val reveal = remember { Animatable(1f) }
    LaunchedEffect(metric, series.map { it.id }, duration) {
        if (duration == 0) reveal.snapTo(1f) else {
            reveal.snapTo(0f)
            reveal.animateTo(1f, tween(duration, easing = LinearOutSlowInEasing))
        }
    }
    val labelWidth = if (metric == Metric.Vpd) 55.dp else 43.dp
    val number = remember { java.text.DecimalFormat("0.##") }
    val plot = remember(lines, series, ticks, start, span, reveal) {
        Modifier.drawWithCache {
            fun x(time: Long) = ((time - start).toDouble() / span * size.width).toFloat()
            fun y(value: Double) = ((ticks.last() - value) / (ticks.last() - ticks.first()) * size.height).toFloat()
            val paths = ArrayList<Path>()
            val singletons = ArrayList<List<Offset>>()
            for (samples in lines) {
                val path = Path()
                val dots = ArrayList<Offset>()
                samples.forEachIndexed { i, sample ->
                    val first = i == 0 || sample.time - samples[i - 1].time > READING_LIFETIME
                    val point = Offset(x(sample.time), y(sample.value))
                    if (first) {
                        path.moveTo(point.x, point.y)
                        if (i == samples.lastIndex || samples[i+1].time - sample.time > READING_LIFETIME) dots.add(point)
                    } else path.lineTo(point.x, point.y)
                }
                paths.add(path)
                singletons.add(dots)
            }
            val line = Stroke(2.dp.toPx(), cap = StrokeCap.Round)
            onDrawBehind {
                ticks.forEach { drawLine(DashboardTokens.Divider, Offset(0f, y(it)), Offset(size.width,y(it)), 1.dp.toPx()) }
                for (i in 1..3) {
                    val xx = size.width * i / 4
                    drawLine(DashboardTokens.Divider.copy(alpha = .45f), Offset(xx, 0f), Offset(xx, size.height))
                }
                clipRect(right = size.width * reveal.value) {
                    paths.forEachIndexed { i, path ->
                        drawPath(path, series[i].color, style = line)
                        singletons[i].forEach { drawCircle(series[i].color, 2.dp.toPx(), it) }
                    }
                }
            }
        }
    }
    Column(modifier) {
        Row(Modifier.weight(1f)) {
            Column(Modifier.width(labelWidth).fillMaxHeight(),
                verticalArrangement = Arrangement.SpaceBetween) {
                ticks.asReversed().forEach { Text(number.format(it) + metric.unit(), color = DashboardTokens.Muted, fontSize = 11.sp, maxLines = 1) }
            }
            Box(Modifier.weight(1f).fillMaxHeight().padding(vertical = 7.dp)
                .background(DashboardTokens.Background, RoundedCornerShape(6.dp))
                .semantics {
                    contentDescription = "${metric.displayName()} trend"
                    stateDescription = selected?.let { chartTime(it, "MMM d, h:mm a") } ?: "Latest readings"
                    progressBarRangeInfo = ProgressBarRangeInfo(selectedFraction.coerceIn(0f, 1f), 0f..1f)
                    setProgress { action(start + (span * it.coerceIn(0f, 1f).toDouble()).toLong()); true }
                }
                .pointerInput(start, end) {
                    fun seek(x: Float) = action(start + (span * (x / size.width.coerceAtLeast(1)).coerceIn(0f,1f).toDouble()).toLong())
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        down.consume()
                        seek(down.position.x)
                        do {
                            val p = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                            p.consume()
                            seek(p.position.x)
                        } while (p.pressed)
                    }
                }
                .then(plot)) {
                if (selected != null) androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                    val xx = size.width * selectedFraction.coerceIn(0f,1f)
                    drawLine(Color.White.copy(alpha=.72f),Offset(xx,0f),Offset(xx,size.height),1.dp.toPx())
                    for (sensor in series) {
                        val row = sensor.timeline.at(selected) ?: continue
                        val yy = ((ticks.last() - historyMetric(row,metric)) / (ticks.last() - ticks.first()) * size.height).toFloat()
                        if (yy in 0f..size.height) {
                            drawCircle(DashboardTokens.Background, 4.dp.toPx(), Offset(xx,yy))
                            drawCircle(sensor.color, 2.7.dp.toPx(), Offset(xx,yy))
                        }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = labelWidth), horizontalArrangement = Arrangement.SpaceBetween) {
            val pattern = when {
                span > 36 * 3_600_000L -> "MMM d"
                span >= 24 * 3_600_000L -> "EEE h a"
                else -> "h:mm a"
            }
            listOf(start, start + span/2, end).forEach { Text(chartTime(it,pattern), color=DashboardTokens.Muted, fontSize=11.sp) }
        }
    }
}
