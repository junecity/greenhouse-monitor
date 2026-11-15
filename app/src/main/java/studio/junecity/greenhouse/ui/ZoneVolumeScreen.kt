package studio.junecity.greenhouse.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import kotlinx.coroutines.delay
import studio.junecity.greenhouse.data.SpatialSample
import studio.junecity.greenhouse.data.ZoneClimate
import studio.junecity.greenhouse.model.*
import kotlin.math.*

internal class Projection(g:GreenhouseConfig, height:Double, size:IntSize, angle:Double, tilt:Double,
                         zoom:Float,pan:Offset, padding:Float) {
    private val c=cos(angle); private val s=sin(angle); private val up=cos(tilt); private val depth=sin(tilt)
    private fun horizontal(x:Double,y:Double)=x*c-y*s
    private fun vertical(x:Double,y:Double,z:Double)=(x*s+y*c)*depth-z*up
    private val outline=g.outline().map { it.x*g.planLengthFeet to it.y*g.planWidthFeet }
    private val left=outline.minOf {horizontal(it.first,it.second)}
    private val right=outline.maxOf {horizontal(it.first,it.second)}
    private val top=outline.minOf {vertical(it.first,it.second,height)}
    private val bottom=outline.maxOf {vertical(it.first,it.second,0.0)}
    val scale=min((size.width-padding*2).coerceAtLeast(1f)/(right-left).coerceAtLeast(.1),
        (size.height-padding*2).coerceAtLeast(1f)/(bottom-top).coerceAtLeast(.1))*zoom
    private val cx=size.width*.5+pan.x-(left+right)*scale*.5
    private val cy=size.height*.5+pan.y-(top+bottom)*scale*.5
    fun point(x:Double,y:Double,z:Double)=Offset((cx+horizontal(x,y)*scale).toFloat(),(cy+vertical(x,y,z)*scale).toFloat())
    fun floorPoint(screen: Offset): SensorPosition {
        val horizontal = (screen.x - cx) / scale
        val vertical = (screen.y - cy) / (scale * depth)
        return SensorPosition(c * horizontal + s * vertical, c * vertical - s * horizontal)
    }
}

