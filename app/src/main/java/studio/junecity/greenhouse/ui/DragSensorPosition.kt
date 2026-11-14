package studio.junecity.greenhouse.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import studio.junecity.greenhouse.model.*
import studio.junecity.greenhouse.data.ZoneClimate

@Composable
internal fun SensorPositionDialog(house: GreenhouseConfig, sensor: GoveeSensor, close: () -> Unit,
                                  save: (SensorPosition) -> Unit) {
    val initial = house.sensorPositions[sensor.id] ?: house.coercePoint(house.planLengthFeet / 2, house.planWidthFeet / 2)
    var drop by remember(house.id, sensor.id) { mutableStateOf(initial) }
    var level by remember(house.id, sensor.id) { mutableStateOf(initial.level) }
    var distance by remember(house.id, sensor.id) { mutableStateOf("") }
    var advanced by remember { mutableStateOf(false) }
    val reference = remember(house, drop) { house.placementReference(drop) }
    val point = if (distance.isBlank()) drop else distance.toDoubleOrNull()?.let { feet ->
        reference?.let { house.refineDrop(drop, it, feet) }
    }
    val peak = if (house.hasStem && (point ?: drop).yFeet > house.widthFeet)
        house.attachedHeightFeet ?: house.heightFeet else house.heightFeet
    val measured = initial.heightFeet.takeIf { level == null }
    val heightFits = measured == null || peak == null || measured <= peak
    val placed = point?.copy(heightFeet = measured, level = level)
    if (advanced) {
        val draft = house.copy(sensorPositions = house.sensorPositions + (sensor.id to (placed ?: drop)))
        ExactPositionDialog(draft, sensor, { advanced = false }, save)
        return
    }
    AlertDialog(onDismissRequest = close, containerColor = DashboardTokens.PanelRaised,
        title = { Text(sensor.name, color = Color.White) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Drag the blue sensor to its spot in ${house.name}.", color = Color.White)
                PlacementMap(house, point ?: drop, reference) { next ->
                    drop = next
                    distance = ""
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (option in MountLevel.entries) {
                        FilterChip(selected = level == option, onClick = { level = option },
                            label = { Text(option.name) }, modifier = Modifier.weight(1f),
                            colors = FilterChipDefaults.filterChipColors(labelColor = Color.White,
                                selectedContainerColor = DashboardTokens.Amber, selectedLabelColor = DashboardTokens.Background))
                    }
                }
                if (level != null && placed != null) {
                    val approximate = ZoneClimate.mountingHeight(house, placed)
                    approximate?.let { Text("About ${planNumber(it)} ft above the floor", color = DashboardTokens.Muted) }
                }
                if (measured != null) Text("Measured height · ${planNumber(measured)} ft", color = DashboardTokens.Muted)
                val circular = house.shape == GreenhouseShape.Circle
                if (reference != null) {
                    val label = if (circular) "Distance from center (ft)" else "Distance from nearest corner (ft)"
                    OutlinedTextField(distance, { distance = it.replace(',', '.') }, label = { Text(label) },
                        placeholder = { Text(planNumber(drop.floorDistance(reference))) },
                        modifier = Modifier.fillMaxWidth(), singleLine = true, colors = darkFieldColors(),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal))
                    Text(if (point == null) "Drag closer to the measured spot or change the distance."
                        else if (!heightFits) "The saved height exceeds this roof. Choose a level or edit the exact height."
                        else "Optional · Measure along the floor from the amber dot.",
                        color = if (point == null || !heightFits) DashboardTokens.Amber else DashboardTokens.Muted)
                }
                TextButton(onClick = { advanced = true }) { Text("Exact measurements", color = DashboardTokens.Amber) }
            }
        },
        confirmButton = { TextButton(enabled = placed != null && heightFits, onClick = { placed?.let(save) }) {
            Text("Save position", color = if (placed != null && heightFits) DashboardTokens.Amber else DashboardTokens.Muted)
        } },
        dismissButton = { TextButton(onClick = close) { Text("Cancel", color = DashboardTokens.Muted) } })
}

private class PlanViewport(house: GreenhouseConfig, width: Float, height: Float, margin: Float) {
    private val scale = minOf((width - margin * 2) / house.planLengthFeet,
        (height - margin * 2) / house.planWidthFeet).coerceAtLeast(.001)
    private val left = (width - house.planLengthFeet * scale) / 2
    private val top = (height - house.planWidthFeet * scale) / 2
    fun pixels(point: SensorPosition) = Offset((left + point.xFeet * scale).toFloat(), (top + point.yFeet * scale).toFloat())
    fun feet(point: Offset) = SensorPosition((point.x - left) / scale, (point.y - top) / scale)
}

@Composable
private fun PlacementMap(house: GreenhouseConfig, point: SensorPosition, reference: SensorPosition?,
                         onPlace: (SensorPosition) -> Unit) {
    val place by rememberUpdatedState(onPlace)
    val outline = remember(house) { house.outline().map { SensorPosition(it.x * house.planLengthFeet, it.y * house.planWidthFeet) } }
    Canvas(Modifier.fillMaxWidth().height(172.dp).background(DashboardTokens.Background, RoundedCornerShape(12.dp))
        .semantics { contentDescription = "Sensor placement map" }
        .pointerInput(house) {
            val view = PlanViewport(house, size.width.toFloat(), size.height.toFloat(), 16.dp.toPx())
            awaitEachGesture {
                val down = awaitFirstDown()
                fun move(at: Offset) {
                    val next = view.feet(at)
                    if (house.containsPoint(next.xFeet, next.yFeet)) place(next)
                }
                down.consume()
                move(down.position)
                do {
                    val finger = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                    if (finger.isConsumed) break
                    move(finger.position)
                    finger.consume()
                } while (finger.pressed)
            }
        }) {
        val view = PlanViewport(house, size.width, size.height, 16.dp.toPx())
        val path = Path().apply {
            outline.forEachIndexed { index, p ->
                val at = view.pixels(p)
                if (index == 0) moveTo(at.x, at.y) else lineTo(at.x, at.y)
            }
            close()
        }
        drawPath(path, DashboardTokens.Amber.copy(alpha = .12f))
        drawPath(path, DashboardTokens.Amber.copy(alpha = .75f), style = Stroke(1.5.dp.toPx()))
        val marker = view.pixels(point)
        reference?.let {
            val anchor = view.pixels(it)
            drawLine(DashboardTokens.Amber, anchor, marker, 1.dp.toPx())
            drawCircle(DashboardTokens.Amber, 5.dp.toPx(), anchor)
        }
        drawCircle(Color.White, 8.dp.toPx(), marker)
        drawCircle(DashboardTokens.Blue, 6.dp.toPx(), marker)
    }
}
