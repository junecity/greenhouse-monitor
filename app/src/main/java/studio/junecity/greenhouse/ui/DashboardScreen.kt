package studio.junecity.greenhouse.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import studio.junecity.greenhouse.data.SpatialInterpolator
import studio.junecity.greenhouse.model.*
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

private val ProAmber=DashboardTokens.Amber
private val ProBlue=DashboardTokens.Blue
private val ProGreen=DashboardTokens.Green
private val ProPanel=DashboardTokens.Panel
private val ProEdge=DashboardTokens.PanelEdge

@Composable fun DashboardScreen(state:GreenhouseUiState,status:DeviceStatus,now:LocalDateTime,onNavigate:(AppScreen)->Unit,modifier:Modifier=Modifier){
    val context=LocalContext.current
    val cardEntranceDuration=accessibleMotionDuration(AppMotion.CardEntranceMs)
    var entered by remember{mutableStateOf(false)}
    LaunchedEffect(Unit){entered=true}
    val contentAlpha by animateFloatAsState(if(entered)1f else 0f,tween(cardEntranceDuration,easing=FastOutSlowInEasing),label="dashboard cards")
    var cameraPermission by remember{mutableStateOf(ContextCompat.checkSelfPermission(context,Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED)}
    val cameraPermissionLauncher=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){cameraPermission=it}
    LaunchedEffect(state.preferences.cameraMode){if(state.preferences.cameraMode==CameraMode.CameraServer&&!cameraPermission)cameraPermissionLauncher.launch(Manifest.permission.CAMERA)}
    ApprovedFrame(AppScreen.Dashboard,status,now,onNavigate,modifier,showTitle=false){d,t->
        val greenhouse=state.preferences.activeGreenhouse
        val fresh=state.sensors.filter{it.greenhouseId==greenhouse.id&&it.isFresh()}
        val temperature=SpatialInterpolator.areaAverage(SpatialInterpolator.samples(fresh,greenhouse,Metric.Temperature),greenhouse)
        val humidity=SpatialInterpolator.areaAverage(SpatialInterpolator.samples(fresh,greenhouse,Metric.Humidity),greenhouse)
        val vpd=SpatialInterpolator.areaAverage(SpatialInterpolator.samples(fresh,greenhouse,Metric.Vpd),greenhouse)
        val dewPoint=SpatialInterpolator.areaAverage(SpatialInterpolator.samples(fresh,greenhouse,Metric.DewPoint),greenhouse)
        Row(Modifier.fillMaxSize().alpha(contentAlpha).graphicsLayer{translationY=(1f-contentAlpha)*d(8f).toPx()},horizontalArrangement=Arrangement.spacedBy(d(14f))){
            Row(Modifier.weight(.59f).fillMaxHeight(),horizontalArrangement=Arrangement.spacedBy(d(14f))){
                Column(Modifier.weight(.46f),verticalArrangement=Arrangement.spacedBy(d(12f))){
                    WeatherCard(state,d,t,Modifier.weight(.38f))
                    Row(Modifier.weight(.31f),horizontalArrangement=Arrangement.spacedBy(d(12f))){MiniMetric("Avg Temp",formatTemp(temperature),DashboardIcon.Thermometer,ProAmber,d,t,Modifier.weight(1f));MiniMetric("Avg RH",formatHumidity(humidity),DashboardIcon.Droplet,ProBlue,d,t,Modifier.weight(1f))}
                    Row(Modifier.weight(.31f),horizontalArrangement=Arrangement.spacedBy(d(12f))){MiniMetric("VPD",vpd?.let{"%.1f kPa".format(it)}?:"—",DashboardIcon.Gauge,Color(0xFF8EEB75),d,t,Modifier.weight(1f));MiniMetric("Dew Point",formatTemp(dewPoint),DashboardIcon.Droplet,ProBlue,d,t,Modifier.weight(1f))}
                }
                ClimateGauge(greenhouse.name,temperature,humidity,d,t,Modifier.weight(.54f).fillMaxHeight())
            }
            Column(Modifier.weight(.41f).fillMaxHeight(),verticalArrangement=Arrangement.spacedBy(d(14f))){
                ProPanel(Modifier.weight(.56f).fillMaxWidth()){
                    if(state.preferences.cameraMode==CameraMode.ViewerClient)RemoteCameraFeed(d,t,Modifier.fillMaxSize())
                    else if(cameraPermission)FrontCameraFeed(d,t,Modifier.fillMaxSize(),state.preferences.cameraStreamQuality)
                    else Box(Modifier.fillMaxSize().clickable{cameraPermissionLauncher.launch(Manifest.permission.CAMERA)},contentAlignment=Alignment.Center){Column(horizontalAlignment=Alignment.CenterHorizontally){DashboardIconView(DashboardIcon.Camera,ProAmber,Modifier.size(d(54f)));Text("Enable front camera",color=Color.White,fontSize=t(16f));Text("Tap to allow camera access",color=DashboardTokens.Muted,fontSize=t(11f))}}
                }
                SensorCard(fresh,d,t,Modifier.weight(.44f).fillMaxWidth())
            }
        }
    }
}

