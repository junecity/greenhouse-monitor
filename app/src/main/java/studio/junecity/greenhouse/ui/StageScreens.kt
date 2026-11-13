package studio.junecity.greenhouse.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import studio.junecity.greenhouse.camera.CameraPairing
import studio.junecity.greenhouse.camera.CameraPairingStore
import studio.junecity.greenhouse.camera.CameraStreamServer
import studio.junecity.greenhouse.cloud.CloudDeviceManagementPanel
import studio.junecity.greenhouse.cloud.CloudServerPairingPanel
import studio.junecity.greenhouse.cloud.CloudDeviceRole
import studio.junecity.greenhouse.settings.SecureSettingsStore
import studio.junecity.greenhouse.data.SpatialInterpolator
import studio.junecity.greenhouse.model.*
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.math.roundToInt

@Composable fun StageScreen(state:GreenhouseUiState,status:DeviceStatus,now:LocalDateTime,onNavigate:(AppScreen)->Unit,onMetric:(Metric)->Unit,onRefresh:()->Unit,onSaveKey:(String)->Unit,onMoveSensor:(String,Double,Double)->Unit,onAssignZone:(String,String)->Unit,onSaveZip:(String)->Unit,onRefreshWeather:()->Unit,onUpdateGreenhouse:(String,Double,Double)->Unit,onUpdatePreferences:((AppPreferences)->AppPreferences)->Unit,modifier:Modifier=Modifier,openSensors:Boolean=false){ApprovedFrame(state.screen,status,now,onNavigate,modifier,standalone=state.standalone){d,t->val stageText:(Float)->androidx.compose.ui.unit.TextUnit={value->t(value*.72f)};when(state.screen){AppScreen.Zones->ZonesContent(state,d,stageText,onMetric,onMoveSensor);AppScreen.History->HistoryContent(state,d,stageText,onMetric);AppScreen.Cameras->CameraContent(state,d,stageText);AppScreen.Settings->SettingsContent(state,d,t,onRefresh,onSaveKey,onAssignZone,onSaveZip,onRefreshWeather,onUpdateGreenhouse,onUpdatePreferences,openSensors);else->Unit}}}

@Composable private fun MetricControls(active:Metric,d:(Float)->androidx.compose.ui.unit.Dp,t:(Float)->androidx.compose.ui.unit.TextUnit,onMetric:(Metric)->Unit){val labels=listOf(Triple(Metric.Temperature,"Temperature",DashboardIcon.Thermometer),Triple(Metric.Humidity,"Humidity",DashboardIcon.Droplet),Triple(Metric.Vpd,"VPD",DashboardIcon.Gauge),Triple(Metric.DewPoint,"Dew Point",DashboardIcon.Droplet));Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(d(16f))){labels.forEach{(m,label,icon)->val selected=m==active;Row(Modifier.weight(1f).height(d(72f)).background(if(selected)DashboardTokens.Amber else Color(0x90001317),RoundedCornerShape(d(28f))).border(d(2f),if(selected)DashboardTokens.AmberBright else DashboardTokens.PanelEdge,RoundedCornerShape(d(28f))).clickable{onMetric(m)},horizontalArrangement=Arrangement.Center,verticalAlignment=Alignment.CenterVertically){DashboardIconView(icon,if(selected)DashboardTokens.Background else if(m==Metric.Humidity||m==Metric.DewPoint)DashboardTokens.Blue else Color.White,Modifier.size(d(26f)));Spacer(Modifier.width(d(10f)));Text(label,color=if(selected)DashboardTokens.Background else Color.White,fontSize=t(18f),fontWeight=FontWeight.Bold)}}}}

@Composable private fun ZonesContent(state:GreenhouseUiState,d:(Float)->androidx.compose.ui.unit.Dp,t:(Float)->androidx.compose.ui.unit.TextUnit,onMetric:(Metric)->Unit,onMove:(String,Double,Double)->Unit){var insight by remember { mutableStateOf<Insight?>(null) };insight?.let { InsightsDialog(it,state,d,t){insight=null} };val g=state.preferences.activeGreenhouse;val fresh=state.sensors.filter{it.greenhouseId==g.id&&it.isFresh()};val samples=SpatialInterpolator.samples(fresh,g,state.metric);val center=SpatialInterpolator.center(samples,g);val shapeLabel=g.areaLabel();Column(Modifier.fillMaxSize()){Row(Modifier.height(d(74f)),horizontalArrangement=Arrangement.spacedBy(d(18f))){NeonPanel(Modifier.weight(.30f).fillMaxHeight()){Row(Modifier.fillMaxSize().padding(horizontal=d(25f)),verticalAlignment=Alignment.CenterVertically){DashboardIconView(DashboardIcon.HeatMap,Color.White,Modifier.size(d(30f)));Spacer(Modifier.width(d(20f)));Text("${g.name} · $shapeLabel",color=Color.White,fontSize=t(18f),fontWeight=FontWeight.Bold)}};Box(Modifier.weight(.70f)){MetricControls(state.metric,d,t,onMetric)}};Spacer(Modifier.height(d(14f)));Row(Modifier.weight(1f),horizontalArrangement=Arrangement.spacedBy(d(14f))){Column(Modifier.weight(.18f).fillMaxHeight(),verticalArrangement=Arrangement.spacedBy(d(12f))){ZoneStat("Avg Temp",formatTemp(SpatialInterpolator.greenhouseAverage(fresh,g,Metric.Temperature)),DashboardIcon.Thermometer,Color.White,d,t,Modifier.weight(1f));ZoneStat("Avg Humidity",formatHumidity(SpatialInterpolator.greenhouseAverage(fresh,g,Metric.Humidity)),DashboardIcon.Droplet,DashboardTokens.Blue,d,t,Modifier.weight(1f));ZoneStat("Sensors","${fresh.size} Online",DashboardIcon.Wifi,DashboardTokens.Green,d,t,Modifier.weight(1f))};NeonPanel(Modifier.weight(.51f).fillMaxHeight()){Column(Modifier.fillMaxSize().padding(d(18f))){Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text("Zone Heat Map",color=Color.White,fontSize=t(23f),fontWeight=FontWeight.Bold);Text("Center ${formatMetric(center,state.metric)}  ·  ${g.dimensionsLabel()} footprint",color=Color.White,fontSize=t(15f),fontWeight=FontWeight.Bold)};Spacer(Modifier.height(d(10f)));HeatMap(samples,state.metric,g,fresh,d,t,onMove,Modifier.weight(1f).fillMaxWidth())}};NeonPanel(Modifier.weight(.31f).fillMaxHeight()){Column(Modifier.fillMaxSize()){Row{InsightButton("Compare"){insight=Insight.Compare};InsightButton("Replay"){insight=Insight.Replay}};if(fresh.isEmpty())Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){Text("No fresh sensor readings",color=DashboardTokens.Muted,fontSize=t(16f))}else LazyColumn(Modifier.fillMaxSize().padding(d(10f)),verticalArrangement=Arrangement.spacedBy(d(7f))){items(fresh,key={it.id}){s->ZoneSummary(s,d,t)}}}}}}}

@Composable private fun ZoneStat(label:String,value:String,icon:DashboardIcon,tint:Color,d:(Float)->androidx.compose.ui.unit.Dp,t:(Float)->androidx.compose.ui.unit.TextUnit,modifier:Modifier){NeonPanel(modifier){Row(Modifier.fillMaxSize().padding(d(18f)),verticalAlignment=Alignment.CenterVertically){DashboardIconView(icon,tint,Modifier.size(d(46f)));Spacer(Modifier.width(d(18f)));Column{Text(label,color=Color.White,fontSize=t(15f),fontWeight=FontWeight.Bold);Text(value,color=tint,fontSize=t(27f),fontWeight=FontWeight.Bold)}}}}
@Composable private fun ZoneSummary(s:GoveeSensor,d:(Float)->androidx.compose.ui.unit.Dp,t:(Float)->androidx.compose.ui.unit.TextUnit){val color=sensorColor(s);Row(Modifier.fillMaxWidth().height(d(74f)).background(Color(0x7A08282C),RoundedCornerShape(d(16f))).border(d(1f),Color(0xFF245A61),RoundedCornerShape(d(16f))).padding(d(10f)),verticalAlignment=Alignment.CenterVertically){Box(Modifier.width(d(10f)).fillMaxHeight().background(color,RoundedCornerShape(d(8f))));Spacer(Modifier.width(d(14f)));Column(Modifier.weight(1f)){Text(s.zone,color=Color.White,fontSize=t(18f),fontWeight=FontWeight.Bold);Text(s.name,color=DashboardTokens.Muted,fontSize=t(11f),maxLines=1)};Text(formatTemp(s.temperatureF),color=color,fontSize=t(22f),fontWeight=FontWeight.Bold)}}

