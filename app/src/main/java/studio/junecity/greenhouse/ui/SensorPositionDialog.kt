package studio.junecity.greenhouse.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import studio.junecity.greenhouse.model.*

@Composable internal fun MeasurementField(label: String, value: String, change: (String) -> Unit) {
    OutlinedTextField(value, { change(it.replace(',', '.')) }, modifier=Modifier.fillMaxWidth(),
        label={Text(label)}, singleLine=true, keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),
        colors=darkFieldColors())
}

@Composable internal fun SensorPositionDialog(house: GreenhouseConfig, sensor: GoveeSensor, close: ()->Unit,
                                             save: (SensorPosition)->Unit) {
    val initial = house.sensorPositions[sensor.id] ?: house.coercePoint(house.planLengthFeet/2,house.planWidthFeet/2)
    var stem by remember { mutableStateOf(house.hasStem && initial.yFeet > house.widthFeet) }
    val section = house.planSection(stem)
    var corners by remember { mutableStateOf(false) }
    var showDistances by remember { mutableStateOf(false) }
    var mount by remember { mutableStateOf(initial.heightFeet?.let(::planNumber).orEmpty()) }
    val rectangular = house.hasStem || house.shape == GreenhouseShape.Rectangle
    val leftCorner=SensorPosition(section.left,section.top)
    val rightCorner=leftCorner.copy(xFeet=section.left+section.width)
    val startX = (initial.xFeet-section.left).coerceIn(0.0,section.width)
    val startY = (initial.yFeet-section.top).coerceIn(0.0,section.depth)
    var x by remember(section) { mutableStateOf(planNumber(startX)) }
    var y by remember(section) { mutableStateOf(planNumber(startY)) }
    var dl by remember(section) { mutableStateOf("") }; var dr by remember(section) { mutableStateOf("") }
    val horizontal=if (x == planNumber(startX)) startX else x.replace(',','.').toDoubleOrNull(); val vertical=if (y == planNumber(startY)) startY else y.replace(',','.').toDoubleOrNull()
    val l=dl.replace(',','.').toDoubleOrNull(); val r=dr.replace(',','.').toDoubleOrNull()
    val point=if(corners) {
        if(l==null || r==null) null else section.fromTopCorners(l,r)
    } else if(horizontal!=null && vertical!=null && horizontal in 0.0..section.width && vertical in 0.0..section.depth)
        SensorPosition(section.left+horizontal,section.top+vertical) else null
    val z=if (mount == initial.heightFeet?.let(::planNumber)) initial.heightFeet else mount.replace(',','.').toDoubleOrNull()
    val inside=point!=null && house.containsPoint(point.xFeet,point.yFeet)
    val peak=if (stem) house.attachedHeightFeet ?: house.heightFeet else house.heightFeet
    val valid=inside && (mount.isBlank() || z!=null && z in 0.0..(peak ?: 480.0))
    AlertDialog(onDismissRequest=close,containerColor=DashboardTokens.PanelRaised,title={Text(sensor.name,color=Color.White)},text={
        Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            MeasurementField("Height above ground (ft)",mount){mount=it}
            if(house.hasStem) Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                for ((label,value) in listOf("Crossbar" to false,"Stem" to true)) {
                    FilterChip(selected=stem==value,onClick={stem=value;corners=false},label={Text(label,color=if(stem==value) Color.Black else Color.White)},
                        colors=FilterChipDefaults.filterChipColors(selectedContainerColor=DashboardTokens.Amber))
                }
            }
            Text(if (corners) "Measure across the floor from the two top corners of this section to the point directly below the sensor." else "Looking down at the plan, measure from the left and top edges to the point directly below the sensor.",color=DashboardTokens.Muted)
            if(corners) {
                MeasurementField("From top-left corner (ft)",dl){dl=it}
                MeasurementField("From top-right corner (ft)",dr){dr=it}
            } else {
                MeasurementField("From left edge (ft)",x){x=it}
                MeasurementField("From top edge (ft)",y){y=it}
            }
            if(rectangular) TextButton(onClick={
                if(!corners && point!=null) { dl=point.floorDistance(leftCorner).toString();dr=point.floorDistance(rightCorner).toString() }
                if(corners && point!=null) { x=planNumber(point.xFeet-section.left);y=planNumber(point.yFeet-section.top) }
                corners=!corners
            }) { Text(if(corners)"Use edge distances" else "Use two corner distances",color=DashboardTokens.Amber) }
            if(!inside) Text("These distances must meet inside the floor plan.",color=DashboardTokens.Amber)
            else if(!valid) Text("Check the mounting height and the section’s peak height.",color=DashboardTokens.Amber)
            if(inside && house.shape!=GreenhouseShape.Circle) {
                TextButton(onClick={showDistances=!showDistances}) { Text(if(showDistances)"Hide corner distances" else "Show all corner distances",color=DashboardTokens.Amber) }
                if(showDistances) {
                    CornerReferencePlan(house, requireNotNull(point))
                    Text("Distance across the floor from each numbered corner to the sensor.",color=DashboardTokens.Muted)
                    house.floorCorners().forEachIndexed { index, corner ->
                        Text("Corner ${index + 1}   ·   ${planNumber(requireNotNull(point).floorDistance(corner))} ft",color=Color.White)
                    }
                }
            }
        }
    },confirmButton={TextButton(enabled=valid,onClick={save(requireNotNull(point).copy(heightFeet=z))}){Text("Save position",color=DashboardTokens.Amber)}},
        dismissButton={TextButton(onClick=close){Text("Cancel",color=DashboardTokens.Muted)}})
}


@Composable
private fun CornerReferencePlan(house: GreenhouseConfig, sensor: SensorPosition) {
    val corners = remember(house) { house.floorCorners() }
    if (corners.size < 3) return
    Canvas(Modifier.fillMaxWidth().height(152.dp).semantics {
        contentDescription = "Numbered greenhouse corners, viewed from above. The sensor is marked in blue."
    }) {
        val margin = 13.dp.toPx()
        val scale = minOf((size.width - margin * 2) / house.planLengthFeet,
            (size.height - margin * 2) / house.planWidthFeet)
        if (scale <= 0) return@Canvas
        val left = (size.width - house.planLengthFeet * scale) / 2
        val top = (size.height - house.planWidthFeet * scale) / 2
        fun project(p: SensorPosition) = Offset((left + p.xFeet * scale).toFloat(), (top + p.yFeet * scale).toFloat())
        val points = corners.map(::project)
        val outline = Path().apply {
            moveTo(points.first().x, points.first().y)
            points.drop(1).forEach { lineTo(it.x, it.y) }
            close()
        }
        drawPath(outline, DashboardTokens.Amber.copy(alpha = .12f))
        drawPath(outline, DashboardTokens.Amber, style = Stroke(1.5.dp.toPx()))
        val text = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.White.toArgb()
            textAlign = android.graphics.Paint.Align.CENTER
            textSize = 10.dp.toPx()
        }
        points.forEachIndexed { index, point ->
            drawCircle(DashboardTokens.PanelRaised, 8.dp.toPx(), point)
            drawContext.canvas.nativeCanvas.drawText((index + 1).toString(), point.x,
                point.y - (text.ascent() + text.descent()) / 2, text)
        }
        drawCircle(Color(0xFF56BDF4), 3.5.dp.toPx(), project(sensor))
    }
}