@Composable internal fun ZoneVolumeScreen(state:GreenhouseUiState, update:((AppPreferences)->AppPreferences)->Unit, dismiss:()->Unit) {
    val g=state.preferences.activeGreenhouse
    val sensors=state.sensors.filter {it.greenhouseId==g.id}
    var selected by rememberSaveable(g.id) {mutableStateOf<String?>(null)}
    val sensor=sensors.find {it.id==selected} ?: sensors.firstOrNull()
    var metric by rememberSaveable {mutableStateOf(if(state.metric==Metric.Humidity) Metric.Humidity else Metric.Temperature)}
    var now by remember {mutableLongStateOf(System.currentTimeMillis())}
    LaunchedEffect(Unit){while(true){now=System.currentTimeMillis();delay(15_000)}}
    val probes=remember(g,sensors,now){ZoneClimate.probes(state,now)}
    val insight=remember(g,probes,state.weather,state.history,state.preferences.notifications,now){ZoneClimate.insight(state,probes,now)}
    val fans = remember(g) { g.airCirculation().placed }
    var showFans by rememberSaveable(g.id) { mutableStateOf(true) }
    val h=ZoneClimate.height(g)
    val scale = ZoneHeatScale.forMetric(metric)
    val cells=remember(g,probes,metric){ZoneClimate.field(g,probes,metric).map {it to scale.color(it.value).copy(alpha=(it.opacity*.44).toFloat())}}
    val planLayer = remember(g, probes, metric) {
        val samples = probes.mapNotNull { (sensor, position) ->
            if (position.heightFeet != null) return@mapNotNull null
            val value = if (metric == Metric.Humidity) sensor.humidityPercent else sensor.temperatureF
            value?.let { SpatialSample(position.xFeet, position.yFeet, it) }
        }
        planHeatLayer(g, samples, scale)
    }
    val layerPaint = remember { android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG) }
    val editable=state.standalone || state.preferences.cameraMode!=CameraMode.ViewerClient
    val savedPosition = g.sensorPositions[sensor?.id]
    var placing by remember(g.id, sensor?.id, editable) { mutableStateOf(false) }
    var draft by remember(g.id, sensor?.id) { mutableStateOf<SensorPosition?>(null) }
    var level by remember(g.id, sensor?.id) { mutableStateOf<MountLevel?>(null) }
    var outside by remember { mutableStateOf(false) }
    androidx.activity.compose.BackHandler(placing) { placing = false }
    var editing by remember {mutableStateOf(false)}
    if(editing) HeightDialog(g,sensor,{editing=false}) {roof,stemRoof,mount ->
        update { prefs -> prefs.copy(greenhouses=prefs.greenhouses.map { house ->
            if(house.id!=g.id) house else {
                val pos=house.sensorPositions[sensor?.id]
                val positions=if(sensor!=null && pos!=null) house.sensorPositions+(sensor.id to pos.copy(heightFeet=mount, level=pos.level.takeIf { mount == null })) else house.sensorPositions
                house.copy(heightFeet=roof,attachedHeightFeet=stemRoof,sensorPositions=positions)
            }
        }) }
        editing=false
    }
    var yaw by rememberSaveable {mutableDoubleStateOf(-.56)}
    var pitch by rememberSaveable {mutableDoubleStateOf(.44)}
    var zoom by rememberSaveable {mutableFloatStateOf(1f)}
    var pan by remember {mutableStateOf(Offset.Zero)}
    fun fit(){yaw=-.56;pitch=.44;zoom=1f;pan=Offset.Zero}
    Column(Modifier.fillMaxSize().background(DashboardTokens.Background).windowInsetsPadding(WindowInsets.safeDrawing)
        .padding(horizontal=12.dp,vertical=5.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment=Alignment.CenterVertically) {
            Column(Modifier.weight(1f)){Text(g.name,color=Color.White,fontWeight=FontWeight.Bold,fontSize=19.sp,maxLines=1)
                Text("3D zones · ${g.areaLabel()}${if(g.heightFeet==null) " · Height estimated" else ""}",color=DashboardTokens.Muted,fontSize=12.sp)}
            InsightButton("Close",dismiss)
        }
        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)) {
            if (placing) {
                MountLevel.entries.forEach { band ->
                    FilterChip(selected = level == band, onClick = { level = band },
                        label = { Text(band.name) },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = DashboardTokens.Amber,
                            selectedLabelColor = DashboardTokens.Background, labelColor = Color.White))
                }
            } else for(m in listOf(Metric.Temperature,Metric.Humidity)){
                FilterChip(selected=metric==m,onClick={metric=m},label={Text(m.displayName())},
                    colors=FilterChipDefaults.filterChipColors(selectedContainerColor=DashboardTokens.Amber,selectedLabelColor=DashboardTokens.Background,labelColor=Color.White))
            }
            Spacer(Modifier.weight(1f))
            if (!placing) VolumeFanMenu(fans, showFans) { showFans = it }
            InsightButton("Fit",::fit)
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(Brush.radialGradient(listOf(Color(0xff29333a),Color(0xff13191d))))
            .border(1.dp,DashboardTokens.PanelEdge,RoundedCornerShape(14.dp))) {
            val density=androidx.compose.ui.platform.LocalDensity.current
            val size=IntSize(constraints.maxWidth,constraints.maxHeight)
            val camera=remember(g,h,size,yaw,pitch,zoom,pan){Projection(g,h,size,yaw,pitch,zoom,pan,with(density){12.dp.toPx()})}
            val positions=probes.map { probe -> val p=probe.position
                probe to camera.point(p.xFeet,p.yFeet,p.heightFeet ?: 0.0) }
            val markers by rememberUpdatedState(positions)
            val duration=accessibleMotionDuration(AppMotion.ChartRevealMs)
            val reveal = remember { Animatable(1f) }
            LaunchedEffect(metric, g.id, duration) {
                if(duration==0) reveal.snapTo(1f) else {
                    reveal.snapTo(.12f)
                    reveal.animateTo(1f,tween(duration))
                }
            }
            val activeColor by animateColorAsState(if(metric==Metric.Temperature) DashboardTokens.Amber else DashboardTokens.Blue,tween(duration),label="Metric accent")
            Canvas(Modifier.fillMaxSize().semantics {contentDescription="3D greenhouse. Drag to rotate; pinch to zoom and pan."
                    stateDescription="Zoom ${(zoom*100).roundToInt()}%"}
                .pointerInput(size) {
                    awaitEachGesture {
                        val down=awaitFirstDown();var travel=0f;var multi=false
                        do {
                            val e=awaitPointerEvent()
                            val move=e.calculatePan()
                            if(e.changes.count{it.pressed}>=2){
                                multi=true
                                zoom=(zoom*e.calculateZoom()).coerceIn(.55f,3.5f)
                                pan=Offset((pan.x+move.x).coerceIn(-size.width*.8f,size.width*.8f),
                                    (pan.y+move.y).coerceIn(-size.height*.8f,size.height*.8f))
                            } else if(!multi) {
                                travel+=move.getDistance()
                                if(travel>viewConfiguration.touchSlop){
                                    yaw+=move.x/this.density*.014;pitch=(pitch+move.y/this.density*.009).coerceIn(.12,1.22)
                                }
                            }
                            e.changes.forEach{it.consume()}
                        } while(e.changes.any{it.pressed})
                        if(!multi && travel<=viewConfiguration.touchSlop) markers.minByOrNull{(it.second-down.position).getDistance()}
                            ?.takeIf{(it.second-down.position).getDistance()<26.dp.toPx()}?.let{selected=it.first.sensor.id}
                    }
                }) {
                val outline=g.outline()
                fun polygon(z:Double)=Path().apply {outline.forEachIndexed{i,a->
                    val pt=camera.point(a.x*g.planLengthFeet,a.y*g.planWidthFeet,z)
                    if(i==0) moveTo(pt.x,pt.y) else lineTo(pt.x,pt.y)
                }; this.close()}
                fun rail(x:Double,y:Double,z:Double,ex:Double,ey:Double,ez:Double,color:Color) =
                    drawLine(color,camera.point(x,y,z),camera.point(ex,ey,ez),1.dp.toPx())
                val floor=polygon(0.0)
                val pitched=g.hasStem || g.shape==GreenhouseShape.Rectangle
                val eaves=if(pitched) ZoneClimate.mainHeight(g)*.8 else h
                drawPath(floor,zoneFloorColor)
                if (planLayer != null) {
                    val origin = camera.point(0.0, 0.0, 0.0)
                    val xEnd = camera.point(g.planLengthFeet, 0.0, 0.0)
                    val yEnd = camera.point(0.0, g.planWidthFeet, 0.0)
                    val transform = android.graphics.Matrix().apply {
                        setPolyToPoly(floatArrayOf(0f, 0f, planLayer.width.toFloat(), 0f, 0f, planLayer.height.toFloat()), 0,
                            floatArrayOf(origin.x, origin.y, xEnd.x, xEnd.y, yEnd.x, yEnd.y), 0, 3)
                    }
                    layerPaint.alpha = (255 * reveal.value).roundToInt()
                    clipPath(floor) { drawContext.canvas.nativeCanvas.drawBitmap(planLayer, transform, layerPaint) }
                }
                val rim=Color(0xff9daeb4).copy(alpha=.65f)
                val strut=rim.copy(alpha=.38f)
                androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()).let {
                    drawPath(floor,rim,style=it); if (!g.hasStem) drawPath(polygon(eaves),rim,style=it)
                }
                val step=maxOf(g.planLengthFeet/25,g.planWidthFeet/25,h/13,.3)
                for((cell,color) in cells)drawCircle(color.copy(alpha=color.alpha*reveal.value),(step*camera.scale*.68).toFloat(),camera.point(cell.x,cell.y,cell.z))
                outline.forEachIndexed {i,a->
                    if(!g.hasStem && (g.shape!=GreenhouseShape.Circle || i%6==0))rail(a.x*g.planLengthFeet,a.y*g.planWidthFeet,0.0,a.x*g.planLengthFeet,a.y*g.planWidthFeet,eaves,strut)
                }
                fun roof(x:Double,y:Double,length:Double,width:Double,alongX:Boolean,h:Double) {
                    val eaves=h*.8
                    val span=if(alongX) length else width
                    val across=if(alongX) width else length
                    fun at(u:Double,v:Double,z:Double)=camera.point(x+if(alongX) u else v,y+if(alongX) v else u,z)
                    if (g.hasStem) {
                        for (u in listOf(0.0,span)) {
                            drawLine(rim,at(u,0.0,eaves),at(u,across,eaves),1.dp.toPx())
                            for (v in listOf(0.0,across)) drawLine(strut,at(u,v,0.0),at(u,v,eaves),1.dp.toPx())
                        }
                        for (v in listOf(0.0,across)) drawLine(rim,at(0.0,v,eaves),at(span,v,eaves),1.dp.toPx())
                    }
                    drawLine(rim,at(0.0,across/2,h),at(span,across/2,h),1.dp.toPx())
                    val ribs=ceil(span/4).toInt().coerceIn(1,12)
                    for(i in 0..ribs) {
                        val u=span*i/ribs
                        drawLine(strut,at(u,0.0,eaves),at(u,across/2,h),1.dp.toPx())
                        drawLine(strut,at(u,across/2,h),at(u,across,eaves),1.dp.toPx())
                    }
                }
                if(pitched) {
                    roof(g.crossbarLeftFeet,0.0,g.lengthFeet,g.widthFeet,g.lengthFeet>=g.widthFeet,ZoneClimate.mainHeight(g))
                    if(g.hasStem) roof(g.stemLeftFeet,g.widthFeet,g.attachedWidthFeet,g.attachedLengthFeet,false,g.attachedHeightFeet ?: ZoneClimate.mainHeight(g))
                }
                // Grid on the physical floor only; a concave or circular plan clips it to its walls.
                clipPath(floor) {
                    val grid=max(2.0,ceil(max(g.planLengthFeet,g.planWidthFeet)/16))
                    var x=grid
                    while(x<g.planLengthFeet){rail(x,0.0,0.0,x,g.planWidthFeet,0.0,strut.copy(alpha=.16f));x+=grid}
                    var y=grid
                    while(y<g.planWidthFeet){rail(0.0,y,0.0,g.planLengthFeet,y,0.0,strut.copy(alpha=.16f));y+=grid}
                }
            }
            if (showFans && !placing) VolumeFans(fans, camera)
            Canvas(Modifier.fillMaxSize()) {
                for((probe,pt) in positions){
                    if (placing && probe.sensor.id == sensor?.id) continue
                    val p=probe.position; val v=if(metric==Metric.Humidity)probe.sensor.humidityPercent!! else probe.sensor.temperatureF!!
                    val mounted=p.heightFeet!=null; val color=scale.color(v)
                    if(mounted)drawLine(color.copy(alpha=.5f),camera.point(p.xFeet,p.yFeet,0.0),pt,1.dp.toPx())
                    drawCircle(DashboardTokens.Background,7.dp.toPx(),pt);drawCircle(color,4.dp.toPx(),pt)
                    if(probe.sensor.id==sensor?.id)drawCircle(activeColor,9.dp.toPx(),pt,style=androidx.compose.ui.graphics.drawscope.Stroke(1.5.dp.toPx()))
                    if (positions.size > 8 && probe.sensor.id != sensor?.id) continue
                    drawContext.canvas.nativeCanvas.apply {
                        val paint=android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                            textSize=12.sp.toPx();typeface=android.graphics.Typeface.DEFAULT_BOLD
                        }
                        val label=formatMetric(v,metric)
                        val width=paint.measureText(label)+10.dp.toPx()
                        val left=(pt.x+9.dp.toPx()).coerceIn(0f,(size.width-width).coerceAtLeast(0f))
                        val top=(pt.y-20.dp.toPx()).coerceIn(0f,(size.height-20.dp.toPx()).coerceAtLeast(0f))
                        paint.color=android.graphics.Color.rgb(20,25,29)
                        drawRoundRect(left,top,left+width,top+19.dp.toPx(),5.dp.toPx(),5.dp.toPx(),paint)
                        paint.color=android.graphics.Color.WHITE;drawText(label,left+5.dp.toPx(),top+14.dp.toPx(),paint)
                    }
                }
            }
            if (placing && sensor != null) {
                val preview = draft?.copy(level = level, heightFeet = savedPosition?.heightFeet.takeIf { level == null })
                VolumePlacement(g, sensor.name, camera, preview, outside) { point ->
                    outside = point == null
                    if (point != null) draft = point
                }
            } else if(planLayer != null) {
                Text("Sensors with unknown heights are shown on the floor plan.",
                    Modifier.align(Alignment.TopStart).padding(10.dp),color=DashboardTokens.Muted,fontSize=12.sp)
            }
            if (!editable && sensors.isNotEmpty()) Text("Place sensors on the greenhouse phone", Modifier.align(Alignment.BottomStart).padding(10.dp), color = DashboardTokens.Muted, fontSize = 12.sp)
            if(sensors.isEmpty() && (!showFans || fans.isEmpty())) Text("Add sensors in Settings → Devices",Modifier.align(Alignment.Center).background(DashboardTokens.Background).padding(8.dp),color=Color.White)
        }
        if (placing) {
            val point = draft
            val measured = savedPosition?.heightFeet.takeIf { level == null }
            val fits = point != null && (measured == null || measured <= ZoneClimate.sectionHeight(g, point.yFeet))
            if (point != null && !fits) Text("Choose Lower or Upper for this shorter section.", color = DashboardTokens.Amber)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(sensor?.name.orEmpty(), Modifier.weight(1f), color = Color.White, maxLines = 2)
                InsightButton("Cancel") { placing = false }
                Button(enabled = fits && !outside, onClick = {
                    if (point != null && sensor != null && editable) {
                        val position = point.copy(heightFeet = measured, level = level)
                        update { prefs -> prefs.copy(greenhouses = prefs.greenhouses.map { house ->
                            if (house.id == g.id && house.containsPoint(position.xFeet, position.yFeet))
                                house.copy(sensorPositions = house.sensorPositions + (sensor.id to position))
                            else house
                        }) }
                        placing = false
                    }
                }, colors = ButtonDefaults.buttonColors(containerColor = DashboardTokens.Amber, contentColor = DashboardTokens.Background)) {
                    Text("Save position")
                }
            }
        } else {
            ZoneHeatLegend(scale)
            Row(verticalAlignment=Alignment.CenterVertically) {
                var menu by remember {mutableStateOf(false)}
                Box(Modifier.weight(1f)) {
                    TextButton(onClick={menu=true},enabled=sensors.isNotEmpty()) {
                        Text(sensor?.let { s ->
                            val position=g.sensorPositions[s.id]
                            val mount=position?.heightFeet
                            val value=probes.find{it.sensor.id==s.id}?.sensor?.let{if(metric==Metric.Temperature)it.temperatureF else it.humidityPercent}
                            "${s.name} ▾  ·  ${formatMetric(value,metric)}  ·  ${mount?.let{planNumber(it)+" ft"} ?: position?.level?.name ?: "Height unset"}" + (s.lightLevel?.let { "  ·  Light: $it" } ?: "")
                        } ?: "No sensors",color=Color.White)
                    }
                    DropdownMenu(expanded=menu,onDismissRequest={menu=false}){sensors.forEach{s->
                        DropdownMenuItem(text={Text(s.name)},onClick={selected=s.id;menu=false})
                    }}
                }
                if(editable) {
                    if (sensor != null) InsightButton("Place sensor") {
                        draft = savedPosition
                        level = savedPosition?.level
                        outside = false
                        placing = true
                    }
                    InsightButton("Set height"){editing=true}
                }
            }
            Text(insight,color=DashboardTokens.Amber,fontSize=14.sp,lineHeight=17.sp,modifier=Modifier.fillMaxWidth().padding(bottom=4.dp))
        }
    }
}