@Composable internal fun HeatMap(samples:List<studio.junecity.greenhouse.data.SpatialSample>,metric:Metric,g:GreenhouseConfig,sensors:List<GoveeSensor>,d:(Float)->androidx.compose.ui.unit.Dp,t:(Float)->androidx.compose.ui.unit.TextUnit,onMove:(String,Double,Double)->Unit,modifier:Modifier,editable:Boolean=true,scale:ClosedFloatingPointRange<Double>?=null){
    BoxWithConstraints(modifier.background(Color(0xFF061D20),RoundedCornerShape(d(18f))).border(d(2f),Color.White,RoundedCornerShape(d(18f))).padding(d(8f)),contentAlignment=Alignment.Center){
        val footprintAspect=(g.planLengthFeet/g.planWidthFeet).toFloat()
        val availableAspect=maxWidth.value/maxHeight.value
        val plotModifier=if(footprintAspect>=availableAspect)Modifier.fillMaxWidth().aspectRatio(footprintAspect) else Modifier.fillMaxHeight().aspectRatio(footprintAspect)
        BoxWithConstraints(plotModifier){
            val samplesPerFoot=2
            val cols=kotlin.math.ceil(g.planLengthFeet*samplesPerFoot).toInt().coerceIn(1,120)
            val rows=kotlin.math.ceil(g.planWidthFeet*samplesPerFoot).toInt().coerceIn(1,120)
            val values=List(rows){y->List(cols){x->val px=(x+.5)*g.planLengthFeet/cols;val py=(y+.5)*g.planWidthFeet/rows;SpatialInterpolator.estimate(samples,px,py)}}
            val finite=values.flatten().filterNotNull()
            val min=scale?.start?:finite.minOrNull()?:60.0
            val max=scale?.endInclusive?:finite.maxOrNull()?:80.0
            Canvas(Modifier.fillMaxSize()){
                val cw=size.width/cols
                val rh=size.height/rows
                val outline=Path()
                g.outline().forEachIndexed { i,p ->
                    val x=(p.x*size.width).toFloat()
                    val y=(p.y*size.height).toFloat()
                    if(i==0) outline.moveTo(x,y) else outline.lineTo(x,y)
                }
                outline.close()
                clipPath(outline) {
                    for (y in 0 until rows) for (x in 0 until cols) {
                        val value=values[y][x]
                        val color=if(value==null) Color(0xFF0B3134) else heatColor(value,min,max,metric)
                        drawRect(color,Offset(x*cw,y*rh),Size(cw+1,rh+1))
                    }
                    val gridColor=Color.White.copy(alpha=.12f)
                    val gridStroke=d(.5f).toPx()
                    for (foot in 1 until kotlin.math.ceil(g.planLengthFeet).toInt()) {
                        val x=size.width*foot/g.planLengthFeet.toFloat()
                        drawLine(gridColor,Offset(x,0f),Offset(x,size.height),gridStroke)
                    }
                    for (foot in 1 until kotlin.math.ceil(g.planWidthFeet).toInt()) {
                        val y=size.height*foot/g.planWidthFeet.toFloat()
                        drawLine(gridColor,Offset(0f,y),Offset(size.width,y),gridStroke)
                    }
                }
                drawPath(outline,Color.White,style=Stroke(d(2f).toPx(),cap=StrokeCap.Round))
                if(g.shape==GreenhouseShape.TShape){
                    val junctionY=(g.widthFeet/g.planWidthFeet*size.height).toFloat()
                    val junctionLeft=(g.stemLeftFeet/g.planLengthFeet*size.width).toFloat()
                    val junctionRight=((g.stemLeftFeet+g.attachedWidthFeet)/g.planLengthFeet*size.width).toFloat()
                    drawLine(Color.White,Offset(junctionLeft,junctionY),Offset(junctionRight,junctionY),d(2f).toPx(),StrokeCap.Round)
                }
            }
            Text("${planNumber(g.lengthFeet)} × ${planNumber(g.widthFeet)} ft",color=Color.White.copy(alpha=.72f),fontSize=t(12f),fontWeight=FontWeight.Bold,modifier=Modifier.align(Alignment.TopCenter).offset(y=maxHeight*(g.widthFeet/g.planWidthFeet/2.0).toFloat()-d(10f)).background(Color.Black.copy(alpha=.35f),RoundedCornerShape(d(8f))).padding(horizontal=d(8f),vertical=d(3f)))
            if(g.shape==GreenhouseShape.TShape)Text("${planNumber(g.attachedLengthFeet)} × ${planNumber(g.attachedWidthFeet)} ft",color=Color.White.copy(alpha=.72f),fontSize=t(12f),fontWeight=FontWeight.Bold,modifier=Modifier.align(Alignment.Center).offset(y=maxHeight*((g.widthFeet+g.attachedLengthFeet/2.0)/g.planWidthFeet-.5).toFloat()).graphicsLayer{rotationZ=90f}.background(Color.Black.copy(alpha=.35f),RoundedCornerShape(d(8f))).padding(horizontal=d(8f),vertical=d(3f)))
            sensors.forEachIndexed{i,s->val stored=g.sensorPositions[s.id]?:g.coercePoint(g.planLengthFeet*(if(i%2==0).2 else .8),g.planWidthFeet*(if(i<2).2 else .7));DraggableSensor(s,stored.normalized(g),metric,d,t,maxWidth.value,maxHeight.value,editable){nx,ny->onMove(s.id,nx,ny)}}
        }
    }
}
@Composable private fun DraggableSensor(sensor:GoveeSensor,initial:NormalizedPosition,metric:Metric,d:(Float)->androidx.compose.ui.unit.Dp,t:(Float)->androidx.compose.ui.unit.TextUnit,widthDp:Float,heightDp:Float,editable:Boolean,onMove:(Double,Double)->Unit){var x by remember(sensor.id,initial.x){mutableDoubleStateOf(initial.x)};var y by remember(sensor.id,initial.y){mutableDoubleStateOf(initial.y)};val density=androidx.compose.ui.platform.LocalDensity.current.density;val value=when(metric){Metric.Temperature->formatTemp(sensor.temperatureF);Metric.Humidity->formatHumidity(sensor.humidityPercent);Metric.Vpd->sensor.vpdKpa?.let{"%.1f".format(it)}?:"—";Metric.DewPoint->formatTemp(sensor.dewPointF)};Column(Modifier.offset{IntOffset((x*widthDp*density-d(42f).toPx()).roundToInt(),(y*heightDp*density-d(42f).toPx()).roundToInt())}.size(d(84f)).background(Color(0xCC082326),CircleShape).border(d(2f),Color.White,CircleShape).pointerInput(sensor.id,widthDp,heightDp,editable){if(!editable)return@pointerInput;detectDragGestures(onDragEnd={onMove(x,y)}){change,drag->change.consume();x=(x+drag.x/(widthDp*density)).coerceIn(.04,.96);y=(y+drag.y/(heightDp*density)).coerceIn(.08,.92)}},horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center){Text(sensor.zone.removePrefix("Zone ").let{"S$it"},color=Color.White,fontSize=t(14f),fontWeight=FontWeight.Bold);Text(value,color=Color.White,fontSize=t(16f),fontWeight=FontWeight.Bold);Box(Modifier.size(d(8f)).background(if(sensor.online)DashboardTokens.Green else Color.Gray,CircleShape))}}

