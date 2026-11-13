package studio.junecity.greenhouse.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
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
    if (mode == Insight.Compare || mode == Insight.Replay) {
        Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                .widthIn(max = 920.dp).fillMaxWidth().fillMaxHeight(.94f), shape = RoundedCornerShape(20.dp),
                color = DashboardTokens.Panel, contentColor = Color.White, border = BorderStroke(1.dp, DashboardTokens.PanelEdge)) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        DashboardIconView(if (mode == Insight.Replay) DashboardIcon.Play else DashboardIcon.ChartBars,
                            DashboardTokens.Amber, Modifier.size(26.dp), null)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(mode.title, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                            Text(g.name, color = DashboardTokens.Muted, fontSize = 14.sp)
                        }
                        InsightButton("Close", close)
                    }
                    HorizontalDivider(Modifier.padding(vertical = 6.dp), color = DashboardTokens.Divider)
                    BoxWithConstraints(Modifier.fillMaxSize()) {
                        val height = maxHeight.coerceAtLeast(185.dp)
                        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                            if (mode == Insight.Compare) CompareSensors(sensors, timelines, night, now, height)
                            else Replay(sensors, timelines, g, night, state.metric, d, t, height)
                        }
                    }
                }
            }
        }
        return
    }
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
                        Insight.Compare -> Unit
                        Insight.Replay -> Unit
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

@Composable private fun Replay(sensors: List<GoveeSensor>, timelines: Map<String, SensorTimeline>,
    g: GreenhouseConfig, night: NightWindow, metric: Metric, d: (Float)->Dp, t:(Float)->TextUnit, height: Dp) {
    val series = remember(sensors.map { it.id to it.name }, timelines) {
        sensors.sortedBy { it.id }.mapIndexedNotNull { i, sensor ->
            timelines[sensor.id]?.let { TrendSensor(sensor.id, sensor.name, it, trendPalette[i % trendPalette.size]) }
        }
    }
    val scale = remember(timelines, g, night.start, night.end / 60_000L, metric) {
        val values = timelines.filterKeys { it in g.sensorPositions }.values.flatMap { series ->
            series.rows.filter { it.timestampEpochMs in (night.start - READING_LIFETIME)..night.end }
                .map { historyMetric(it, metric) }.filter { it.isFinite() }
        }
        values.minOrNull()?.let { it..values.max() }
    }
    var time by remember(g.id, night.start) { mutableStateOf<Long?>(null) }
    var playing by remember(g.id, night.start) { mutableStateOf(false) }
    val at = (time ?: night.end).coerceIn(night.start, night.end)
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _,event -> if (event == Lifecycle.Event.ON_PAUSE) playing = false }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    val latestEnd by rememberUpdatedState(night.end)
    LaunchedEffect(playing) {
        if (!playing) return@LaunchedEffect
        val initial = time ?: night.start
        val started = android.os.SystemClock.elapsedRealtime()
        val span = (latestEnd - night.start).coerceAtLeast(1)
        while (playing) {
            val elapsed = android.os.SystemClock.elapsedRealtime() - started
            val target = initial + (span * (elapsed / 24000.0)).toLong()
            if (target >= latestEnd) { time = null; playing = false; break }
            time = target
            kotlinx.coroutines.delay(120)
        }
    }
    val canPlay = remember(series,night.start,night.end) {
        night.end > night.start && series.any { it.timeline.rows.any { r -> r.timestampEpochMs in (night.start-READING_LIFETIME)..night.end } }
    }
    val readings = sensors.mapNotNull { sensor ->
        val row = timelines[sensor.id]?.at(at) ?: return@mapNotNull null
        if (sensor.id !in g.sensorPositions) return@mapNotNull null
        sensor.copy(temperatureF=row.temperatureF, humidityPercent=row.humidityPercent, online=true, updatedAtEpochMs=row.timestampEpochMs)
    }
    val seek: (Long)->Unit = { playing=false; time=it }
    Row(Modifier.fillMaxWidth().height((height - 54.dp).coerceAtLeast(132.dp)),
        horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        Box(Modifier.weight(.34f).fillMaxHeight(),contentAlignment=Alignment.Center) {
            if (readings.isEmpty()) Text("No mapped readings", fontSize=14.sp, color=DashboardTokens.Muted)
            else HeatMap(SpatialInterpolator.samples(readings,g,metric,at),metric,g,readings,d,t,
                { _,_,_ -> }, Modifier.fillMaxSize(),editable=false,scale=scale)
        }
        Column(Modifier.weight(.66f).fillMaxHeight()) {
            Text(metric.displayName(), fontSize=16.sp,fontWeight=FontWeight.Bold)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                for (sensor in series) {
                    val row = sensor.timeline.at(at)
                    Text("${sensor.name}  ${formatMetric(row?.let { historyMetric(it,metric) },metric)}",
                        color=sensor.color,fontSize=12.sp, maxLines=1)
                }
            }
            SensorTrendChart(series,metric,night.start,night.end,at,seek,Modifier.fillMaxWidth().weight(1f))
        }
    }
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        Button(onClick={
            if (!playing && at >= night.end) time=night.start
            playing=!playing
        },enabled=canPlay,
            colors=ButtonDefaults.buttonColors(containerColor=DashboardTokens.Amber,contentColor=DashboardTokens.Background)) {
            Text(if(playing) "Pause" else "Play",fontWeight=FontWeight.Bold)
        }
        Slider(((at-night.start).toDouble()/(night.end-night.start).coerceAtLeast(1)).toFloat(),
            { seek(night.start+((night.end-night.start)*it.toDouble()).toLong()) }, modifier=Modifier.weight(1f),
            colors=SliderDefaults.colors(thumbColor=DashboardTokens.Amber,activeTrackColor=DashboardTokens.Amber))
        Text(clock(at),color=DashboardTokens.White,fontSize=14.sp,fontWeight=FontWeight.Bold)
    }
    var details by remember { mutableStateOf(false) }
    if (readings.isNotEmpty()) {
        InsightButton(if (details) "Hide readings" else "Sensor readings (${readings.size})") { details=!details }
        if (details) readings.forEach { Text("${label(it)} · ${clock(it.updatedAtEpochMs)}", color=DashboardTokens.Muted) }
    }
}