@Composable private fun WeatherCard(state:GreenhouseUiState,d:(Float)->androidx.compose.ui.unit.Dp,t:(Float)->androidx.compose.ui.unit.TextUnit,modifier:Modifier){val icon=when{state.weather.condition.contains("storm",true)||state.weather.condition.contains("thunder",true)->DashboardIcon.Storm;state.weather.condition.contains("snow",true)->DashboardIcon.Snow;state.weather.condition.contains("rain",true)||state.weather.condition.contains("drizzle",true)->DashboardIcon.Rain;state.weather.condition.contains("clear",true)->DashboardIcon.Sun;state.weather.condition.contains("cloud",true)->DashboardIcon.CloudSun;else->DashboardIcon.Cloud};ProPanel(modifier){Row(Modifier.fillMaxSize().padding(horizontal=d(18f)),verticalAlignment=Alignment.CenterVertically){DashboardIconView(icon,ProAmber,Modifier.size(d(65f)));Spacer(Modifier.width(d(14f)));Column(Modifier.weight(.9f)){Text("Outdoor",color=Color.White,fontSize=t(17f),fontWeight=FontWeight.Bold,maxLines=1);Text(formatTemp(state.weather.temperatureF),color=Color.White,fontSize=t(34f),fontWeight=FontWeight.Black,maxLines=1)};Box(Modifier.width(d(1f)).fillMaxHeight(.62f).background(ProEdge));Spacer(Modifier.width(d(16f)));Column(Modifier.weight(1.1f)){Text(state.weather.location,color=DashboardTokens.Muted,fontSize=t(13f),maxLines=1);Text(state.weather.condition,color=Color.White,fontSize=t(16f),fontWeight=FontWeight.Bold,maxLines=1)}}}}

@Composable private fun MiniMetric(label:String,value:String,icon:DashboardIcon,color:Color,d:(Float)->androidx.compose.ui.unit.Dp,t:(Float)->androidx.compose.ui.unit.TextUnit,modifier:Modifier){ProPanel(modifier){Row(Modifier.fillMaxSize().padding(d(16f)),verticalAlignment=Alignment.CenterVertically){DashboardIconView(icon,color,Modifier.size(d(52f)));Spacer(Modifier.width(d(12f)));Column{Text(label,color=DashboardTokens.Muted,fontSize=t(12f),fontWeight=FontWeight.Bold,maxLines=1);Text(value,color=Color.White,fontSize=t(27f),fontWeight=FontWeight.Black,maxLines=1)}}}}

@Composable private fun ClimateGauge(name:String,temp:Double?,humidity:Double?,d:(Float)->androidx.compose.ui.unit.Dp,t:(Float)->androidx.compose.ui.unit.TextUnit,modifier:Modifier){
    val target=(((temp?:40.0)-40)/60).coerceIn(0.0,1.0).toFloat()
    val progress by animateFloatAsState(target,tween(accessibleMotionDuration(AppMotion.GaugeSweepMs),easing=FastOutSlowInEasing),label="temperature gauge")
    ProPanel(modifier){Column(Modifier.fillMaxSize().padding(d(16f)),horizontalAlignment=Alignment.CenterHorizontally){Box(Modifier.weight(1f).aspectRatio(1f)){Canvas(Modifier.fillMaxSize()){val stroke=size.minDimension*.055f;drawArc(Color(0xFF292C30),135f,270f,false,style=Stroke(stroke,cap=StrokeCap.Round));drawArc(ProAmber.copy(alpha=.10f),135f,270f*progress,false,style=Stroke(stroke*2.5f,cap=StrokeCap.Round));drawArc(DashboardTokens.AmberBright,135f,270f*progress,false,style=Stroke(stroke,cap=StrokeCap.Round))};Column(Modifier.align(Alignment.Center),horizontalAlignment=Alignment.CenterHorizontally){DashboardIconView(DashboardIcon.House,Color(0xFF8EEB75),Modifier.size(d(30f)));Text(name,color=Color.White,fontSize=t(18f),fontWeight=FontWeight.ExtraBold,maxLines=1);Text(formatTemp(temp),color=Color.White,fontSize=t(60f),fontWeight=FontWeight.Black,maxLines=1);Text("${formatHumidity(humidity)} RH",color=Color.White,fontSize=t(21f),fontWeight=FontWeight.Bold,maxLines=1)}};Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceEvenly){ClimateMode(DashboardIcon.Flame,"HEAT",d,t);ClimateMode(DashboardIcon.Snowflake,"COOL",d,t);ClimateMode(DashboardIcon.Fan,"FAN",d,t)}}}
}