@Composable private fun HistoryContent(state:GreenhouseUiState,d:(Float)->androidx.compose.ui.unit.Dp,t:(Float)->androidx.compose.ui.unit.TextUnit,onMetric:(Metric)->Unit){var overnight by remember { mutableStateOf(false) };if(overnight)InsightsDialog(Insight.Overnight,state,d,t){overnight=false};Column(Modifier.fillMaxSize()){Row(Modifier.height(d(72f)),horizontalArrangement=Arrangement.spacedBy(d(18f))){NeonPanel(Modifier.weight(.30f).fillMaxHeight()){Row(Modifier.fillMaxSize(),horizontalArrangement=Arrangement.Center,verticalAlignment=Alignment.CenterVertically){DashboardIconView(DashboardIcon.Calendar,Color.White,Modifier.size(d(28f)));Spacer(Modifier.width(d(14f)));InsightButton("Overnight report"){overnight=true}}};Box(Modifier.weight(.70f)){MetricControls(state.metric,d,t,onMetric)}};Spacer(Modifier.height(d(16f)));Row(Modifier.weight(1f),horizontalArrangement=Arrangement.spacedBy(d(14f))){NeonPanel(Modifier.weight(.70f).fillMaxHeight()){Column(Modifier.fillMaxSize().padding(d(22f))){Text("${state.metric.name} History",color=Color.White,fontSize=t(24f),fontWeight=FontWeight.Bold);Spacer(Modifier.height(d(12f)));HistoryChart(state.history,state.metric,d,t,Modifier.weight(1f).fillMaxWidth())}};NeonPanel(Modifier.weight(.30f).fillMaxHeight()){val grouped=state.history.groupBy{it.sensorId};if(grouped.isEmpty())Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){Text("No recorded sensors",color=DashboardTokens.Muted,fontSize=t(15f))}else LazyColumn(Modifier.fillMaxSize().padding(d(10f)),verticalArrangement=Arrangement.spacedBy(d(7f))){items(grouped.entries.toList().take(6)){(_,rows)->val latest=rows.maxByOrNull{it.timestampEpochMs};val vals=rows.map{historyMetric(it,state.metric)};Row(Modifier.fillMaxWidth().height(d(76f)).background(Color(0x6607272B),RoundedCornerShape(d(14f))).padding(d(12f)),verticalAlignment=Alignment.CenterVertically){Box(Modifier.width(d(9f)).fillMaxHeight().background(sensorColor(latest?.sensorName?:""),RoundedCornerShape(d(6f))));Spacer(Modifier.width(d(12f)));Column(Modifier.weight(1f)){Text(latest?.sensorName?:"Sensor",color=Color.White,fontSize=t(16f),fontWeight=FontWeight.Bold);Text("Min ${formatMetric(vals.minOrNull(),state.metric)}   Max ${formatMetric(vals.maxOrNull(),state.metric)}",color=DashboardTokens.Muted,fontSize=t(11f))};Text(formatMetric(latest?.let{historyMetric(it,state.metric)},state.metric),color=DashboardTokens.Green,fontSize=t(21f),fontWeight=FontWeight.Bold)}}}}}}}
@Composable private fun HistoryChart(history:List<HistoryReading>,metric:Metric,d:(Float)->androidx.compose.ui.unit.Dp,t:(Float)->androidx.compose.ui.unit.TextUnit,modifier:Modifier){Box(modifier){var entered by remember(metric){mutableStateOf(false)};LaunchedEffect(metric){entered=true};val reveal by androidx.compose.animation.core.animateFloatAsState(if(entered)1f else 0f,androidx.compose.animation.core.tween(accessibleMotionDuration(AppMotion.ChartRevealMs)),label="chart reveal");Canvas(Modifier.fillMaxSize().graphicsLayer{scaleX=reveal;transformOrigin=androidx.compose.ui.graphics.TransformOrigin(0f,.5f)}){val cutoff=System.currentTimeMillis()-7L*24*60*60*1000;val rows=history.filter{it.timestampEpochMs>=cutoff}.sortedBy{it.timestampEpochMs};if(rows.isEmpty()){drawLine(Color(0xFF35646B),Offset(0f,size.height/2),Offset(size.width,size.height/2),2f);return@Canvas};val values=rows.map{historyMetric(it,metric)};val padding=if(metric==Metric.Vpd).2 else 2.0;val min=(values.minOrNull()?:0.0)-padding;val max=(values.maxOrNull()?:1.0)+padding;val start=rows.first().timestampEpochMs;val end=rows.last().timestampEpochMs.coerceAtLeast(start+1);repeat(5){i->val y=size.height*i/4;drawLine(Color(0xFF35646B),Offset(0f,y),Offset(size.width,y),1f)};rows.groupBy{it.sensorId}.values.forEachIndexed{i,series->val p=Path();series.forEachIndexed{j,r->val x=((r.timestampEpochMs-start).toFloat()/(end-start))*size.width;val v=historyMetric(r,metric);val y=size.height-((v-min)/(max-min)*size.height).toFloat();if(j==0)p.moveTo(x,y)else p.lineTo(x,y)};drawPath(p,sensorColor(i),style=Stroke(5f,cap=StrokeCap.Round))}};if(history.isEmpty())Text("No history yet · readings appear after the first sensor sync",color=DashboardTokens.Muted,fontSize=t(15f),modifier=Modifier.align(Alignment.Center))}}

@Composable private fun CameraContent(state:GreenhouseUiState,d:(Float)->androidx.compose.ui.unit.Dp,t:(Float)->androidx.compose.ui.unit.TextUnit){
    val context=LocalContext.current
    val cloudEnrollment=remember{SecureSettingsStore(context).cloudEnrollment()}
    if(state.standalone){
        CameraMessage("No greenhouse camera","Pair a server in Settings → Connectivity to use Live View.",d,t)
        return
    }
    var selectedTab by remember{mutableIntStateOf(0)}
    var permissionGranted by remember{mutableStateOf(ContextCompat.checkSelfPermission(context,Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED)}
    var cameraActive by remember{mutableStateOf(false)}
    var cameraMessage by remember{mutableStateOf<String?>(null)}
    val permissionLauncher=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){permissionGranted=it;cameraMessage=if(it)null else "Camera permission denied"}
    LaunchedEffect(state.preferences.cameraMode){if(state.preferences.cameraMode==CameraMode.CameraServer&&!permissionGranted)permissionLauncher.launch(Manifest.permission.CAMERA)}
    Column(Modifier.fillMaxSize()){
        CameraControls(selectedTab,d,t){selectedTab=it}
        Spacer(Modifier.height(d(16f)))
        Row(Modifier.weight(1f),horizontalArrangement=Arrangement.spacedBy(d(16f))){
            NeonPanel(Modifier.weight(.70f).fillMaxHeight()){
                when(selectedTab){
                    0->if(state.preferences.cameraMode==CameraMode.ViewerClient)RemoteCameraFeed(d,t,Modifier.fillMaxSize()){active,message->cameraActive=active;cameraMessage=message} else if(permissionGranted)LiveCameraPreview(d,t,state.preferences.cameraStreamQuality){active,message->cameraActive=active;cameraMessage=message}else CameraMessage("Camera permission required","Allow camera access to open the front camera.",d,t){permissionLauncher.launch(Manifest.permission.CAMERA)}
                    1->CameraMessage("No recordings yet","Playback will show saved camera recordings when recording storage is configured.",d,t)
                    2->CameraMessage("Timelapse not configured","Timelapse capture needs an interval and storage destination.",d,t)
                    else->CameraMessage(if(state.preferences.cameraMode==CameraMode.CameraServer)"Greenhouse Server" else "Viewer Client",if(state.preferences.cameraMode==CameraMode.CameraServer)"This phone may publish its front camera after secure streaming is configured." else "This phone is view-only and will not request camera access.",d,t)
                }
            }
            NeonPanel(Modifier.weight(.30f).fillMaxHeight()){
                Column(Modifier.padding(d(18f))){
                    Text(listOf(if(state.preferences.cameraMode==CameraMode.CameraServer)"Greenhouse Server" else "Viewer Client","Playback","Timelapse","Camera settings")[selectedTab],color=Color.White,fontSize=t(21f),fontWeight=FontWeight.Bold)
                    Spacer(Modifier.height(d(12f)))
                    val status=when(selectedTab){0->if(state.preferences.cameraMode==CameraMode.ViewerClient)when{cameraActive->"Encrypted remote feed active";cameraMessage!=null->cameraMessage!!;else->"Connecting to greenhouse…"} else when{!permissionGranted->"Permission required";cameraActive->"Front camera active";cameraMessage!=null->cameraMessage!!;else->"Starting front camera…"};1->"No saved recordings";2->"Not configured";else->if(cloudEnrollment!=null)"Automatic camera connection" else if(state.preferences.cameraMode==CameraMode.CameraServer)"Camera server settings" else "Viewer pairing settings"}
                    Text(status,color=if(cameraActive&&selectedTab==0)DashboardTokens.Green else Color(0xFFFFB23F),fontSize=t(18f))
                    Text(if(state.preferences.cameraMode==CameraMode.CameraServer)"The front camera opens only for an authorized Live View request and is encrypted before it leaves this phone." else if(cloudEnrollment!=null)"Live View uses the greenhouse server already paired to this account. No second camera scan is required." else "The viewer tries the paired local address first, then the remote router address.",color=DashboardTokens.Muted,fontSize=t(13f),modifier=Modifier.padding(top=d(12f)))
                }
            }
        }
    }
}
@Composable private fun CameraMessage(title:String,body:String,d:(Float)->androidx.compose.ui.unit.Dp,t:(Float)->androidx.compose.ui.unit.TextUnit,onClick:(()->Unit)?=null){Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){Column(horizontalAlignment=Alignment.CenterHorizontally){DashboardIconView(DashboardIcon.Camera,Color.White,Modifier.size(d(72f)));Text(title,color=Color.White,fontSize=t(22f),fontWeight=FontWeight.Bold);Text(body,color=DashboardTokens.Muted,fontSize=t(14f),textAlign=TextAlign.Center,modifier=Modifier.padding(d(12f)));if(onClick!=null)ActionButton("Allow Camera",d,t,onClick)}}}
@Composable private fun LiveCameraPreview(d:(Float)->androidx.compose.ui.unit.Dp,t:(Float)->androidx.compose.ui.unit.TextUnit,quality:CameraStreamQuality,onStatus:(Boolean,String?)->Unit){FrontCameraFeed(d,t,Modifier.fillMaxSize(),quality,onStatus)}
@Composable private fun CameraControls(active:Int,d:(Float)->androidx.compose.ui.unit.Dp,t:(Float)->androidx.compose.ui.unit.TextUnit,onSelect:(Int)->Unit){val tabs=listOf(Triple("Live View",DashboardIcon.Video,0),Triple("Playback",DashboardIcon.Play,1),Triple("Timelapse",DashboardIcon.Calendar,2),Triple("Settings",DashboardIcon.Settings,3));Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(d(16f))){tabs.forEach{(label,icon,i)->val selected=i==active;Row(Modifier.weight(1f).height(d(72f)).background(if(selected)DashboardTokens.Amber else Color(0x90001317),RoundedCornerShape(d(28f))).border(d(2f),if(selected)DashboardTokens.AmberBright else DashboardTokens.PanelEdge,RoundedCornerShape(d(28f))).clickable{onSelect(i)},horizontalArrangement=Arrangement.Center,verticalAlignment=Alignment.CenterVertically){DashboardIconView(icon,if(selected)DashboardTokens.Background else Color.White,Modifier.size(d(27f)));Spacer(Modifier.width(d(10f)));Text(label,color=if(selected)DashboardTokens.Background else Color.White,fontSize=t(18f),fontWeight=FontWeight.Bold)}}}}

