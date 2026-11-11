package studio.junecity.greenhouse.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import studio.junecity.greenhouse.model.*
import kotlin.math.min
import kotlin.math.roundToInt

internal fun GreenhouseConfig.outline(): List<NormalizedPosition> = when(shape) {
    GreenhouseShape.Drawn -> vertices
    GreenhouseShape.Rectangle -> listOf(0.0 to 0.0,1.0 to 0.0,1.0 to 1.0,0.0 to 1.0).map { NormalizedPosition(it.first,it.second) }
    GreenhouseShape.TShape -> {
        val bar = widthFeet/planWidthFeet
        val left = crossbarLeftFeet/planLengthFeet
        val stem = stemLeftFeet/planLengthFeet
        listOf(left to 0.0,1-left to 0.0,1-left to bar,1-stem to bar,1-stem to 1.0,stem to 1.0,stem to bar,left to bar)
            .map { NormalizedPosition(it.first,it.second) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FloorPlanEditor(saved: GreenhouseConfig, sensors: List<GoveeSensor>, onSave: (GreenhouseConfig)->Unit) {
    var draft by remember(saved.id) { mutableStateOf(saved) }
    var drawing by remember { mutableStateOf(false) }
    var dimensions by remember { mutableStateOf(false) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    var stroke by remember { mutableStateOf(emptyList<NormalizedPosition>()) }
    var feedback by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<String?>(null) }
    fun changeShape(next: GreenhouseConfig) {
        draft = next.copy(sensorPositions=draft.sensorPositions.mapValues { next.reproject(it.value,draft) })
    }
    val scale = min(size.width/(draft.planLengthFeet*1.35),size.height/(draft.planWidthFeet*1.35)).coerceAtLeast(.01)
    val left = (size.width-draft.planLengthFeet*scale)/2
    val top = (size.height-draft.planWidthFeet*scale)/2
    fun feet(at: Offset) = NormalizedPosition((at.x-left)/scale,(at.y-top)/scale)
    fun pixel(x: Double,y: Double) = Offset((left+x*scale).toFloat(),(top+y*scale).toFloat())
    fun position(id: String) = draft.sensorPositions[id] ?: draft.coercePoint(draft.planLengthFeet/2,draft.planWidthFeet/2)
    fun place(id: String,at: Offset) {
        val p = feet(at)
        draft = draft.copy(sensorPositions=draft.sensorPositions+(id to draft.coercePoint(p.x,p.y).copy(heightFeet=position(id).heightFeet)))
    }
    val teal = DashboardTokens.Blue
    Column(Modifier.fillMaxWidth(), verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text(if(drawing) "Drag diagonally for a rectangle, or trace the walls and lift to close." else
            if(sensors.isEmpty()) "Draw the outline of your greenhouse." else "Drag a sensor, or select its name and tap the plan.", color=DashboardTokens.Muted)
        Canvas(Modifier.fillMaxWidth().height(220.dp).background(DashboardTokens.Background,RoundedCornerShape(12.dp))
            .onSizeChanged { size=it }.semantics { contentDescription="Greenhouse floor plan" }
            .pointerInput(drawing,size,sensors.map{it.id},draft.planLengthFeet,draft.planWidthFeet) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val target = sensors.minByOrNull { s ->
                        val p=position(s.id)
                        (pixel(p.xFeet,p.yFeet)-down.position).getDistance()
                    }?.id?.takeIf { id ->
                        val p=position(id)
                        (pixel(p.xFeet,p.yFeet)-down.position).getDistance() <= 26.dp.toPx()
                    } ?: selected
                    if(!drawing && target==null) return@awaitEachGesture
                    down.consume()
                    feedback=""
                    if(drawing) stroke=listOf(feet(down.position)) else selected=target
                    do {
                        val event=awaitPointerEvent()
                        val finger=event.changes.find { it.id==down.id } ?: break
                        if(finger.isConsumed || event.changes.size>1) { stroke=emptyList();break }
                        finger.consume()
                        val point=feet(finger.position)
                        if(drawing && stroke.size<1024 && stroke.lastOrNull()!=point) stroke=stroke+point
                        if(!drawing && target!=null) place(target,finger.position)
                        if(!finger.pressed && drawing){
                            val next=DrawnPlan.fromStroke(stroke,draft,(8.dp.toPx()/scale).coerceAtLeast(.25))
                            if(next==null) feedback="Draw a larger outline without crossing walls."
                            else { changeShape(next);drawing=false }
                            stroke=emptyList()
                        }
                    } while(finger.pressed)
                }
            }) {
                fun trace(points:List<Offset>,close:Boolean):Path = Path().apply {
                    points.forEachIndexed { i,p->if(i==0)moveTo(p.x,p.y)else lineTo(p.x,p.y) }
                    if(close)close()
                }
                val walls=trace(draft.outline().map { pixel(it.x*draft.planLengthFeet,it.y*draft.planWidthFeet) },true)
                drawPath(walls,DashboardTokens.Amber.copy(alpha=.13f))
                drawPath(walls,DashboardTokens.Amber,style=Stroke(2.dp.toPx()))
                if(stroke.isNotEmpty()) drawPath(trace(stroke.map{pixel(it.x,it.y)},false),Color.White,style=Stroke(2.dp.toPx()))
                if(!drawing) sensors.forEach { s ->
                    val p=position(s.id)
                    val center=pixel(p.xFeet,p.yFeet)
                    drawCircle(if(selected==s.id) Color.White else teal,10.dp.toPx(),center)
                    drawCircle(DashboardTokens.Background,4.dp.toPx(),center)
                }
        }
        Text(feedback.ifEmpty { "${draft.planLengthFeet.roundToInt()} × ${draft.planWidthFeet.roundToInt()} ft  ·  ${draft.areaSquareFeet.roundToInt()} sq ft" },
            color=if(feedback.isEmpty()) DashboardTokens.Muted else DashboardTokens.Amber)
        FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            EditorAction(if(drawing)"Cancel drawing" else "Draw outline") { drawing=!drawing;stroke=emptyList() }
            EditorAction("Dimensions") { dimensions=!dimensions }
        }
        if(dimensions) {
            fun dimensionChange(value:String, apply:(Double)->GreenhouseConfig) {
                value.toDoubleOrNull()?.takeIf { it in 1.0..240.0 }?.let { changeShape(apply(it)) }
            }
            PlanField("Greenhouse name",draft.name){draft=draft.copy(name=it)}
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                PlanField("Length (ft)",draft.lengthFeet.roundToInt().toString(),Modifier.weight(1f)){ dimensionChange(it){v->draft.copy(lengthFeet=v)} }
                PlanField("Width (ft)",draft.widthFeet.roundToInt().toString(),Modifier.weight(1f)){ dimensionChange(it){v->draft.copy(widthFeet=v)} }
            }
            EditorAction(if(draft.shape==GreenhouseShape.TShape)"Use rectangle" else "Use T-shaped plan"){
                changeShape(draft.copy(shape=if(draft.shape==GreenhouseShape.TShape)GreenhouseShape.Rectangle else GreenhouseShape.TShape))
            }
            if(draft.shape==GreenhouseShape.TShape)Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                PlanField("Stem length (ft)",draft.attachedLengthFeet.roundToInt().toString(),Modifier.weight(1f)){dimensionChange(it){v->draft.copy(attachedLengthFeet=v)}}
                PlanField("Stem width (ft)",draft.attachedWidthFeet.roundToInt().toString(),Modifier.weight(1f)){dimensionChange(it){v->draft.copy(attachedWidthFeet=v)}}
            }
        }
        if(!drawing && sensors.isNotEmpty()) FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
            sensors.forEach { s -> FilterChip(selected=selected==s.id,onClick={selected=s.id},label={Text(s.name)},colors=FilterChipDefaults.filterChipColors(labelColor=Color.White,selectedContainerColor=DashboardTokens.Amber)) }
        }
        Button(onClick={onSave(draft.copy(name=draft.name.ifBlank{saved.name}))},enabled=!drawing,colors=ButtonDefaults.buttonColors(containerColor=DashboardTokens.Amber,contentColor=DashboardTokens.Background)){Text("Save layout")}
    }
}

@Composable private fun EditorAction(label:String,onClick:()->Unit){
    OutlinedButton(onClick=onClick){Text(label,color=DashboardTokens.Amber)}
}

@Composable private fun PlanField(label:String,value:String,modifier:Modifier=Modifier,onChange:(String)->Unit){
    var text by remember(value){mutableStateOf(value)}
    OutlinedTextField(value=text,onValueChange={text=it;onChange(it)},label={Text(label)},modifier=modifier,singleLine=true,
        colors=OutlinedTextFieldDefaults.colors(focusedTextColor=Color.White,unfocusedTextColor=Color.White,focusedBorderColor=DashboardTokens.Amber))
}
