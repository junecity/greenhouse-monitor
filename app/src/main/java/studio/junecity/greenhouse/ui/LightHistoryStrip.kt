package studio.junecity.greenhouse.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import studio.junecity.greenhouse.data.*
import studio.junecity.greenhouse.model.*
import studio.junecity.greenhouse.sensors.ShellyModel
import kotlin.math.abs

@Composable internal fun LightHistoryStrip(state: GreenhouseUiState, series: List<TrendSensor>, start: Long, end: Long,
                                           selectTime: (Long) -> Unit) {
    val sources = remember(series) { series.filter { s -> s.timeline.rows.any { it.lightLevel != null } } }
    if (sources.isEmpty()) {
        if (state.sensors.any { it.greenhouseId == state.preferences.activeGreenhouseId && it.sku == ShellyModel.Display.sku })
            Text("No recorded light in this period", color = DashboardTokens.Muted, fontSize = 12.sp)
        return
    }
    var ref by remember(state.preferences.activeGreenhouseId) { mutableStateOf<String?>(null) }
    val source = sources.find { it.id == ref } ?: sources.first()
    val spans = remember(source) { LightHistory.spans(source.timeline.rows) }
    val zone = state.weather.zone
    val events = remember(spans, zone) { LightHistory.changes(spans, zone) }
    var details by remember(source.id, start / DAY_MS) { mutableStateOf<LightChange?>(null) }
    var menu by remember { mutableStateOf(false) }
    fun open(event: LightChange) { selectTime(event.time); details = event }
    val span = (end - start).coerceAtLeast(1)
    val dark = Color(0xFF59627F)
    val twilight = Color(0xFFC59CD9)
    val bright = DashboardTokens.Amber
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box {
            TextButton(onClick = { menu = true }, contentPadding = PaddingValues(2.dp), modifier = Modifier.width(43.dp)) {
                Text("Light ▾", fontSize = 11.sp, color = bright)
            }
            DropdownMenu(menu, { menu = false }, modifier = Modifier.heightIn(max = 240.dp)) {
                sources.forEach { sensor -> DropdownMenuItem(text = { Text(sensor.name + if (sensor.id == source.id) " ✓" else "") },
                    onClick = { ref = sensor.id; menu = false }) }
                HorizontalDivider()
                if (events.isEmpty()) DropdownMenuItem(text = { Text("Waiting for a sustained change") }, enabled = false, onClick = {})
                events.asReversed().forEach { event ->
                    DropdownMenuItem(text = { Text("${if (event.brightening) "Morning light" else "Evening dimming"} · ${chartTime(event.time, "MMM d, h:mm a")}") },
                        onClick = { menu = false; open(event) })
                }
            }
        }
        Column(Modifier.weight(1f)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf("Dark" to dark, "Twilight" to twilight, "Bright" to bright).forEach { (label, color) ->
                    Text(label, color = color, fontSize = 11.sp)
                }
            }
            Canvas(Modifier.fillMaxWidth().height(18.dp).semantics {
                contentDescription = "Light history for ${source.name}"
                onClick(label = "Choose light transition") { menu = true; true }
            }.pointerInput(spans, events, start, end) {
                detectTapGestures { pos ->
                    val time = start + (pos.x / size.width * span).toLong()
                    val hit = spans.find { time in it.start..it.last }
                    val nearest = events.minByOrNull { abs(it.time - time) }
                    if (nearest != null && abs(nearest.time - time) <= span * 14.dp.toPx() / size.width)
                        open(nearest)
                    else if (hit != null) events.find { it.time == hit.start }?.let(::open) ?: selectTime(time)
                    else selectTime(time)
                }
            }) {
                fun x(time: Long) = (size.width * ((time - start).toDouble() / span)).toFloat()
                drawRect(DashboardTokens.Background, Offset(0f, size.height * .24f), Size(size.width, size.height * .52f))
                for (run in spans) {
                    val color = when (run.level) { "Bright" -> bright; "Twilight" -> twilight; else -> dark }
                    drawRect(color, Offset(x(run.start), size.height * .24f),
                        Size((x(run.last) - x(run.start)).coerceAtLeast(1f), size.height * .52f))
                }
                events.forEach { drawCircle(Color.White, 2.5.dp.toPx(), Offset(x(it.time), size.height / 2)) }
            }
        }
    }
    details?.let { event ->
        // A full hour can extend beyond a chart edge. Include available raw readings for that house.
        val timelines = remember(series, state.history) {
            val groups = state.history.groupBy { it.sensorId }
            series.map { s -> s.copy(timeline = SensorTimeline(groups[s.id].orEmpty() + s.timeline.rows)) }
        }
        val allEvents = remember(timelines, source.id, zone) {
            LightHistory.changes(LightHistory.spans(timelines.first { it.id == source.id }.timeline.rows), zone)
        }
        LightResponseDialog(event, source.name, timelines, allEvents, state) { details = null }
    }
}

@Composable private fun LightResponseDialog(event: LightChange, reference: String, series: List<TrendSensor>,
                                           events: List<LightChange>, state: GreenhouseUiState, close: () -> Unit) {
    var cursor by remember(event) { mutableStateOf<Long?>(null) }
    val limit = state.preferences.notifications.lowTemperatureF
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize().padding(10.dp), color = DashboardTokens.Panel,
            shape = RoundedCornerShape(18.dp)) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(if (event.brightening) "Morning warm-up" else "Evening cooling", color = Color.White, fontSize = 19.sp)
                        Text("$reference · ${chartTime(event.time, "MMM d, h:mm a")}", color = DashboardTokens.Muted, fontSize = 12.sp)
                    }
                    InsightButton("Close", close)
                }
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SensorTrendChart(series, Metric.Temperature, event.time, event.time + 60 * LIGHT_MINUTE, cursor,
                        { cursor = it }, Modifier.weight(1.25f).fillMaxHeight())
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("First hour after the light changed", color = Color.White, fontSize = 14.sp)
                        series.forEach { sensor ->
                            val response = remember(sensor, event, limit) { LightHistory.response(sensor.timeline, event, limit) }
                            Text(sensor.name, color = sensor.color, fontSize = 14.sp)
                            Text(response?.let { r ->
                                "%+.1f°F in %d min".format(r.deltaF, r.minutes) + (r.twoDegreesAt?.let { " · ${if (event.brightening) "+2" else "−2"}°F at ${it} min" } ?: "")
                            } ?: "Waiting for continuous readings", color = Color.White, fontSize = 13.sp)
                            val weather = state.weather
                            val comparison = remember(sensor, events, event, weather, limit, state.preferences.zipCode) {
                                if (weather.zipCode != state.preferences.zipCode) null else
                                    LightHistory.compare(events, event, sensor.timeline, weather.zone, limit) { time ->
                                        val hour = time / 3_600_000L * 3_600_000L
                                        weather.stationHours[hour]?.temperatureF
                                    }
                            }
                            comparison?.let { c ->
                                Text(if (event.brightening) {
                                    val times = c.twoDegreeTimes
                                    if (times.isEmpty()) "No 2°F rise in ${c.nights} similar mornings"
                                    else "2°F rise in ${times.size} of ${c.nights} similar mornings · typically ${(times[(times.size - 1) / 2] + times[times.size / 2]) / 2} min"
                                }
                                    else "Below ${formatTemp(limit)} on ${c.crossings} of ${c.nights} similar evenings",
                                    color = DashboardTokens.Muted, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}