private enum class SettingsCategory(val icon:DashboardIcon){General(DashboardIcon.Sliders),Connectivity(DashboardIcon.Wifi),Camera(DashboardIcon.Camera),Alerts(DashboardIcon.Bell),Devices(DashboardIcon.Signal),Display(DashboardIcon.Resize),}
@Composable private fun visibleCategories(state:GreenhouseUiState):List<SettingsCategory>{
    val context=LocalContext.current
    val role=remember(state.standalone,state.preferences.cameraMode){SecureSettingsStore(context).cloudEnrollment()?.role}
    return SettingsCategory.entries.filter {
        when(it){
            SettingsCategory.Camera -> !state.standalone && role!=CloudDeviceRole.ViewerClient
            SettingsCategory.Connectivity -> state.standalone || role==CloudDeviceRole.ViewerClient
            else -> true
        }
    }
}

@Composable private fun SettingsContent(state:GreenhouseUiState,d:(Float)->androidx.compose.ui.unit.Dp,t:(Float)->androidx.compose.ui.unit.TextUnit,onRefresh:()->Unit,onSaveKey:(String)->Unit,onAssignZone:(String,String)->Unit,onSaveZip:(String)->Unit,onWeather:()->Unit,onUpdateGreenhouse:(String,Double,Double)->Unit,onUpdatePreferences:((AppPreferences)->AppPreferences)->Unit,openSensors:Boolean=false){
    var category by remember{mutableStateOf(if(openSensors || (state.standalone && state.sensors.isEmpty())) SettingsCategory.Devices else SettingsCategory.General)}
    BoxWithConstraints(Modifier.fillMaxSize()){
        val portrait=maxHeight>maxWidth
        if(portrait){
            Column(Modifier.fillMaxSize(),verticalArrangement=Arrangement.spacedBy(d(10f))){
                SettingsMenuBar(category,visibleCategories(state),d,t){category=it}
                NeonPanel(Modifier.fillMaxWidth().weight(1f)){SettingsDetail(category,state,d,t,onRefresh,onSaveKey,onAssignZone,onSaveZip,onWeather,onUpdateGreenhouse,onUpdatePreferences)}
            }
        }else{
            Row(Modifier.fillMaxSize(),horizontalArrangement=Arrangement.spacedBy(d(18f))){
                NeonPanel(Modifier.weight(.23f).fillMaxHeight()){
                    Column(Modifier.padding(d(12f)),verticalArrangement=Arrangement.spacedBy(d(7f))){
                        visibleCategories(state).forEach{c->SettingsCategoryButton(c,c==category,false,d,t,Modifier.fillMaxWidth().weight(1f)){category=c}}
                    }
                }
                NeonPanel(Modifier.weight(.77f).fillMaxHeight()){SettingsDetail(category,state,d,t,onRefresh,onSaveKey,onAssignZone,onSaveZip,onWeather,onUpdateGreenhouse,onUpdatePreferences)}
            }
        }
    }
}

@Composable private fun SettingsMenuBar(selected:SettingsCategory,categories:List<SettingsCategory>,d:(Float)->androidx.compose.ui.unit.Dp,t:(Float)->androidx.compose.ui.unit.TextUnit,onSelect:(SettingsCategory)->Unit){
    var expanded by remember{mutableStateOf(false)}
    Box(Modifier.fillMaxWidth()){
        Row(
            Modifier.fillMaxWidth().heightIn(min=d(58f)).background(DashboardTokens.PanelRaised,RoundedCornerShape(d(18f))).border(d(1f),DashboardTokens.PanelEdge,RoundedCornerShape(d(18f))).clickable{expanded=true}.padding(horizontal=d(10f),vertical=d(7f)),
            verticalAlignment=Alignment.CenterVertically
        ){
            Box(
                Modifier.size(d(46f)).background(DashboardTokens.Amber,RoundedCornerShape(d(13f))).border(d(1f),DashboardTokens.AmberBright,RoundedCornerShape(d(13f))),
                contentAlignment=Alignment.Center
            ){HamburgerIcon(DashboardTokens.Background,Modifier.size(d(24f)))}
            Spacer(Modifier.width(d(12f)))
            DashboardIconView(selected.icon,Color.White,Modifier.size(d(22f)))
            Spacer(Modifier.width(d(9f)))
            Column(Modifier.weight(1f)){
                Text(selected.name,color=Color.White,fontSize=t(16f),fontWeight=FontWeight.ExtraBold,maxLines=1)

            }

        }
        DropdownMenu(
            expanded=expanded,
            onDismissRequest={expanded=false},
            modifier=Modifier.widthIn(min=d(270f)).background(DashboardTokens.PanelRaised)
        ){
            categories.forEach{category->
                val active=category==selected
                DropdownMenuItem(
                    text={Text(category.name,fontSize=t(15f),fontWeight=FontWeight.ExtraBold)},
                    leadingIcon={DashboardIconView(category.icon,if(active)DashboardTokens.Background else Color.White,Modifier.size(d(22f)))},
                    onClick={onSelect(category);expanded=false},
                    modifier=Modifier.background(if(active)DashboardTokens.Amber else DashboardTokens.PanelRaised),
                    colors=MenuDefaults.itemColors(textColor=if(active)DashboardTokens.Background else Color.White,leadingIconColor=if(active)DashboardTokens.Background else Color.White)
                )
            }
        }
    }
}