@Composable internal fun HeightDialog(g:GreenhouseConfig,s:GoveeSensor?,close:()->Unit,save:(Double?,Double?,Double?)->Unit){
    val pos=g.sensorPositions[s?.id]
    var roof by remember(g.id){mutableStateOf(g.heightFeet?.toString().orEmpty())}
    var stem by remember(g.id){mutableStateOf(g.attachedHeightFeet?.toString().orEmpty())}
    var mount by remember(s?.id){mutableStateOf(pos?.heightFeet?.let(::planNumber).orEmpty())}
    val h=roof.toDoubleOrNull(); val sh=stem.toDoubleOrNull(); val z=mount.replace(',','.').toDoubleOrNull()
    val proposed=g.copy(heightFeet=h, attachedHeightFeet=if(g.hasStem) sh else g.attachedHeightFeet)
    val valid=(roof.isBlank() || h!=null && h in .1..480.0) &&
        (!g.hasStem || stem.isBlank() || sh!=null && sh in .1..480.0) &&
        (pos==null || mount.isBlank() || z!=null && z in 0.0..ZoneClimate.sectionHeight(proposed,pos.yFeet)) &&
        g.sensorPositions.all { (id,p) -> id==s?.id || p.heightFeet==null || p.heightFeet <= ZoneClimate.sectionHeight(proposed,p.yFeet) }
    AlertDialog(onDismissRequest=close,containerColor=DashboardTokens.PanelRaised,
        title={Text(if(s==null) "Greenhouse heights" else "Mounting height",color=Color.White)},text={
        Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)){
            if(s!=null && pos!=null) MeasurementField("${s.name} · ft above floor",mount){mount=it}
            MeasurementField(if(g.hasStem) "Crossbar peak height (ft)" else "Greenhouse peak height (ft)",roof){roof=it}
            if(g.hasStem) MeasurementField("Stem peak height (ft)",stem){stem=it}
            Text(if(g.hasStem) "Measure from the floor to each roof peak. Leave the stem blank to use the crossbar height."
                else "Measure from the floor to the highest point of the roof.",color=DashboardTokens.Muted)
            if(!valid)Text("Check the heights. Each roof must include the sensors in that section.",color=DashboardTokens.Amber)
        }
    },confirmButton={TextButton(enabled=valid,onClick={save(h,proposed.attachedHeightFeet,z)}){Text("Save",color=DashboardTokens.Amber)}},
        dismissButton={TextButton(onClick=close){Text("Cancel",color=DashboardTokens.Muted)}})
}