@Composable private fun ClimateMode(icon:DashboardIcon,label:String,d:(Float)->androidx.compose.ui.unit.Dp,t:(Float)->androidx.compose.ui.unit.TextUnit){Column(horizontalAlignment=Alignment.CenterHorizontally){Box(Modifier.size(d(48f)).background(Color(0xFF202327),CircleShape).border(d(1f),ProEdge,CircleShape),contentAlignment=Alignment.Center){DashboardIconView(icon,DashboardTokens.MutedDark,Modifier.size(d(24f)))};Text(label,color=DashboardTokens.MutedDark,fontSize=t(11f),fontWeight=FontWeight.Bold)}}

@Composable private fun SensorCard(sensors:List<GoveeSensor>,d:(Float)->androidx.compose.ui.unit.Dp,t:(Float)->androidx.compose.ui.unit.TextUnit,modifier:Modifier){var selected by remember{mutableStateOf<String?>(sensors.firstOrNull()?.id)};val selectionDuration=accessibleMotionDuration(AppMotion.SensorSelectionMs);ProPanel(modifier){Column(Modifier.fillMaxSize().padding(d(16f))){Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text("Sensors (${sensors.size})",color=Color.White,fontSize=t(18f),fontWeight=FontWeight.ExtraBold);Text("Live readings",color=DashboardTokens.Muted,fontSize=t(10f))};Spacer(Modifier.height(d(8f)));if(sensors.isEmpty())Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){Text("Connect H5179 sensors in Settings",color=DashboardTokens.Muted,fontSize=t(13f))}else LazyColumn(Modifier.fillMaxSize(),verticalArrangement=Arrangement.spacedBy(d(7f))){items(sensors,key={it.id}){sensor->val emphasis by animateFloatAsState(if(sensor.id==selected)1f else 0f,tween(selectionDuration),label="sensor selection");Row(Modifier.height(d(54f)).fillMaxWidth().clip(RoundedCornerShape(d(18f))).background(Color(0xFF1E2023).copy(alpha=.82f+emphasis*.18f)).border(d(1f),if(emphasis>.5f)ProAmber.copy(alpha=.55f) else ProEdge,RoundedCornerShape(d(18f))).clickable{selected=sensor.id}.padding(horizontal=d(14f)),verticalAlignment=Alignment.CenterVertically){Box(Modifier.size(d(10f)).background(if(sensor.online)ProGreen else DashboardTokens.MutedDark,CircleShape));Spacer(Modifier.width(d(12f)));Column(Modifier.weight(1f)){Text(sensor.zone,color=Color.White,fontSize=t(13f),fontWeight=FontWeight.ExtraBold,maxLines=1);Text(sensor.name,color=DashboardTokens.Muted,fontSize=t(9f),maxLines=1)};Text(formatTemp(sensor.temperatureF),color=Color.White,fontSize=t(16f),fontWeight=FontWeight.Black);Spacer(Modifier.width(d(22f)));Text(formatHumidity(sensor.humidityPercent),color=Color.White,fontSize=t(16f),fontWeight=FontWeight.Black)}}}}}}