@Composable private fun HamburgerIcon(color:Color,modifier:Modifier=Modifier){
    Canvas(modifier){
        val stroke=(size.minDimension*.11f).coerceAtLeast(2f)
        listOf(.24f,.50f,.76f).forEach{fraction->
            drawLine(color,Offset(size.width*.14f,size.height*fraction),Offset(size.width*.86f,size.height*fraction),strokeWidth=stroke,cap=StrokeCap.Round)
        }
    }
}

@Composable private fun SettingsCategoryButton(category:SettingsCategory,selected:Boolean,compact:Boolean,d:(Float)->androidx.compose.ui.unit.Dp,t:(Float)->androidx.compose.ui.unit.TextUnit,modifier:Modifier=Modifier,onClick:()->Unit){
    Row(modifier.heightIn(min=d(48f)).widthIn(min=if(compact)d(118f) else d(0f)).background(if(selected)DashboardTokens.Amber else DashboardTokens.PanelRaised,RoundedCornerShape(d(16f))).border(d(1f),if(selected)DashboardTokens.AmberBright else DashboardTokens.PanelEdge,RoundedCornerShape(d(16f))).clickable(onClick=onClick).padding(horizontal=d(14f)),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.Center){
        DashboardIconView(category.icon,if(selected)DashboardTokens.Background else Color.White,Modifier.size(d(21f)))
        Spacer(Modifier.width(d(8f)))
        Text(category.name,color=if(selected)DashboardTokens.Background else Color.White,fontSize=t(if(compact)13f else 17f),fontWeight=FontWeight.ExtraBold,maxLines=1)
    }
}

@Composable private fun SettingsDetail(category:SettingsCategory,state:GreenhouseUiState,d:(Float)->androidx.compose.ui.unit.Dp,t:(Float)->androidx.compose.ui.unit.TextUnit,onRefresh:()->Unit,onSaveKey:(String)->Unit,onAssignZone:(String,String)->Unit,onSaveZip:(String)->Unit,onWeather:()->Unit,onUpdateGreenhouse:(String,Double,Double)->Unit,onUpdatePreferences:((AppPreferences)->AppPreferences)->Unit){
    when(category){
        SettingsCategory.General->GeneralSettings(state,d,t,onSaveZip,onWeather,onUpdateGreenhouse,onUpdatePreferences)
        SettingsCategory.Connectivity->ConnectivitySettings(state,d,t,onRefresh,onSaveKey)
        SettingsCategory.Camera->CameraRoleSettings(state,d,t,onUpdatePreferences)
        SettingsCategory.Alerts->AlertSettings(state,d,t,onUpdatePreferences)
        SettingsCategory.Devices->DevicesSettings(state,d,t,onAssignZone,onRefresh,onSaveKey)
        SettingsCategory.Display->DisplaySettings(state,d,t,onUpdatePreferences)
    }
}

@Composable private fun CameraRoleSettings(state:GreenhouseUiState,d:(Float)->androidx.compose.ui.unit.Dp,t:(Float)->androidx.compose.ui.unit.TextUnit,onPrefs:((AppPreferences)->AppPreferences)->Unit){
    val context=LocalContext.current
    val cloudEnrollment=remember{SecureSettingsStore(context).cloudEnrollment()}
    val store=remember{CameraPairingStore(context)}
    var pairing by remember{mutableStateOf(store.load())}
    var serverStatus by remember{mutableStateOf(CameraStreamServer.status)}
    var pairingMessage by remember{mutableStateOf<String?>(null)}
    val scanLauncher=rememberLauncherForActivityResult(ScanContract()){result->
        val parsed=result.contents?.let(CameraPairing::parse)
        if(parsed==null)pairingMessage="That QR code is not a Greenhouse camera pairing code"
        else{store.save(parsed);pairing=parsed;pairingMessage="Viewer paired securely";onPrefs{it.copy(cameraMode=CameraMode.ViewerClient)}}
    }
    LaunchedEffect(state.preferences.cameraMode){while(true){pairing=store.load();serverStatus=CameraStreamServer.status;kotlinx.coroutines.delay(1_000)}}
    Column(Modifier.fillMaxSize().padding(d(20f))){
        Text("Camera",color=Color.White,fontSize=t(28f),fontWeight=FontWeight.ExtraBold)
        Text(if(cloudEnrollment==null)"Local camera pairing" else "Camera access is included automatically when the greenhouse server is paired.",color=DashboardTokens.Muted,fontSize=t(17f),lineHeight=t(22f))
        Spacer(Modifier.height(d(16f)))
        if(cloudEnrollment!=null){
            val isServer=cloudEnrollment.role==CloudDeviceRole.GreenhouseServer
            SettingsRow("Assigned role"){Text(if(isServer)"Greenhouse Server" else "Authorized Client",color=DashboardTokens.Green,fontSize=t(17f),fontWeight=FontWeight.ExtraBold)}
            Spacer(Modifier.height(d(14f)))
            Column(
                Modifier.fillMaxWidth().weight(1f).background(DashboardTokens.PanelRaised,RoundedCornerShape(d(18f))).border(d(1f),DashboardTokens.PanelEdge,RoundedCornerShape(d(18f))).padding(d(20f)),
                verticalArrangement=Arrangement.spacedBy(d(12f))
            ){
                DashboardIconView(DashboardIcon.Camera,DashboardTokens.Amber,Modifier.size(d(44f)))
                Text(if(isServer)"Camera linked to this server" else "Camera linked to the paired server",color=Color.White,fontSize=t(22f),fontWeight=FontWeight.ExtraBold)
                Text(
                    if(isServer)"No separate camera QR code is needed. When an authorized client opens Live View, this phone starts an encrypted camera session automatically."
                    else "No separate camera scan is needed. Open Cameras → Live View to connect to the greenhouse server automatically.",
                    color=DashboardTokens.Muted,fontSize=t(16f),lineHeight=t(22f)
                )
                if(isServer){
                    Text("Stream quality",color=Color.White,fontSize=t(17f),fontWeight=FontWeight.Bold)
                    Row(horizontalArrangement=Arrangement.spacedBy(d(8f))){CameraStreamQuality.entries.forEach{quality->val label=when(quality){CameraStreamQuality.DataSaver->"Data saver";CameraStreamQuality.Balanced->"Balanced";CameraStreamQuality.High->"High"};ActionButton(if(state.preferences.cameraStreamQuality==quality)"$label selected" else label,d,t){onPrefs{it.copy(cameraStreamQuality=quality)}}}}
                    Text("The camera stays off until an authorized client requests Live View.",color=DashboardTokens.Green,fontSize=t(15f),fontWeight=FontWeight.Bold)
                }
            }
        }else{
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(d(14f))){
                CameraRoleCard("Greenhouse Server","Shares this phone's front camera using an encrypted, authenticated feed.",DashboardIcon.Camera,state.preferences.cameraMode==CameraMode.CameraServer,d,t,Modifier.weight(1f)){onPrefs{it.copy(cameraMode=CameraMode.CameraServer)}}
                CameraRoleCard("Viewer Client","Shows a locally paired greenhouse camera.",DashboardIcon.Wifi,state.preferences.cameraMode==CameraMode.ViewerClient,d,t,Modifier.weight(1f)){onPrefs{it.copy(cameraMode=CameraMode.ViewerClient)}}
            }
            Spacer(Modifier.height(d(14f)))
            if(state.preferences.cameraMode==CameraMode.CameraServer){
                val active=pairing
                Row(Modifier.fillMaxWidth().weight(1f),horizontalArrangement=Arrangement.spacedBy(d(18f)),verticalAlignment=Alignment.CenterVertically){
                    if(active!=null)PairingQrCode(active.pairingUri(),Modifier.size(d(180f)))
                    Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(d(7f))){
                        Text("Legacy local viewer pairing",color=Color.White,fontSize=t(19f),fontWeight=FontWeight.ExtraBold)
                        Text(serverStatus.message,color=if(serverStatus.running)DashboardTokens.Green else DashboardTokens.Amber,fontSize=t(14f))
                        Text("Local: ${serverStatus.localAddress?:active?.localHost?:"starting"}:${active?.port?:CameraStreamServer.DEFAULT_PORT}",color=DashboardTokens.Muted,fontSize=t(12f))
                        Text("Stream quality",color=Color.White,fontSize=t(13f),fontWeight=FontWeight.Bold)
                        Row(horizontalArrangement=Arrangement.spacedBy(d(7f))){CameraStreamQuality.entries.forEach{quality->val label=when(quality){CameraStreamQuality.DataSaver->"480×270 · 4 fps";CameraStreamQuality.Balanced->"640×360 · 7 fps";CameraStreamQuality.High->"1280×720 · 10 fps"};ActionButton(if(state.preferences.cameraStreamQuality==quality)"$label selected" else label,d,t){onPrefs{it.copy(cameraStreamQuality=quality)}}}}
                    }
                }
            }else{
                Column(Modifier.fillMaxWidth().weight(1f),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center){
                    DashboardIconView(DashboardIcon.Camera,DashboardTokens.Amber,Modifier.size(d(54f)))
                    Text(if(pairing==null)"No local camera paired" else "Paired to greenhouse ${pairing!!.id.take(6)}",color=Color.White,fontSize=t(19f),fontWeight=FontWeight.ExtraBold)
                    pairingMessage?.let{Text(it,color=DashboardTokens.Green,fontSize=t(13f))}
                    Spacer(Modifier.height(d(12f)))
                    ActionButton(if(pairing==null)"Scan local camera QR" else "Scan a different local camera",d,t){scanLauncher.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setPrompt("Scan the QR shown on the greenhouse phone").setBeepEnabled(false).setOrientationLocked(false))}
                }
            }
        }
    }
}

