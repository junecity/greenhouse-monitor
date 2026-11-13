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
import studio.junecity.greenhouse.data.ZoneClimate
import studio.junecity.greenhouse.model.*
import kotlin.math.*

private val thermal = listOf(Color(0xff568aff),Color(0xff49ccdd),Color(0xffadd989),Color(0xffffbb55),Color(0xfffa714e))
private val moisture = listOf(Color(0xffeabb72),Color(0xffb6d397),Color(0xff5bcabf),Color(0xff519ee6),Color(0xff9885ed))
private fun shade(value:Double,metric:Metric):Color {
    val colors=if(metric==Metric.Temperature) thermal else moisture
    val fraction=if(metric==Metric.Temperature)(value-40)/50 else (value-35)/65
    val index=fraction.coerceIn(0.0,1.0)*(colors.size-1)
    val i=index.toInt().coerceAtMost(colors.size-2)
    return lerp(colors[i],colors[i+1],(index-i).toFloat())
}

private class Projection(g:GreenhouseConfig, height:Double, size:IntSize, angle:Double, tilt:Double,
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
    val h=ZoneClimate.height(g)
    val cells=remember(g,probes,metric){ZoneClimate.field(g,probes,metric).map {it to shade(it.value,metric).copy(alpha=(it.opacity*.44).toFloat())}}
    val editable=state.standalone || state.preferences.cameraMode!=CameraMode.ViewerClient
    var editing by remember {mutableStateOf(false)}
    if(editing) HeightDialog(g,sensor,{editing=false}) {roof,mount ->
        update { prefs -> prefs.copy(greenhouses=prefs.greenhouses.map { house ->
            if(house.id!=g.id) house else {
                val pos=house.sensorPositions[sensor?.id]
                val positions=if(sensor!=null && pos!=null) house.sensorPositions+(sensor.id to pos.copy(heightFeet=mount)) else house.sensorPositions
                house.copy(heightFeet=roof,sensorPositions=positions)
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
            for(m in listOf(Metric.Temperature,Metric.Humidity)){
                FilterChip(selected=metric==m,onClick={metric=m},label={Text(m.displayName())},
                    colors=FilterChipDefaults.filterChipColors(selectedContainerColor=DashboardTokens.Amber,selectedLabelColor=DashboardTokens.Background,labelColor=Color.White))
            }
            Spacer(Modifier.weight(1f)); InsightButton("Fit",::fit)
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
                val eaves=if(pitched) h*.76 else h
                drawPath(floor,Color(0xff22343a))
                val rim=Color(0xff9daeb4).copy(alpha=.65f)
                val strut=rim.copy(alpha=.38f)
                androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()).let {
                    drawPath(floor,rim,style=it);drawPath(polygon(eaves),rim,style=it)
                }
                val step=maxOf(g.planLengthFeet/25,g.planWidthFeet/25,h/13,.3)
                for((cell,color) in cells)drawCircle(color.copy(alpha=color.alpha*reveal.value),(step*camera.scale*.68).toFloat(),camera.point(cell.x,cell.y,cell.z))
                outline.forEachIndexed {i,a->
                    if(g.shape!=GreenhouseShape.Circle || i%6==0)rail(a.x*g.planLengthFeet,a.y*g.planWidthFeet,0.0,a.x*g.planLengthFeet,a.y*g.planWidthFeet,eaves,strut)
                }
                fun roof(x:Double,y:Double,length:Double,width:Double,alongX:Boolean) {
                    val span=if(alongX) length else width
                    val across=if(alongX) width else length
                    fun at(u:Double,v:Double,z:Double)=camera.point(x+if(alongX) u else v,y+if(alongX) v else u,z)
                    drawLine(rim,at(0.0,across/2,h),at(span,across/2,h),1.dp.toPx())
                    val ribs=ceil(span/4).toInt().coerceIn(1,12)
                    for(i in 0..ribs) {
                        val u=span*i/ribs
                        drawLine(strut,at(u,0.0,eaves),at(u,across/2,h),1.dp.toPx())
                        drawLine(strut,at(u,across/2,h),at(u,across,eaves),1.dp.toPx())
                    }
                }
                if(pitched) {
                    roof(g.crossbarLeftFeet,0.0,g.lengthFeet,g.widthFeet,g.lengthFeet>=g.widthFeet)
                    if(g.hasStem) roof(g.stemLeftFeet,g.widthFeet,g.attachedWidthFeet,g.attachedLengthFeet,false)
                }
                // Grid on the physical floor only; a concave or circular plan clips it to its walls.
                clipPath(floor) {
                    val grid=max(2.0,ceil(max(g.planLengthFeet,g.planWidthFeet)/16))
                    var x=grid
                    while(x<g.planLengthFeet){rail(x,0.0,0.0,x,g.planWidthFeet,0.0,strut.copy(alpha=.16f));x+=grid}
                    var y=grid
                    while(y<g.planWidthFeet){rail(0.0,y,0.0,g.planLengthFeet,y,0.0,strut.copy(alpha=.16f));y+=grid}
                }
                for((probe,pt) in positions){
                    val p=probe.position; val v=if(metric==Metric.Humidity)probe.sensor.humidityPercent!! else probe.sensor.temperatureF!!
                    val mounted=p.heightFeet!=null; val color=if(mounted)shade(v,metric) else DashboardTokens.Muted
                    if(mounted)drawLine(color.copy(alpha=.5f),camera.point(p.xFeet,p.yFeet,0.0),pt,1.dp.toPx())
                    drawCircle(DashboardTokens.Background,7.dp.toPx(),pt);drawCircle(color,4.dp.toPx(),pt)
                    if(probe.sensor.id==sensor?.id)drawCircle(activeColor,9.dp.toPx(),pt,style=androidx.compose.ui.graphics.drawscope.Stroke(1.5.dp.toPx()))
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
            if(probes.isNotEmpty() && probes.all { it.position.heightFeet == null }) {
                Text(if(editable) "Set mounting heights to see the air map" else "Set mounting heights on the greenhouse phone",
                    Modifier.align(Alignment.TopStart).padding(10.dp),color=DashboardTokens.Muted,fontSize=12.sp)
            }
            if(sensors.isEmpty()) Text("Add a sensor to see readings here",Modifier.align(Alignment.Center).background(DashboardTokens.Background).padding(8.dp),color=Color.White)
        }
        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)){
            val temp=metric==Metric.Temperature
            Text(if(temp)"40°F" else "35%",color=DashboardTokens.Muted,fontSize=12.sp)
            Spacer(Modifier.width(54.dp).height(5.dp).background(Brush.horizontalGradient(if(temp)thermal else moisture),RoundedCornerShape(3.dp)))
            Text(if(temp)"90°F" else "100%",color=DashboardTokens.Muted,fontSize=12.sp)
            Text("· Estimated nearby",color=DashboardTokens.Muted,fontSize=12.sp)
        }
        Row(verticalAlignment=Alignment.CenterVertically) {
            var menu by remember {mutableStateOf(false)}
            Box(Modifier.weight(1f)) {
                TextButton(onClick={menu=true},enabled=sensors.isNotEmpty()) {
                    Text(sensor?.let { s ->
                        val mount=g.sensorPositions[s.id]?.heightFeet
                        val value=probes.find{it.sensor.id==s.id}?.sensor?.let{if(metric==Metric.Temperature)it.temperatureF else it.humidityPercent}
                        "${s.name} ▾  ·  ${formatMetric(value,metric)}  ·  ${mount?.let{planNumber(it)+" ft"} ?: "Height unset"}"
                    } ?: "No sensors",color=Color.White)
                }
                DropdownMenu(expanded=menu,onDismissRequest={menu=false}){sensors.forEach{s->
                    DropdownMenuItem(text={Text(s.name)},onClick={selected=s.id;menu=false})
                }}
            }
            if(editable) InsightButton("Set height"){editing=true}
        }
        Text(insight,color=DashboardTokens.Amber,fontSize=14.sp,lineHeight=17.sp,modifier=Modifier.fillMaxWidth().padding(bottom=4.dp))
    }
}

@Composable private fun HeightDialog(g:GreenhouseConfig,s:GoveeSensor?,close:()->Unit,save:(Double,Double?)->Unit){
    val pos=g.sensorPositions[s?.id]
    var roof by remember(g.id){mutableStateOf(planNumber(ZoneClimate.height(g)))}
    var mount by remember(s?.id){mutableStateOf(pos?.heightFeet?.let(::planNumber).orEmpty())}
    val maxOther=g.sensorPositions.filterKeys{it!=s?.id}.values.maxOfOrNull{it.heightFeet ?: 0.0} ?: 0.0
    val h=roof.toDoubleOrNull();val z=mount.toDoubleOrNull()
    val valid=h!=null && h in .1..480.0 && h>=maxOther && (pos==null || mount.isBlank() || z!=null && z in 0.0..h)
    AlertDialog(onDismissRequest=close,title={Text("Mounting height")},text={
        Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)){
            OutlinedTextField(roof,{roof=it},label={Text("Greenhouse height (ft)")},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal))
            if(s!=null && pos!=null)OutlinedTextField(mount,{mount=it},label={Text("${s.name} · ft above floor")},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal))
            if(!valid)Text("Enter a greenhouse height that includes all mounted sensors.",color=DashboardTokens.Amber)
        }
    },confirmButton={TextButton(enabled=valid,onClick={save(requireNotNull(h),z)}){Text("Save")}},dismissButton={TextButton(onClick=close){Text("Cancel")}})
}
