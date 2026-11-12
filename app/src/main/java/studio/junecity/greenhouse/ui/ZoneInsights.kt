package studio.junecity.greenhouse.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import studio.junecity.greenhouse.data.*
import studio.junecity.greenhouse.model.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs

internal enum class Insight(val title: String) {
    Compare("Compare sensors"), Replay("Overnight replay"), Overnight("Overnight report"), Alert("Alert details")
}

private val timeFormat = DateTimeFormatter.ofPattern("MMM d, h:mm a")
private fun clock(time: Long) = Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()).format(timeFormat)
private fun label(s: GoveeSensor) = if (s.zone == "Unassigned") s.name else "${s.zone} · ${s.name}"
private fun minutes(ms: Long) = "${ms / 60_000} min"

@Composable internal fun InsightButton(text: String, action: () -> Unit) {
    TextButton(onClick = action, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)) {
        Text(text, color = DashboardTokens.Amber)
    }
}

@Composable internal fun InsightsDialog(
    mode: Insight, state: GreenhouseUiState, d: (Float) -> Dp, t: (Float) -> TextUnit,
    alertSensor: String? = null, allowVideo: Boolean = false, alertTime: Long? = null, close: () -> Unit
) {
    val g = state.preferences.activeGreenhouse
    val sensors = state.sensors.filter { it.greenhouseId == g.id }
    val ids = sensors.map { it.id }.toSet()
    val timelines = remember(ids, state.history) {
        state.history.filter { it.sensorId in ids }.groupBy { it.sensorId }.mapValues { SensorTimeline(it.value) }
    }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); kotlinx.coroutines.delay(10_000) } }
    val night = NightWindow.at(now)
    Dialog(onDismissRequest = close) {
        Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp), color = DashboardTokens.Panel, contentColor = Color.White) {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text(mode.title, style = MaterialTheme.typography.titleLarge)
                    InsightButton("Close", close)
                }
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(g.name, color = DashboardTokens.Amber)
                    if (mode != Insight.Alert) Text("${clock(night.start)} – ${clock(night.end)}")
                    when (mode) {
                        Insight.Overnight -> {
                            val threshold = state.preferences.notifications.lowTemperatureF
                            val stats = sensors.map { it to timelines[it.id]?.summarize(night, threshold) }
                            val cold = stats.minByOrNull { it.second?.coldest?.temperatureF ?: Double.POSITIVE_INFINITY }
                            val low = cold?.second?.coldest
                            if (cold == null || low == null) Text("No overnight measurements yet.")
                            else Text("Coldest · ${label(cold.first)} · ${formatTemp(low.temperatureF)} at ${clock(low.timestampEpochMs)}")
                            stats.forEach { (sensor, result) ->
                                val covered = result?.coverageMs ?: 0
                                Text(label(sensor), color = DashboardTokens.Blue)
                                if (covered > 0) Text("About ${minutes(result!!.belowMs)} below ${formatTemp(threshold)}")
                                Text("${minutes(covered)} observed · ${minutes(night.end - night.start - covered)} without coverage")
                            }
                        }
                        Insight.Compare -> CompareSensors(sensors, timelines, night, now)
                        Insight.Replay -> {
                            var progress by remember { mutableFloatStateOf(1f) }
                            val at = night.start + ((night.end - night.start) * progress.toDouble()).toLong()
                            val scale = remember(timelines, g, night.start, night.end / 60_000L, state.metric) {
                                val values = timelines.filterKeys { it in g.sensorPositions }.values.flatMap { series ->
                                    series.rows.filter { it.timestampEpochMs in (night.start - READING_LIFETIME)..night.end }
                                        .map { historyMetric(it, state.metric) }.filter { it.isFinite() }
                                }
                                values.minOrNull()?.let { it..values.max() }
                            }
                            val readings = sensors.mapNotNull { sensor ->
                                val row = timelines[sensor.id]?.at(at) ?: return@mapNotNull null
                                if (sensor.id !in g.sensorPositions) return@mapNotNull null
                                sensor.copy(temperatureF = row.temperatureF, humidityPercent = row.humidityPercent, online = true, updatedAtEpochMs = row.timestampEpochMs)
                            }
                            Text(clock(at))
                            Slider(value = progress, onValueChange = { progress = it })
                            if (readings.isEmpty()) Text("No placed sensors with measurements at this time.")
                            else {
                                HeatMap(SpatialInterpolator.samples(readings, g, state.metric, at), state.metric, g, readings, d, t, { _,_,_ -> }, Modifier.fillMaxWidth().height(145.dp), editable = false, scale = scale)
                                Text("Markers show readings. Colors estimate conditions between sensors using the current layout.")
                                readings.forEach { Text("${label(it)} · ${clock(it.updatedAtEpochMs)}") }
                            }
                        }
                        Insight.Alert -> {
                            val eventTime = (alertTime ?: now).coerceAtMost(now)
                            Text("Alert · ${clock(eventTime)}")
                            val relevant = sensors.filter { alertSensor == null || it.id == alertSensor }
                            val marker = g.sensorPositions[alertSensor]
                            if (marker != null) {
                                Canvas(Modifier.fillMaxWidth().height(86.dp)) {
                                    val scale = minOf(size.width / g.planLengthFeet, size.height / g.planWidthFeet)
                                    val left = (size.width - g.planLengthFeet * scale) / 2
                                    val outline = androidx.compose.ui.graphics.Path()
                                    g.outline().forEachIndexed { i, p ->
                                        val x = (left + p.x * g.planLengthFeet * scale).toFloat()
                                        val y = (p.y * g.planWidthFeet * scale).toFloat()
                                        if (i == 0) outline.moveTo(x,y) else outline.lineTo(x,y)
                                    }
                                    outline.close()
                                    drawPath(outline, DashboardTokens.PanelRaised)
                                    drawPath(outline, DashboardTokens.Muted, style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
                                    drawCircle(DashboardTokens.Amber, 5.dp.toPx(), Offset((left+marker.xFeet*scale).toFloat(), (marker.yFeet*scale).toFloat()))
                                }
                            }
                            if (relevant.isEmpty()) Text("No matching sensor in the current greenhouse.")
                            relevant.forEach { sensor ->
                                Text(label(sensor), color = DashboardTokens.Blue)
                                g.sensorPositions[sensor.id]?.let { Text("Location · %.1f ft across, %.1f ft down".format(it.xFeet, it.yFeet)) }
                                val recent = timelines[sensor.id]?.rows.orEmpty().filter { it.timestampEpochMs in (eventTime - 3_600_000)..eventTime }
                                if (recent.isEmpty()) Text("No measurements in the last hour.")
                                else {
                                    Text("Last hour · ${formatTemp(recent.first().temperatureF)} → ${formatTemp(recent.last().temperatureF)} at ${clock(recent.last().timestampEpochMs)}")
                                    Trace(recent.map { it.timestampEpochMs to it.temperatureF }, eventTime - 3_600_000, eventTime)
                                }
                            }
                            if (allowVideo) RemoteCameraFeed(d, t, Modifier.fillMaxWidth().height(136.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun CompareSensors(sensors: List<GoveeSensor>, timelines: Map<String, SensorTimeline>, night: NightWindow, now: Long) {
    if (sensors.size < 2) { Text("Add two sensors to compare readings."); return }
    val ids = sensors.map { it.id }
    var first by remember(ids) { mutableStateOf(ids[0]) }
    var second by remember(ids) { mutableStateOf(ids[1]) }
    val a = sensors.first { it.id == first }
    val b = sensors.first { it.id == second }
    Picker(a, sensors.filter { it.id != second }) { first = it }
    Picker(b, sensors.filter { it.id != first }) { second = it }
    if (a.isFresh(now) && b.isFresh(now) && a.updatedAtEpochMs <= now && b.updatedAtEpochMs <= now) {
        Text("Current difference · ${formatTemp(abs(a.temperatureF!! - b.temperatureF!!))} · %.0f humidity points".format(abs(a.humidityPercent!! - b.humidityPercent!!)))
    } else Text("Live comparison paused until both sensors report fresh readings.")
    // Fixed five-minute samples keep the two series time-aligned and bound replay work.
    val spreads = remember(first, second, timelines, night.start, night.end / 300_000) {
        (night.start..night.end step 300_000L).mapNotNull { time ->
            val left = timelines[first]?.at(time) ?: return@mapNotNull null
            val right = timelines[second]?.at(time) ?: return@mapNotNull null
            Triple(time,abs(left.temperatureF - right.temperatureF),abs(left.humidityPercent - right.humidityPercent))
        }
    }
    if (spreads.isEmpty()) Text("No overlapping overnight readings.")
    else {
        val peak = spreads.maxBy { it.second }
        Text("Overnight temperature difference · peak ${formatTemp(peak.second)} at ${clock(peak.first)}")
        Trace(spreads.map { it.first to it.second }, night.start, night.end, 300_000)
        val humidPeak = spreads.maxBy { it.third }
        Text("Overnight humidity difference · peak %.0f points at %s".format(humidPeak.third, clock(humidPeak.first)))
        Trace(spreads.map { it.first to it.third }, night.start, night.end, 300_000)
    }
}

@Composable private fun Picker(selected: GoveeSensor, options: List<GoveeSensor>, select: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        InsightButton("${label(selected)} · ${if(selected.updatedAtEpochMs > 0) clock(selected.updatedAtEpochMs) else "No reading"} ▾") { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { sensor -> DropdownMenuItem(text = { Text(label(sensor)) }, onClick = { select(sensor.id); open = false }) }
        }
    }
}

@Composable private fun Trace(points: List<Pair<Long, Double>>, start: Long, end: Long, maxGap: Long = READING_LIFETIME) {
    Canvas(Modifier.fillMaxWidth().height(54.dp).background(DashboardTokens.Background)) {
        val low = points.minOf { it.second } - 0.5
        val high = points.maxOf { it.second } + 0.5
        fun point(i: Int) = Offset(((points[i].first - start).toDouble() / (end - start).coerceAtLeast(1) * size.width).toFloat(),
            ((high - points[i].second) / (high - low) * size.height).toFloat())
        points.indices.forEach { i ->
            drawCircle(DashboardTokens.Amber, 2.dp.toPx(), point(i))
            if (i > 0 && points[i].first - points[i - 1].first <= maxGap)
                drawLine(DashboardTokens.Amber, point(i - 1), point(i), 2.dp.toPx())
        }
    }
}