@Composable private fun CameraRoleCard(title:String,body:String,icon:DashboardIcon,selected:Boolean,d:(Float)->androidx.compose.ui.unit.Dp,t:(Float)->androidx.compose.ui.unit.TextUnit,modifier:Modifier,onClick:()->Unit){Column(modifier.height(d(180f)).clip(RoundedCornerShape(d(22f))).background(if(selected)DashboardTokens.Amber.copy(alpha=.16f) else DashboardTokens.PanelRaised).border(d(2f),if(selected)DashboardTokens.Amber else DashboardTokens.PanelEdge,RoundedCornerShape(d(22f))).clickable(onClick=onClick).padding(d(18f))){DashboardIconView(icon,if(selected)DashboardTokens.Amber else DashboardTokens.Muted,Modifier.size(d(36f)));Text(title,color=Color.White,fontSize=t(19f),fontWeight=FontWeight.ExtraBold,modifier=Modifier.padding(top=d(10f)));Text(body,color=DashboardTokens.Muted,fontSize=t(12f),modifier=Modifier.padding(top=d(7f)))}}
@Composable private fun GeneralSettings(state:GreenhouseUiState,d:(Float)->androidx.compose.ui.unit.Dp,t:(Float)->androidx.compose.ui.unit.TextUnit,onSaveZip:(String)->Unit,onWeather:()->Unit,onGreenhouse:(String,Double,Double)->Unit,onPrefs:((AppPreferences)->AppPreferences)->Unit){
    val context=LocalContext.current
    val viewer=remember(context){SecureSettingsStore(context).cloudEnrollment()?.role==CloudDeviceRole.ViewerClient}
    val g=state.preferences.activeGreenhouse
    var zip by remember(state.preferences.zipCode){mutableStateOf(state.preferences.zipCode)}
    var layoutExpanded by remember(g.id){mutableStateOf(false)}
    var greenhouseManagerExpanded by remember{mutableStateOf(false)}
    val editable=!viewer || state.standalone
    if (layoutExpanded && editable) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(d(20f)),
            verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.SpaceBetween,
                verticalAlignment=Alignment.CenterVertically) {
                Text("Floor plan", color=Color.White, fontSize=t(25f), fontWeight=FontWeight.Bold)
                TextButton(onClick={ layoutExpanded=false }) { Text("Cancel", color=DashboardTokens.Muted) }
            }
            FloorPlanEditor(g,state.sensors.filter{it.greenhouseId==g.id}){saved->
                onPrefs { prefs -> prefs.copy(greenhouses=prefs.greenhouses.map { current ->
                    if(current.id!=g.id) current else saved.copy(
                        refreshMinutes=current.refreshMinutes,
                        sensorPositions=current.sensorPositions.mapValues { (_,position)->saved.reproject(position,current) } + saved.sensorPositions
                    )
                }) }
                layoutExpanded=false
            }
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize().padding(d(20f)),verticalArrangement=Arrangement.spacedBy(d(12f))){
        item{Text(g.name,color=Color.White,fontSize=t(27f),fontWeight=FontWeight.ExtraBold)}
        if(editable && greenhouseManagerExpanded)item{SettingsRow("Greenhouses"){state.preferences.greenhouses.forEach{house->ActionButton(house.name,d,t){onPrefs{it.copy(activeGreenhouseId=house.id)};greenhouseManagerExpanded=false}};ActionButton("Add greenhouse",d,t){val id="greenhouse_${System.currentTimeMillis()}";onPrefs{it.copy(greenhouses=it.greenhouses+GreenhouseConfig(id=id,name="Greenhouse ${it.greenhouses.size+1}"),activeGreenhouseId=id)};greenhouseManagerExpanded=false;layoutExpanded=true}}}
        if(editable)item{ResponsiveActionSetting("Floor plan","${g.dimensionsLabel()} · ${g.areaLabel()}","Edit layout",d,t){layoutExpanded=!layoutExpanded}}
        if(!editable)item{SettingsRow("Floor plan"){Text("${g.dimensionsLabel()} · ${g.areaLabel()} · Edit on the greenhouse phone",color=DashboardTokens.Muted)}}
        if(editable)item{ActionButton(if(greenhouseManagerExpanded)"Close greenhouses" else "Manage greenhouses",d,t){greenhouseManagerExpanded=!greenhouseManagerExpanded}}
        item{RefreshIntervalSettingsRow(g.refreshMinutes,d,t){minutes->onPrefs{prefs->prefs.copy(greenhouses=prefs.greenhouses.map{if(it.id==g.id)it.copy(refreshMinutes=minutes)else it})}}}
        item{Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(d(10f))){OutdoorWeatherSetting(zip,{zip=it.filter(Char::isDigit).take(5)},d,t,{onSaveZip(zip)},onWeather)}}
    }
}

@Composable private fun ConnectivitySettings(state:GreenhouseUiState,d:(Float)->androidx.compose.ui.unit.Dp,t:(Float)->androidx.compose.ui.unit.TextUnit,onRefresh:()->Unit,onSave:(String)->Unit){
    LazyColumn(Modifier.fillMaxSize().padding(d(16f)),verticalArrangement=Arrangement.spacedBy(d(12f))){
        item { CloudServerPairingPanel(d,t,Modifier.fillMaxWidth()) }
        if(!state.standalone)item { ActionButton("Sync readings",d,t,onRefresh) }
    }
}