@Composable private fun CompareSensors(sensors: List<GoveeSensor>, timelines: Map<String, SensorTimeline>, night: NightWindow, now: Long, height: Dp) {
    if (sensors.size < 2) { Text("Add two sensors to compare readings."); return }
    val ids = sensors.map { it.id }
    var first by remember(ids) { mutableStateOf(ids[0]) }
    var second by remember(ids) { mutableStateOf(ids[1]) }
    val a = sensors.first { it.id == first }
    val b = sensors.first { it.id == second }
    var metric by remember { mutableStateOf(Metric.Temperature) }
    var cursor by remember(first,second,night.start) { mutableStateOf<Long?>(null) }
    val series = remember(timelines, a.id, b.id) {
        listOf(a,b).mapIndexed { i,s -> TrendSensor(s.id,s.name,timelines[s.id] ?: SensorTimeline(emptyList()),trendPalette[i]) }
    }
    fun value(sensor: GoveeSensor): Double? {
        val at = cursor
        if (at != null) return timelines[sensor.id]?.at(at)?.let { historyMetric(it,metric) }
        if (!sensor.isFresh(now) || sensor.updatedAtEpochMs > now) return null
        return if(metric==Metric.Temperature) sensor.temperatureF else sensor.humidityPercent
    }
    val left = value(a)
    val right = value(b)
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        listOf(a,b).forEachIndexed { i,sensor ->
            val other = if(i==0) second else first
            Box(Modifier.weight(1f)) {
                Picker(sensor,sensors.filter { it.id != other },series[i].color,formatMetric(if(i==0) left else right,metric)) {
                    if(i==0) first=it else second=it
                }
            }
        }
    }
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        for (option in listOf(Metric.Temperature,Metric.Humidity)) {
            FilterChip(selected=metric==option,onClick={metric=option},label={Text(option.displayName())},
                colors=FilterChipDefaults.filterChipColors(labelColor=Color.White,
                    selectedContainerColor=DashboardTokens.Amber,selectedLabelColor=DashboardTokens.Background))
        }
        Spacer(Modifier.weight(1f))
        val diff = if(left==null || right==null) "—" else if(metric==Metric.Humidity)
            "%.0f percentage points".format(abs(left-right)) else formatTemp(abs(left-right))
        Text("${if(cursor==null) "Current difference" else "Difference"} · $diff",
            color=Color.White,fontSize=15.sp,fontWeight=FontWeight.Bold)
    }
    SensorTrendChart(series,metric,night.start,night.end,cursor,{ cursor=it },Modifier.fillMaxWidth().height((height-124.dp).coerceAtLeast(96.dp)))
    val peak = remember(series,metric,night.start,night.end/60_000) {
        val times = series.flatMap { s -> s.timeline.rows.map { it.timestampEpochMs } }.filter { it in night.start..night.end } + night.start
        times.mapNotNull { time ->
            val l = series[0].timeline.at(time) ?: return@mapNotNull null
            val r = series[1].timeline.at(time) ?: return@mapNotNull null
            time to abs(historyMetric(l,metric)-historyMetric(r,metric))
        }.maxByOrNull { it.second }
    }
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
        Text(cursor?.let { clock(it) } ?: peak?.let {
            val delta = if(metric==Metric.Humidity) "%.0f points".format(it.second) else formatTemp(it.second)
            "Overnight peak · $delta at ${clock(it.first)}"
        } ?: "No overlapping overnight readings",modifier=Modifier.weight(1f),color=DashboardTokens.Muted,fontSize=13.sp)
        if(cursor!=null) InsightButton("Latest"){cursor=null}
        else Text("Drag to inspect",color=DashboardTokens.Muted,fontSize=12.sp)
    }
}

@Composable private fun Picker(selected: GoveeSensor, options: List<GoveeSensor>, color: Color, value: String, select: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton({ open=true },modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(12.dp),
            contentPadding=PaddingValues(horizontal=10.dp,vertical=4.dp),border=BorderStroke(1.dp,DashboardTokens.PanelEdge)) {
            Text(label(selected),color=color,modifier=Modifier.weight(1f),maxLines=1,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            Text("$value  ▾",color=Color.White,fontWeight=FontWeight.Bold)
        }
        DropdownMenu(expanded=open,onDismissRequest={open=false}) {
            options.forEach { sensor -> DropdownMenuItem(text={Text(label(sensor))},onClick={select(sensor.id);open=false}) }
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