@Composable fun ApprovedFrame(current:AppScreen,status:DeviceStatus,now:LocalDateTime,onNavigate:(AppScreen)->Unit,modifier:Modifier=Modifier,showTitle:Boolean=true,content:@Composable ((Float)->androidx.compose.ui.unit.Dp,(Float)->androidx.compose.ui.unit.TextUnit)->Unit){val systemFontScale=LocalDensity.current.fontScale.coerceAtLeast(1f);BoxWithConstraints(modifier.background(Brush.radialGradient(listOf(Color(0xFF1C1D1F),Color(0xFF08090A)),radius=1500f))){val densityScale=minOf(maxWidth.value/800f,maxHeight.value/360f).coerceIn(.85f,1.30f);val geometryScale=.43f*densityScale;val textScale=(geometryScale*2.05f).coerceIn(.88f,1.22f);val d:(Float)->androidx.compose.ui.unit.Dp={(it*geometryScale).dp};val t:(Float)->androidx.compose.ui.unit.TextUnit={(it*textScale/systemFontScale).sp};ProvideTextStyle(LocalTextStyle.current.copy(fontWeight=FontWeight.Bold)){Column(Modifier.fillMaxSize().padding(horizontal=d(4f),vertical=d(6f))){Box(Modifier.fillMaxWidth().height(d(76f))){if(showTitle)Text(current.name,color=Color.White,fontSize=t(22f),fontWeight=FontWeight.ExtraBold,modifier=Modifier.align(Alignment.CenterStart).padding(start=d(28f)));Row(Modifier.align(Alignment.CenterEnd).padding(end=d(20f)),verticalAlignment=Alignment.CenterVertically){DashboardIconView(DashboardIcon.Wifi,ProGreen,Modifier.size(d(34f)));Text(if(status.wifiConnected)" Online" else " Offline",color=if(status.wifiConnected)ProGreen else ProAmber,fontSize=t(18f));Box(Modifier.padding(horizontal=d(22f)).width(d(1f)).height(d(42f)).background(ProEdge));Text(now.format(DateTimeFormatter.ofPattern("MMM d, h:mm a")),color=Color.White,fontSize=t(18f))}};Box(Modifier.weight(1f).fillMaxWidth()){content(d,t)};Spacer(Modifier.height(d(6f)));AppBottomNavigation(current,d,t,onNavigate,Modifier.height(d(112f)).fillMaxWidth())}}}}

@Composable fun AppBottomNavigation(current:AppScreen,d:(Float)->androidx.compose.ui.unit.Dp,t:(Float)->androidx.compose.ui.unit.TextUnit,onNavigate:(AppScreen)->Unit,modifier:Modifier=Modifier){val items=listOf(Triple(DashboardIcon.House,"Dashboard",AppScreen.Dashboard),Triple(DashboardIcon.Layers,"Zones",AppScreen.Zones),Triple(DashboardIcon.ChartBars,"History",AppScreen.History),Triple(DashboardIcon.Camera,"Cameras",AppScreen.Cameras),Triple(DashboardIcon.Settings,"Settings",AppScreen.Settings));val selectionDuration=accessibleMotionDuration(AppMotion.SensorSelectionMs);Row(modifier.clip(RoundedCornerShape(d(36f))).background(Color(0xF516181B)).border(d(1f),ProEdge,RoundedCornerShape(d(36f))).padding(d(14f)),verticalAlignment=Alignment.CenterVertically){items.forEach{(icon,label,screen)->val active=screen==current;val color by androidx.compose.animation.animateColorAsState(if(active)ProAmber else Color.White,animationSpec=androidx.compose.animation.core.tween(selectionDuration),label="nav icon color");val scale by androidx.compose.animation.core.animateFloatAsState(if(active)1.04f else 1f,animationSpec=androidx.compose.animation.core.tween(selectionDuration),label="nav icon scale");Row(Modifier.weight(1f).fillMaxHeight().background(if(active)ProAmber.copy(alpha=.12f) else Color.Transparent,RoundedCornerShape(d(28f))).clickable{onNavigate(screen)},horizontalArrangement=Arrangement.Center,verticalAlignment=Alignment.CenterVertically){DashboardIconView(icon,color,Modifier.size(d(34f)).graphicsLayer{scaleX=scale;scaleY=scale});Spacer(Modifier.width(d(13f)));Text(label,color=color,fontSize=t(17f),fontWeight=FontWeight.Bold)}}}}

@Composable fun NeonPanel(modifier:Modifier,content:@Composable BoxScope.()->Unit)=Box(modifier.clip(RoundedCornerShape(22.dp)).background(Color(0xF216181B),RoundedCornerShape(22.dp)).border(1.dp,ProEdge,RoundedCornerShape(22.dp)),content=content)
@Composable private fun ProPanel(modifier:Modifier,content:@Composable BoxScope.()->Unit)=Box(modifier.clip(RoundedCornerShape(22.dp)).background(Brush.linearGradient(listOf(Color(0xFF1E2023),ProPanel)),RoundedCornerShape(22.dp)).border(1.dp,ProEdge,RoundedCornerShape(22.dp)),content=content)
fun formatTemp(value:Double?)=value?.roundToInt()?.let{"$it°F"}?:"—"
fun formatHumidity(value:Double?)=value?.roundToInt()?.let{"$it%"}?:"—"