@Composable private fun AlertSettings(state:GreenhouseUiState,d:(Float)->androidx.compose.ui.unit.Dp,t:(Float)->androidx.compose.ui.unit.TextUnit,onPrefs:((AppPreferences)->AppPreferences)->Unit){val n=state.preferences.notifications;var delivery by remember{mutableStateOf(false)};LazyColumn(Modifier.fillMaxSize().padding(d(22f)),verticalArrangement=Arrangement.spacedBy(d(8f))){item{Text("Alerts & Notifications",color=Color.White,fontSize=t(25f),fontWeight=FontWeight.Bold);Text("Monitoring and history continue when notifications are off. Defaults are quiet and non-repeating.",color=DashboardTokens.Muted,fontSize=t(14f))};item{ToggleRow("Master notifications",n.masterEnabled,d,t){onPrefs{it.copy(notifications=it.notifications.copy(masterEnabled=!it.notifications.masterEnabled))}}};item{ToggleRow("Temperature alerts",n.temperatureAlerts,d,t){onPrefs{it.copy(notifications=it.notifications.copy(temperatureAlerts=!it.notifications.temperatureAlerts))}}};item{ToggleRow("Humidity alerts",n.humidityAlerts,d,t){onPrefs{it.copy(notifications=it.notifications.copy(humidityAlerts=!it.notifications.humidityAlerts))}}};item{ToggleRow("Sensor offline alerts",n.offlineAlerts,d,t){onPrefs{it.copy(notifications=it.notifications.copy(offlineAlerts=!it.notifications.offlineAlerts))}}};item{TextButton(onClick={delivery=!delivery}){Text(if(delivery)"Hide delivery options" else "Alert delivery",color=DashboardTokens.Amber)}};if(delivery)item{ToggleRow("On-screen pop-ups",n.popupsEnabled,d,t){onPrefs{it.copy(notifications=it.notifications.copy(popupsEnabled=!it.notifications.popupsEnabled))}}};if(delivery)item{ToggleRow("Sound",n.soundEnabled,d,t){onPrefs{it.copy(notifications=it.notifications.copy(soundEnabled=!it.notifications.soundEnabled))}}};if(delivery)item{ToggleRow("Vibration",n.vibrationEnabled,d,t){onPrefs{it.copy(notifications=it.notifications.copy(vibrationEnabled=!it.notifications.vibrationEnabled))}}};if(delivery)item{ToggleRow("Repeated alerts (${n.repeatMinutes} min)",n.repeatedAlerts,d,t){onPrefs{it.copy(notifications=it.notifications.copy(repeatedAlerts=!it.notifications.repeatedAlerts))}}};item{SettingsRow("Temperature range"){StepButton("−",d,t){onPrefs{it.copy(notifications=it.notifications.copy(lowTemperatureF=it.notifications.lowTemperatureF-1))}};Text("${n.lowTemperatureF.roundToInt()}–${n.highTemperatureF.roundToInt()}°F",color=Color.White,fontSize=t(16f));StepButton("+",d,t){onPrefs{it.copy(notifications=it.notifications.copy(highTemperatureF=it.notifications.highTemperatureF+1))}}}};item{SettingsRow("Humidity range"){StepButton("−",d,t){onPrefs{it.copy(notifications=it.notifications.copy(lowHumidity=(it.notifications.lowHumidity-1).coerceAtLeast(0.0)))}};Text("${n.lowHumidity.roundToInt()}–${n.highHumidity.roundToInt()}%",color=Color.White,fontSize=t(16f));StepButton("+",d,t){onPrefs{it.copy(notifications=it.notifications.copy(highHumidity=(it.notifications.highHumidity+1).coerceAtMost(100.0)))}}}};item{Row(horizontalArrangement=Arrangement.spacedBy(d(10f))){ActionButton("Snooze 1 hour",d,t){onPrefs{it.copy(notifications=it.notifications.copy(snoozedUntilEpochMs=System.currentTimeMillis()+60*60_000L))}};ActionButton("Clear snooze",d,t){onPrefs{it.copy(notifications=it.notifications.copy(snoozedUntilEpochMs=0))}}}}}}
@Composable private fun DevicesSettings(state:GreenhouseUiState,d:(Float)->androidx.compose.ui.unit.Dp,t:(Float)->androidx.compose.ui.unit.TextUnit,onAssign:(String,String)->Unit,onRefresh:()->Unit,onSave:(String)->Unit){
    val context=LocalContext.current
    val viewer=remember(state.standalone){SecureSettingsStore(context).cloudEnrollment()?.role==CloudDeviceRole.ViewerClient}
    val local=state.standalone || !viewer
    var editKey by remember(local){mutableStateOf(local && !state.goveeConnected && state.sensors.isEmpty())}
    var expanded by remember{mutableStateOf<String?>(null)}
    var managePhones by remember{mutableStateOf(false)}
    var key by remember{mutableStateOf("")}
    LazyColumn(Modifier.fillMaxSize().padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
        item{Text("Sensors",color=Color.White,fontSize=24.sp,fontWeight=FontWeight.Bold)}
        if(local)item{
            Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
                if(editKey){
                    Text("Connect your Govee H5179 sensors",color=Color.White)
                    OutlinedTextField(key,{key=it},label={Text("Govee API key")},singleLine=true,visualTransformation=PasswordVisualTransformation(),colors=darkFieldColors(),modifier=Modifier.fillMaxWidth())
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                        if(key.isNotBlank())ActionButton("Connect sensors",d,t){onSave(key.trim());key="";editKey=false}
                        TextButton(onClick={key="";editKey=false}){Text("Cancel",color=DashboardTokens.Muted)}
                    }
                }else{
                    ActionButton(if(state.goveeConnected)"Change sensor key" else "Add sensors",d,t){editKey=true}
                    if(state.goveeConnected) TextButton(onClick=onRefresh){Text("Refresh readings",color=DashboardTokens.Amber)}
                }
                state.message?.let{Text(it,color=DashboardTokens.Muted)}
            }
        }
        if(!local && state.sensors.isEmpty())item{Text("No sensors reported by the greenhouse server yet.",color=DashboardTokens.Muted)}
        items(state.sensors,key={it.id}){sensor->
            Column(Modifier.fillMaxWidth().background(DashboardTokens.PanelRaised,RoundedCornerShape(12.dp)).clickable(enabled=local){expanded=if(expanded==sensor.id)null else sensor.id}.padding(14.dp)){
                Text(sensor.name,color=Color.White,fontSize=18.sp,fontWeight=FontWeight.Bold)
                val assigned = state.preferences.greenhouses.firstOrNull { it.id == sensor.greenhouseId }
                Text(listOfNotNull(assigned?.name, sensor.zone, if(sensor.isFresh()) "Online" else "Offline").joinToString(" · "), color=DashboardTokens.Muted)
                if(local && expanded==sensor.id && sensor.greenhouseId!=state.preferences.activeGreenhouse.id) {
                    ActionButton("Assign to ${state.preferences.activeGreenhouse.name}",d,t){onAssign(sensor.id,sensor.zone);expanded=null}
                }
                if(local && expanded==sensor.id)FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)){(1..4).forEach{z->
                    ActionButton("Zone $z",d,t){onAssign(sensor.id,"Zone $z");expanded=null}
                }}
            }
        }
        if(viewer)item{TextButton(onClick={managePhones=!managePhones}){Text(if(managePhones)"Hide phones" else "Manage phones",color=DashboardTokens.Amber)}}
        if(managePhones)item{
            val secure=remember{SecureSettingsStore(context)}
            val device=secure.cloudEnrollment()
            if(device!=null && device.greenhouseId.isNotBlank())ActionButton(if(state.standalone)"Use paired server" else "Use sensors on this phone",d,t){
                secure.saveCloudEnrollment(device.copy(localSensors=!state.standalone))
                managePhones=false
            }
            CloudDeviceManagementPanel(d,t)
        }
    }
}
@Composable private fun DisplaySettings(state:GreenhouseUiState,d:(Float)->androidx.compose.ui.unit.Dp,t:(Float)->androidx.compose.ui.unit.TextUnit,onPrefs:((AppPreferences)->AppPreferences)->Unit){Column(Modifier.padding(d(22f))){Text("Display",color=Color.White,fontSize=t(25f),fontWeight=FontWeight.Bold);ToggleRow("Keep Screen On",state.preferences.keepScreenOn,d,t){onPrefs{it.copy(keepScreenOn=!it.keepScreenOn)}};ToggleRow("Auto Start (Run at Boot)",state.preferences.autoStart,d,t){onPrefs{it.copy(autoStart=!it.autoStart)}}}}
@Composable private fun SettingsRow(label:String,modifier:Modifier=Modifier,content:@Composable ()->Unit){BoxWithConstraints(modifier.fillMaxWidth().background(Color(0x55082A2E),RoundedCornerShape(16.dp)).border(1.dp,Color(0xFF397A82),RoundedCornerShape(16.dp)).padding(14.dp)){if(maxWidth<520.dp)Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(10.dp)){Text(label,color=Color.White,fontSize=18.sp,fontWeight=FontWeight.ExtraBold);FlowRow(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(8.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)){content()}}else Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Text(label,color=Color.White,fontSize=17.sp,fontWeight=FontWeight.Bold,modifier=Modifier.weight(.7f));FlowRow(Modifier.weight(1.3f),verticalArrangement=Arrangement.spacedBy(8.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)){content()}}}}

