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
import kotlin.math.ceil
import kotlin.math.floor

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

internal fun planNumber(value: Double): String =
    java.text.DecimalFormat("0.##").format(value)

internal fun GreenhouseConfig.dimensionsLabel() =
    "${planNumber(planLengthFeet)} × ${planNumber(planWidthFeet)} ft"

internal fun GreenhouseConfig.areaLabel() = "${planNumber(areaSquareFeet)} sq ft"

private fun GreenhouseConfig.wallPoints() = outline()
    .map { NormalizedPosition(it.x * planLengthFeet, it.y * planWidthFeet) }
    .fold(mutableListOf<NormalizedPosition>()) { points, p ->
        if (points.lastOrNull() != p) points.add(p)
        points
    }

private fun midpoint(a: NormalizedPosition, b: NormalizedPosition) =
    NormalizedPosition((a.x + b.x) / 2, (a.y + b.y) / 2)

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
    var snap by remember { mutableStateOf(true) }
    var movingWalls by remember(saved.id) { mutableStateOf<List<NormalizedPosition>?>(null) }
    var preview by remember(saved.id) { mutableStateOf<GreenhouseConfig?>(null) }
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
        val displayed = preview ?: draft
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,
            verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
            Column {
                Text(displayed.areaLabel(), color=DashboardTokens.Amber, style=MaterialTheme.typography.headlineMedium)
                Text(displayed.dimensionsLabel(), color=Color.White)
            }
            FilterChip(selected=snap, onClick={ snap=!snap }, label={ Text(if(snap) "Snap on" else "Snap off") },
                colors=FilterChipDefaults.filterChipColors(labelColor=Color.White,
                    selectedLabelColor=DashboardTokens.Background, selectedContainerColor=DashboardTokens.Amber))
        }
        Text(if(drawing) "Trace the walls, or drag diagonally for a rectangle." else
            "Drag a corner to reshape. Drag a wall’s midpoint to add a corner.", color=DashboardTokens.Muted)
        Canvas(Modifier.fillMaxWidth().height(220.dp).background(DashboardTokens.Background,RoundedCornerShape(12.dp))
            .onSizeChanged { size=it }.semantics { contentDescription="Greenhouse floor plan" }
            .pointerInput(drawing, snap, size, sensors.map{it.id}, draft.planLengthFeet, draft.planWidthFeet) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val walls = draft.wallPoints()
                    fun distance(p: NormalizedPosition) = (pixel(p.x,p.y)-down.position).getDistance()
                    val midpoints = walls.indices.map { midpoint(walls[it],walls[(it+1)%walls.size]) }
                    val corner = walls.indices.minByOrNull { distance(walls[it]) }
                        ?.takeIf { distance(walls[it]) <= 20.dp.toPx() }
                    val edge = midpoints.indices.minByOrNull { distance(midpoints[it]) }
                        ?.takeIf { distance(midpoints[it]) <= 16.dp.toPx() && walls.size < 48 }
                    val hitSensor = sensors.minByOrNull { s ->
                        val p=position(s.id)
                        (pixel(p.xFeet,p.yFeet)-down.position).getDistance()
                    }?.id?.takeIf { id ->
                        val p=position(id)
                        (pixel(p.xFeet,p.yFeet)-down.position).getDistance() <= 16.dp.toPx()
                    }
                    val editWall = !drawing && selected == null && (corner != null || edge != null) &&
                        (hitSensor == null || corner != null)
                    val target = hitSensor ?: selected
                    if(!drawing && !editWall && target==null) return@awaitEachGesture
                    down.consume()
                    feedback=""
                    val grabbed = feet(down.position)
                    if(drawing) stroke=listOf(grabbed)
                    var edited = false
                    try {
                        do {
                            val event=awaitPointerEvent()
                            val finger=event.changes.find { it.id==down.id } ?: break
                            if(finger.isConsumed || event.changes.size>1) break
                            finger.consume()
                            val point=feet(finger.position)
                            if (editWall && (edited || (finger.position-down.position).getDistance() >= viewConfiguration.touchSlop)) {
                                edited = true
                                val vertices = walls.toMutableList()
                                val index = corner ?: (requireNotNull(edge)+1).also { vertices.add(it,midpoints[edge]) }
                                val origin = vertices[index]
                                val candidate = NormalizedPosition(origin.x+point.x-grabbed.x, origin.y+point.y-grabbed.y)
                                val prev = vertices[(index+vertices.size-1)%vertices.size]
                                val next = vertices[(index+1)%vertices.size]
                                val adjusted = if (snap) DrawnPlan.snapCorner(candidate,prev,next,8.dp.toPx()/scale) else candidate
                                vertices[index]=adjusted
                                if (vertices.size>3 && (adjusted==prev || adjusted==next)) vertices.removeAt(index)
                                val valid = DrawnPlan.fromCorners(vertices,draft)
                                movingWalls=vertices
                                preview=valid
                                feedback=if(valid==null) "Walls must form one closed outline without crossing." else ""
                                if (!finger.pressed && valid!=null) draft=valid
                            } else if (drawing) {
                                if(stroke.size<1024 && stroke.lastOrNull()!=point) stroke=stroke+point
                                if(!finger.pressed){
                                    val plan=DrawnPlan.fromStroke(stroke,draft,(8.dp.toPx()/scale).coerceAtLeast(.25),snap)
                                    if(plan==null) feedback="Draw a larger outline without crossing walls."
                                    else { draft=plan; drawing=false }
                                }
                            } else if (!editWall && target!=null) {
                                place(target,finger.position)
                                if(!finger.pressed) selected=null
                            }
                        } while(finger.pressed)
                    } finally {
                        stroke=emptyList()
                        movingWalls=null
                        preview=null
                    }
                }
            }) {
                fun trace(points:List<Offset>,close:Boolean):Path = Path().apply {
                    points.forEachIndexed { i,p->if(i==0)moveTo(p.x,p.y)else lineTo(p.x,p.y) }
                    if(close)close()
                }
                // Grid, outline and touch input all use the same pixels-per-foot transform.
                val step = if (scale >= 6.dp.toPx()) 1 else ceil(6.dp.toPx()/scale).toInt().coerceAtLeast(1)
                val gridColor=Color.White.copy(alpha=if(snap) .13f else .06f)
                for(x in (floor(-left/scale/step).toInt())..ceil((size.width-left)/scale/step).toInt()) {
                    val px=pixel(x.toDouble()*step,0.0).x
                    drawLine(gridColor,Offset(px,0f),Offset(px,size.height.toFloat()))
                }
                for(y in floor(-top/scale/step).toInt()..ceil((size.height-top)/scale/step).toInt()) {
                    val py=pixel(0.0,y.toDouble()*step).y
                    drawLine(gridColor,Offset(0f,py),Offset(size.width.toFloat(),py))
                }
                val corners=(movingWalls ?: draft.wallPoints()).map { pixel(it.x,it.y) }
                val walls=trace(corners,true)
                drawPath(walls,DashboardTokens.Amber.copy(alpha=.13f))
                drawPath(walls,if(movingWalls!=null && preview==null) Color.Red else DashboardTokens.Amber,style=Stroke(2.dp.toPx()))
                if(!drawing) corners.forEachIndexed { i,p ->
                    val end=corners[(i+1)%corners.size]
                    val mid=(p+end)/2f
                    if ((end-p).getDistance()>40.dp.toPx()) {
                        drawCircle(DashboardTokens.Amber,4.dp.toPx(),mid)
                        drawCircle(DashboardTokens.Background,2.dp.toPx(),mid)
                    }
                    drawCircle(DashboardTokens.Background,8.dp.toPx(),p)
                    drawCircle(DashboardTokens.Amber,7.dp.toPx(),p,style=Stroke(2.dp.toPx()))
                }
                if(stroke.isNotEmpty()) drawPath(trace(stroke.map{pixel(it.x,it.y)},false),Color.White,style=Stroke(2.dp.toPx()))
                if(!drawing) sensors.forEach { s ->
                    val p=position(s.id)
                    val center=pixel(p.xFeet,p.yFeet)
                    drawCircle(if(selected==s.id) Color.White else teal,10.dp.toPx(),center)
                    drawCircle(DashboardTokens.Background,4.dp.toPx(),center)
                }
        }
        if(feedback.isNotEmpty()) Text(feedback,color=DashboardTokens.Amber)
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
                PlanField("Length (ft)",draft.lengthFeet.let(::planNumber),Modifier.weight(1f)){ dimensionChange(it){v->draft.copy(lengthFeet=v)} }
                PlanField("Width (ft)",draft.widthFeet.let(::planNumber),Modifier.weight(1f)){ dimensionChange(it){v->draft.copy(widthFeet=v)} }
            }
            EditorAction(if(draft.shape==GreenhouseShape.TShape)"Use rectangle" else "Use T-shaped plan"){
                changeShape(draft.copy(shape=if(draft.shape==GreenhouseShape.TShape)GreenhouseShape.Rectangle else GreenhouseShape.TShape))
            }
            if(draft.shape==GreenhouseShape.TShape)Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                PlanField("Stem length (ft)",draft.attachedLengthFeet.let(::planNumber),Modifier.weight(1f)){dimensionChange(it){v->draft.copy(attachedLengthFeet=v)}}
                PlanField("Stem width (ft)",draft.attachedWidthFeet.let(::planNumber),Modifier.weight(1f)){dimensionChange(it){v->draft.copy(attachedWidthFeet=v)}}
            }
        }
        if(!drawing && sensors.isNotEmpty()) FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
            sensors.forEach { s -> FilterChip(selected=selected==s.id,onClick={selected=if(selected==s.id) null else s.id},label={Text(s.name)},colors=FilterChipDefaults.filterChipColors(labelColor=Color.White,selectedContainerColor=DashboardTokens.Amber)) }
        }
        Button(onClick={onSave(draft.copy(name=draft.name.ifBlank{saved.name}))},enabled=!drawing,colors=ButtonDefaults.buttonColors(containerColor=DashboardTokens.Amber,contentColor=DashboardTokens.Background)){Text("Save layout")}
    }
}

@Composable private fun EditorAction(label:String,onClick:()->Unit){
    OutlinedButton(onClick=onClick){Text(label,color=DashboardTokens.Amber)}
}

@Composable private fun PlanField(label:String,value:String,modifier:Modifier=Modifier,onChange:(String)->Unit){
    var text by remember { mutableStateOf(value) }
    OutlinedTextField(value=text,onValueChange={text=it;onChange(it)},label={Text(label)},modifier=modifier,singleLine=true,
        colors=OutlinedTextFieldDefaults.colors(focusedTextColor=Color.White,unfocusedTextColor=Color.White,focusedBorderColor=DashboardTokens.Amber))
}