@Composable private fun ResponsiveActionSetting(label:String,value:String,actionLabel:String,d:(Float)->androidx.compose.ui.unit.Dp,t:(Float)->androidx.compose.ui.unit.TextUnit,mutedValue:Boolean=false,onAction:()->Unit){
    BoxWithConstraints(Modifier.fillMaxWidth().background(Color(0x55082A2E),RoundedCornerShape(16.dp)).border(1.dp,Color(0xFF397A82),RoundedCornerShape(16.dp)).padding(14.dp)){
        val compact=maxWidth<520.dp
        if(compact){
            Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(10.dp)){
                Text(label,color=Color.White,fontSize=18.sp,fontWeight=FontWeight.ExtraBold)
                Text(value,color=if(mutedValue)DashboardTokens.Muted else Color.White,fontSize=t(15f),fontWeight=FontWeight.Bold,lineHeight=t(20f),modifier=Modifier.fillMaxWidth())
                ActionButton(actionLabel,d,t,Modifier.fillMaxWidth(),onAction)
            }
        }else{
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(d(12f))){
                Text(label,color=Color.White,fontSize=17.sp,fontWeight=FontWeight.Bold,modifier=Modifier.weight(.7f))
                Text(value,color=if(mutedValue)DashboardTokens.Muted else Color.White,fontSize=t(15f),fontWeight=FontWeight.Bold,modifier=Modifier.weight(1f))
                ActionButton(actionLabel,d,t,onClick=onAction)
            }
        }
    }
}

@Composable private fun OutdoorWeatherSetting(zip:String,onZip:(String)->Unit,d:(Float)->androidx.compose.ui.unit.Dp,t:(Float)->androidx.compose.ui.unit.TextUnit,onSave:()->Unit,onRefresh:()->Unit){
    BoxWithConstraints(Modifier.fillMaxWidth().background(Color(0x55082A2E),RoundedCornerShape(16.dp)).border(1.dp,Color(0xFF397A82),RoundedCornerShape(16.dp)).padding(14.dp)){
        val compact=maxWidth<520.dp
        if(compact){
            Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(10.dp)){
                Text("Outdoor Weather",color=Color.White,fontSize=18.sp,fontWeight=FontWeight.ExtraBold)
                CompactField(zip,onZip,d,t,Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){
                    ActionButton("Save ZIP",d,t,Modifier.weight(1f),onSave)
                    ActionButton("Refresh",d,t,Modifier.weight(1f),onRefresh)
                }
            }
        }else{
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(d(10f))){
                Text("Outdoor Weather",color=Color.White,fontSize=17.sp,fontWeight=FontWeight.Bold,modifier=Modifier.weight(.7f))
                CompactField(zip,onZip,d,t,Modifier.weight(.8f))
                ActionButton("Save ZIP",d,t,Modifier.weight(.7f),onSave)
                ActionButton("Refresh",d,t,Modifier.weight(.7f),onRefresh)
            }
        }
    }
}
@Composable private fun RefreshIntervalSettingsRow(selectedMinutes:Int,d:(Float)->androidx.compose.ui.unit.Dp,t:(Float)->androidx.compose.ui.unit.TextUnit,onSelect:(Int)->Unit){
    var open by remember { mutableStateOf(false) }
    SettingsRow("Dashboard refresh") {
        Box {
            ActionButton(if(selectedMinutes==1)"Every minute" else "Every $selectedMinutes minutes",d,t){open=true}
            DropdownMenu(expanded=open,onDismissRequest={open=false},modifier=Modifier.background(DashboardTokens.PanelRaised)){
                DataSyncIntervals.minutes.forEach { minutes ->
                    DropdownMenuItem(text={Text(if(minutes==1)"Every minute" else "Every $minutes minutes",color=Color.White)},
                        onClick={onSelect(minutes);open=false})
                }
            }
        }
    }
}
@Composable private fun CompactField(value:String,onChange:(String)->Unit,d:(Float)->androidx.compose.ui.unit.Dp,t:(Float)->androidx.compose.ui.unit.TextUnit,modifier:Modifier){OutlinedTextField(value,onChange,singleLine=true,modifier=modifier.heightIn(min=d(58f)),textStyle=LocalTextStyle.current.copy(color=Color.White,fontSize=t(15f)),colors=darkFieldColors())}
@Composable private fun ActionButton(label:String,d:(Float)->androidx.compose.ui.unit.Dp,t:(Float)->androidx.compose.ui.unit.TextUnit,onClick:()->Unit)=ActionButton(label,d,t,Modifier,onClick)
@Composable private fun ActionButton(label:String,d:(Float)->androidx.compose.ui.unit.Dp,t:(Float)->androidx.compose.ui.unit.TextUnit,modifier:Modifier,onClick:()->Unit){val interaction=remember{MutableInteractionSource()};val pressed by interaction.collectIsPressedAsState();val pressDuration=accessibleMotionDuration(if(pressed)90 else 140);val scale by androidx.compose.animation.core.animateFloatAsState(if(pressed).98f else 1f,androidx.compose.animation.core.tween(pressDuration),label="button press");Button(onClick,interactionSource=interaction,modifier=modifier.heightIn(min=d(46f)).graphicsLayer{scaleX=scale;scaleY=scale},shape=RoundedCornerShape(d(14f)),colors=ButtonDefaults.buttonColors(containerColor=DashboardTokens.Amber,contentColor=DashboardTokens.Background),border=androidx.compose.foundation.BorderStroke(d(1f),DashboardTokens.AmberBright.copy(alpha=.75f)),contentPadding=PaddingValues(horizontal=d(16f),vertical=d(8f))){Text(label,fontSize=t(13f),fontWeight=FontWeight.ExtraBold,maxLines=1)}}
@Composable private fun StepButton(label:String,d:(Float)->androidx.compose.ui.unit.Dp,t:(Float)->androidx.compose.ui.unit.TextUnit,onClick:()->Unit){OutlinedButton(onClick,shape=RoundedCornerShape(d(14f)),border=androidx.compose.foundation.BorderStroke(d(1f),DashboardTokens.PanelEdge),contentPadding=PaddingValues(0.dp),modifier=Modifier.size(d(46f))){Text(label,color=Color.White,fontSize=t(20f),fontWeight=FontWeight.Bold)}}
@Composable private fun ToggleRow(label:String,checked:Boolean,d:(Float)->androidx.compose.ui.unit.Dp,t:(Float)->androidx.compose.ui.unit.TextUnit,onChange:(Boolean)->Unit){Row(Modifier.fillMaxWidth().padding(vertical=d(7f)),verticalAlignment=Alignment.CenterVertically){Text(label,color=Color.White,fontSize=t(17f),modifier=Modifier.weight(1f));Switch(checked,onChange,colors=SwitchDefaults.colors(checkedThumbColor=Color.White,checkedTrackColor=DashboardTokens.Amber))}}
@Composable private fun darkFieldColors()=OutlinedTextFieldDefaults.colors(focusedTextColor=Color.White,unfocusedTextColor=Color.White,focusedBorderColor=DashboardTokens.Amber,unfocusedBorderColor=DashboardTokens.PanelEdge,cursorColor=DashboardTokens.Amber)
private fun formatMetric(v:Double?,m:Metric)=when(m){Metric.Humidity->formatHumidity(v);Metric.Vpd->v?.let{"%.1f kPa".format(it)}?:"—";else->formatTemp(v)}
internal fun historyMetric(r:HistoryReading,m:Metric):Double=when(m){Metric.Temperature->r.temperatureF;Metric.Humidity->r.humidityPercent;Metric.Vpd->{val c=(r.temperatureF-32)*5/9;.6108*kotlin.math.exp(17.27*c/(c+237.3))*(1-r.humidityPercent/100)};Metric.DewPoint->{val c=(r.temperatureF-32)*5/9;val a=17.625;val b=243.04;val gamma=kotlin.math.ln(r.humidityPercent.coerceAtLeast(.1)/100)+a*c/(b+c);(b*gamma/(a-gamma))*9/5+32}}
private fun sensorColor(sensor:GoveeSensor)=sensorColor(sensor.id.hashCode())
private fun sensorColor(value:Any)=sensorColor(value.hashCode())
private fun sensorColor(index:Int)=listOf(Color(0xFFFFBF20),Color(0xFF19EF8A),Color(0xFF10BFF3),Color(0xFFAE55ED))[kotlin.math.abs(index)%4]
private fun heatColor(v:Double,min:Double,max:Double,metric:Metric):Color{val p=((v-min)/(max-min).coerceAtLeast(.01)).coerceIn(0.0,1.0).toFloat();return when{p<.25f->Color(0xFF1168EF);p<.5f->Color(0xFF00CAA5);p<.75f->Color(0xFFFFC400);else->Color(0xFFFF3858)}}
