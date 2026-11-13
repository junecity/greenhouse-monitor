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

@Composable fun DashboardScreen(state:GreenhouseUiState,status:DeviceStatus,now:LocalDateTime,onNavigate:(AppScreen)->Unit,modifier:Modifier=Modifier,onManageSensors:()->Unit={onNavigate(AppScreen.Settings)},onRefresh:()->Unit={},onForecast:()->Unit={},heatingSummary:String?=null,onHeating:(()->Unit)?=null){
    val context=LocalContext.current
    val cardEntranceDuration=accessibleMotionDuration(AppMotion.CardEntranceMs)
    var entered by remember{mutableStateOf(false)}
    LaunchedEffect(Unit){entered=true}
    val contentAlpha by animateFloatAsState(if(entered)1f else 0f,tween(cardEntranceDuration,easing=FastOutSlowInEasing),label="dashboard cards")
    var cameraPermission by remember{mutableStateOf(ContextCompat.checkSelfPermission(context,Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED)}
    val cameraPermissionLauncher=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){cameraPermission=it}
    LaunchedEffect(state.preferences.cameraMode,state.standalone){if(!state.standalone&&state.preferences.cameraMode==CameraMode.CameraServer&&!cameraPermission)cameraPermissionLauncher.launch(Manifest.permission.CAMERA)}
    ApprovedFrame(AppScreen.Dashboard,status,now,onNavigate,modifier,showTitle=false,standalone=state.standalone){d,t->
        val greenhouse=state.preferences.activeGreenhouse
        val assigned = state.sensors.filter { it.greenhouseId == greenhouse.id }
        val fresh = assigned.filter { it.isFresh() }
        val temperature=SpatialInterpolator.greenhouseAverage(fresh,greenhouse,Metric.Temperature)
        val humidity=SpatialInterpolator.greenhouseAverage(fresh,greenhouse,Metric.Humidity)
        val vpd=SpatialInterpolator.greenhouseAverage(fresh,greenhouse,Metric.Vpd)
        val dewPoint=SpatialInterpolator.greenhouseAverage(fresh,greenhouse,Metric.DewPoint)
        Row(Modifier.fillMaxSize().alpha(contentAlpha).graphicsLayer{translationY=(1f-contentAlpha)*d(8f).toPx()},horizontalArrangement=Arrangement.spacedBy(d(14f))){
            Row(Modifier.weight(.59f).fillMaxHeight(),horizontalArrangement=Arrangement.spacedBy(d(14f))){
                Column(Modifier.weight(.46f),verticalArrangement=Arrangement.spacedBy(d(12f))){
                    WeatherCard(state,d,t,heatingSummary,onHeating,Modifier.weight(.38f).clickable(role=androidx.compose.ui.semantics.Role.Button, onClickLabel="Open seven-day forecast", onClick=onForecast))
                    Row(Modifier.weight(.31f),horizontalArrangement=Arrangement.spacedBy(d(12f))){MiniMetric("Avg Temp",formatTemp(temperature),DashboardIcon.Thermometer,ProAmber,d,t,Modifier.weight(1f));MiniMetric("Avg RH",formatHumidity(humidity),DashboardIcon.Droplet,ProBlue,d,t,Modifier.weight(1f))}
                    Row(Modifier.weight(.31f),horizontalArrangement=Arrangement.spacedBy(d(12f))){MiniMetric("VPD",vpd?.let{"%.1f kPa".format(it)}?:"—",DashboardIcon.Gauge,Color(0xFF8EEB75),d,t,Modifier.weight(1f));MiniMetric("Dew Point",formatTemp(dewPoint),DashboardIcon.Droplet,ProBlue,d,t,Modifier.weight(1f))}
                }
                ClimateGauge(greenhouse.name,temperature,humidity,d,t,Modifier.weight(.54f).fillMaxHeight())
            }
            Column(Modifier.weight(.41f).fillMaxHeight(),verticalArrangement=Arrangement.spacedBy(d(14f))){
                ProPanel(Modifier.weight(.56f).fillMaxWidth()){Box(Modifier.fillMaxSize().dashboardLensClearance(curved=true)){
                    if(state.standalone)Box(Modifier.fillMaxSize().clickable{onNavigate(AppScreen.Settings)},contentAlignment=Alignment.Center){Text(if(state.sensors.isEmpty())"Add sensors" else "Manage greenhouse",color=ProAmber,fontSize=t(22f),fontWeight=FontWeight.Bold)}
                    else if(state.preferences.cameraMode==CameraMode.ViewerClient)RemoteCameraFeed(d,t,Modifier.fillMaxSize())
                    else if(cameraPermission)FrontCameraFeed(d,t,Modifier.fillMaxSize(),state.preferences.cameraStreamQuality)
                    else Box(Modifier.fillMaxSize().clickable{cameraPermissionLauncher.launch(Manifest.permission.CAMERA)},contentAlignment=Alignment.Center){Column(horizontalAlignment=Alignment.CenterHorizontally){DashboardIconView(DashboardIcon.Camera,ProAmber,Modifier.size(d(54f)));Text("Enable front camera",color=Color.White,fontSize=t(16f));Text("Tap to allow camera access",color=DashboardTokens.Muted,fontSize=t(11f))}}
                }}
                val viewer = !state.standalone && state.preferences.cameraMode == CameraMode.ViewerClient
                val otherHouses = state.preferences.greenhouses.filter { house -> state.sensors.any { it.greenhouseId == house.id } }
                val emptyText = when {
                    viewer -> "Waiting for readings from the server"
                    otherHouses.isNotEmpty() -> "Sensors assigned to " + otherHouses.joinToString { it.name }
                    state.sensors.any { it.greenhouseId.isEmpty() } -> "Choose a greenhouse for your sensors in Devices"
                    state.goveeConnected -> "No H5179 sensors discovered"
                    else -> "Connect your Govee H5179 sensors"
                }
                SensorCard(assigned, emptyText, if (viewer) "Sync from server" else "Manage sensors",
                    if (viewer) onRefresh else onManageSensors, d, t, Modifier.weight(.44f).fillMaxWidth())
            }
        }
    }
}

@Composable private fun WeatherCard(state:GreenhouseUiState,d:(Float)->androidx.compose.ui.unit.Dp,t:(Float)->androidx.compose.ui.unit.TextUnit,heatingSummary:String?,onHeating:(()->Unit)?,modifier:Modifier) {
    val weather=state.weather
    val icon = forecastIcon(weather.condition)
    ProPanel(modifier) {
        BoxWithConstraints(Modifier.fillMaxSize().dashboardLensClearance(curved=true).padding(horizontal=d(14f))) {
            val compact=maxWidth < 190.dp
            Row(Modifier.fillMaxSize(),verticalAlignment=Alignment.CenterVertically) {
                if(!compact) {
                    DashboardIconView(icon,ProAmber,Modifier.size(d(52f)))
                    Spacer(Modifier.width(d(10f)))
                }
                Column(Modifier.weight(.83f)) {
                    Text("Outdoor",color=Color.White,fontSize=t(if(compact) 14f else 17f),fontWeight=FontWeight.Bold)
                    Text(formatTemp(weather.temperatureF),color=Color.White,fontSize=t(if(compact) 25f else 34f),fontWeight=FontWeight.Black,maxLines=1)
                    if (onHeating != null) Text(heatingSummary?.let { "Est. $it" } ?: "Heating costs",
                        modifier = Modifier.clickable(role = androidx.compose.ui.semantics.Role.Button,
                            onClickLabel = "Open nighttime heating forecast", onClick = onHeating).padding(vertical = d(3f)),
                        color = ProAmber, fontSize = t(if (compact) 11f else 12f), fontWeight = FontWeight.Bold)
                }
                Box(Modifier.width(d(1f)).fillMaxHeight(.62f).background(ProEdge))
                Spacer(Modifier.width(d(12f)))
                Column(Modifier.weight(1.17f)) {
                    Text(weather.location,color=DashboardTokens.Muted,fontSize=t(if(compact) 12f else 13f),maxLines=2)
                    Text(weather.condition,color=Color.White,fontSize=t(if(compact) 13f else 16f),fontWeight=FontWeight.Bold,maxLines=2)
                    Spacer(Modifier.height(d(4f)))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        DashboardIconView(DashboardIcon.Calendar, ProAmber, Modifier.size(d(14f)), contentDescription = null)
                        Spacer(Modifier.width(d(4f)))
                        Text("Forecast", color = ProAmber, fontSize = t(if (compact) 12f else 14f),
                            fontWeight = FontWeight.Bold, maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable private fun MiniMetric(label:String,value:String,icon:DashboardIcon,color:Color,d:(Float)->androidx.compose.ui.unit.Dp,t:(Float)->androidx.compose.ui.unit.TextUnit,modifier:Modifier) {
    ProPanel(modifier) {
        BoxWithConstraints(Modifier.fillMaxSize().dashboardLensClearance(curved=true).padding(d(16f))) {
            val compact = maxWidth < d(142f)
            Row(Modifier.fillMaxSize(), verticalAlignment=Alignment.CenterVertically) {
                if (!compact) {
                    DashboardIconView(icon,color,Modifier.size(d(52f)))
                    Spacer(Modifier.width(d(12f)))
                }
                Column {
                    Text(label,color=DashboardTokens.Muted,fontSize=t(12f),fontWeight=FontWeight.Bold,
                        maxLines=if(compact) 2 else 1, lineHeight=t(14f))
                    Text(value,color=Color.White,fontSize=t(27f),fontWeight=FontWeight.Black,maxLines=1,
                        autoSize=if(compact) androidx.compose.foundation.text.TextAutoSize.StepBased(
                            minFontSize=12.sp, maxFontSize=t(27f).value.coerceAtLeast(12f).sp) else null)
                }
            }
        }
    }
}

@Composable private fun ClimateGauge(name:String,temp:Double?,humidity:Double?,d:(Float)->androidx.compose.ui.unit.Dp,t:(Float)->androidx.compose.ui.unit.TextUnit,modifier:Modifier){
    val target=(((temp?:40.0)-40)/60).coerceIn(0.0,1.0).toFloat()
    val progress by animateFloatAsState(target,tween(accessibleMotionDuration(AppMotion.GaugeSweepMs),easing=FastOutSlowInEasing),label="temperature gauge")
    ProPanel(modifier){Column(Modifier.fillMaxSize().padding(d(16f)),horizontalAlignment=Alignment.CenterHorizontally){Box(Modifier.weight(1f).aspectRatio(1f)){Canvas(Modifier.fillMaxSize()){val stroke=size.minDimension*.055f;drawArc(Color(0xFF292C30),135f,270f,false,style=Stroke(stroke,cap=StrokeCap.Round));drawArc(ProAmber.copy(alpha=.10f),135f,270f*progress,false,style=Stroke(stroke*2.5f,cap=StrokeCap.Round));drawArc(DashboardTokens.AmberBright,135f,270f*progress,false,style=Stroke(stroke,cap=StrokeCap.Round))};Column(Modifier.align(Alignment.Center),horizontalAlignment=Alignment.CenterHorizontally){DashboardIconView(DashboardIcon.House,Color(0xFF8EEB75),Modifier.size(d(30f)));Text(name,color=Color.White,fontSize=t(18f),fontWeight=FontWeight.ExtraBold,maxLines=1);Text(formatTemp(temp),color=Color.White,fontSize=t(60f),fontWeight=FontWeight.Black,maxLines=1);Text("${formatHumidity(humidity)} RH",color=Color.White,fontSize=t(21f),fontWeight=FontWeight.Bold,maxLines=1)}};Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceEvenly){ClimateMode(DashboardIcon.Flame,"HEAT",d,t);ClimateMode(DashboardIcon.Snowflake,"COOL",d,t);ClimateMode(DashboardIcon.Fan,"FAN",d,t)}}}
}

@Composable private fun ClimateMode(icon:DashboardIcon,label:String,d:(Float)->androidx.compose.ui.unit.Dp,t:(Float)->androidx.compose.ui.unit.TextUnit){Column(horizontalAlignment=Alignment.CenterHorizontally){Box(Modifier.size(d(48f)).background(Color(0xFF202327),CircleShape).border(d(1f),ProEdge,CircleShape),contentAlignment=Alignment.Center){DashboardIconView(icon,DashboardTokens.MutedDark,Modifier.size(d(24f)))};Text(label,color=DashboardTokens.MutedDark,fontSize=t(11f),fontWeight=FontWeight.Bold)}}

@Composable private fun SensorCard(
    sensors: List<GoveeSensor>, emptyText: String, action: String, onAction: () -> Unit,
    d: (Float) -> androidx.compose.ui.unit.Dp, t: (Float) -> androidx.compose.ui.unit.TextUnit,
    modifier: Modifier
) {
    var selected by remember { mutableStateOf(sensors.firstOrNull()?.id) }
    val duration = accessibleMotionDuration(AppMotion.SensorSelectionMs)
    val live = sensors.count { it.isFresh() }
    ProPanel(modifier) {
        Column(Modifier.fillMaxSize().dashboardLensClearance(curved=true).padding(d(16f))) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Sensors (${sensors.size})", color=Color.White, fontSize=t(18f), fontWeight=FontWeight.ExtraBold)
                val status = when {
                    sensors.isEmpty() -> ""
                    live == sensors.size -> "Live readings"
                    else -> "$live live"
                }
                Text(status, color=DashboardTokens.Muted, fontSize=t(10f))
            }
            Spacer(Modifier.height(d(8f)))
            if (sensors.isEmpty()) {
                Column(Modifier.fillMaxSize(), horizontalAlignment=Alignment.CenterHorizontally, verticalArrangement=Arrangement.Center) {
                    Text(emptyText, color=DashboardTokens.Muted, fontSize=t(13f), textAlign=TextAlign.Center)
                    androidx.compose.material3.TextButton(onClick=onAction) { Text(action, color=ProAmber, fontSize=t(14f)) }
                }
            } else LazyColumn(Modifier.fillMaxSize(), verticalArrangement=Arrangement.spacedBy(d(7f))) {
                items(sensors, key={it.id}) { sensor ->
                    val fresh = sensor.isFresh()
                    val emphasis by animateFloatAsState(if(sensor.id==selected) 1f else 0f, tween(duration), label="sensor selection")
                    val shape = RoundedCornerShape(d(18f))
                    Row(Modifier.heightIn(min=d(54f)).fillMaxWidth().clip(shape)
                        .background(Color(0xFF1E2023).copy(alpha=.82f+emphasis*.18f))
                        .border(d(1f), if(emphasis>.5f) ProAmber.copy(alpha=.55f) else ProEdge, shape)
                        .clickable { selected=sensor.id }.padding(horizontal=d(14f)), verticalAlignment=Alignment.CenterVertically) {
                        Box(Modifier.size(d(10f)).background(if(fresh) ProGreen else DashboardTokens.MutedDark, CircleShape))
                        Spacer(Modifier.width(d(12f)))
                        Column(Modifier.weight(1f)) {
                            Text(sensor.zone, color=Color.White, fontSize=t(13f), fontWeight=FontWeight.ExtraBold, maxLines=1)
                            val label = when {
                                fresh -> sensor.name
                                sensor.updatedAtEpochMs <= 0 -> "${sensor.name} · No reading yet"
                                else -> "${sensor.name} · Last read ${((System.currentTimeMillis()-sensor.updatedAtEpochMs)/60_000).coerceAtLeast(0)}m ago"
                            }
                            Text(label, color=DashboardTokens.Muted, fontSize=t(10f))
                        }
                        val color = if(fresh) Color.White else DashboardTokens.Muted
                        Text(formatTemp(sensor.temperatureF), color=color, fontSize=t(16f), fontWeight=FontWeight.Black)
                        Spacer(Modifier.width(d(22f)))
                        Text(formatHumidity(sensor.humidityPercent), color=color, fontSize=t(16f), fontWeight=FontWeight.Black)
                    }
                }
            }
        }
    }
}

@Composable fun ApprovedFrame(current:AppScreen,status:DeviceStatus,now:LocalDateTime,onNavigate:(AppScreen)->Unit,modifier:Modifier=Modifier,showTitle:Boolean=true,standalone:Boolean=false,content:@Composable ((Float)->androidx.compose.ui.unit.Dp,(Float)->androidx.compose.ui.unit.TextUnit)->Unit){val systemFontScale=LocalDensity.current.fontScale.coerceAtLeast(1f);BoxWithConstraints(modifier.background(Brush.radialGradient(listOf(Color(0xFF1C1D1F),Color(0xFF08090A)),radius=1500f)).then(if(current==AppScreen.Dashboard) Modifier else Modifier.displayCutoutPadding())){val portrait=maxHeight>maxWidth;val geometryScale=if(portrait)(maxWidth.value/430f).coerceIn(.82f,1.05f) else .43f*minOf(maxWidth.value/800f,maxHeight.value/360f).coerceIn(.85f,1.30f);val textScale=if(portrait)(maxWidth.value/390f).coerceIn(.92f,1.12f) else (geometryScale*2.05f).coerceIn(.88f,1.22f);val d:(Float)->androidx.compose.ui.unit.Dp={(it*geometryScale).dp};val t:(Float)->androidx.compose.ui.unit.TextUnit={(it*textScale/systemFontScale).sp};ProvideTextStyle(LocalTextStyle.current.copy(fontWeight=FontWeight.Bold)){Column(Modifier.fillMaxSize().padding(horizontal=if(portrait)d(14f) else d(4f),vertical=d(6f))){Box(Modifier.fillMaxWidth().height(if(portrait)d(68f) else d(76f))){if(showTitle)Text(current.name,color=Color.White,fontSize=t(if(portrait)26f else 22f),fontWeight=FontWeight.ExtraBold,modifier=Modifier.align(Alignment.CenterStart).padding(start=if(portrait)0.dp else d(28f)));Row(Modifier.align(Alignment.CenterEnd).then(if(current==AppScreen.Dashboard) Modifier.dashboardLensClearance() else Modifier).padding(end=if(portrait)0.dp else d(20f)),verticalAlignment=Alignment.CenterVertically){DashboardIconView(DashboardIcon.Wifi,if(status.wifiConnected)ProGreen else ProAmber,Modifier.size(d(if(portrait)24f else 34f)));Text(if(status.wifiConnected)" Online" else " Offline",color=if(status.wifiConnected)ProGreen else ProAmber,fontSize=t(if(portrait)14f else 18f));if(!portrait){Box(Modifier.padding(horizontal=d(22f)).width(d(1f)).height(d(42f)).background(ProEdge));Text(now.format(DateTimeFormatter.ofPattern("MMM d, h:mm a")),color=Color.White,fontSize=t(18f))}}};Box(Modifier.weight(1f).fillMaxWidth()){content(d,t)};Spacer(Modifier.height(d(6f)));AppBottomNavigation(current,d,t,onNavigate,portrait,Modifier.height(d(if(portrait)82f else 112f)).fillMaxWidth(),standalone)}}}}

@Composable fun AppBottomNavigation(current:AppScreen,d:(Float)->androidx.compose.ui.unit.Dp,t:(Float)->androidx.compose.ui.unit.TextUnit,onNavigate:(AppScreen)->Unit,portrait:Boolean=false,modifier:Modifier=Modifier,standalone:Boolean=false){val items=listOf(Triple(DashboardIcon.House,"Dashboard",AppScreen.Dashboard),Triple(DashboardIcon.Layers,"Zones",AppScreen.Zones),Triple(DashboardIcon.ChartBars,"History",AppScreen.History),Triple(DashboardIcon.Camera,"Cameras",AppScreen.Cameras),Triple(DashboardIcon.Settings,"Settings",AppScreen.Settings)).filter { !standalone || it.third != AppScreen.Cameras };val selectionDuration=accessibleMotionDuration(AppMotion.SensorSelectionMs);Row(modifier.clip(RoundedCornerShape(d(if(portrait)24f else 36f))).background(Color(0xF516181B)).border(d(1f),ProEdge,RoundedCornerShape(d(if(portrait)24f else 36f))).padding(d(if(portrait)6f else 14f)),verticalAlignment=Alignment.CenterVertically){items.forEach{(icon,label,screen)->val active=screen==current;val color by androidx.compose.animation.animateColorAsState(if(active)ProAmber else Color.White,animationSpec=androidx.compose.animation.core.tween(selectionDuration),label="nav icon color");val scale by androidx.compose.animation.core.animateFloatAsState(if(active)1.04f else 1f,animationSpec=androidx.compose.animation.core.tween(selectionDuration),label="nav icon scale");val itemModifier=Modifier.weight(1f).fillMaxHeight().background(if(active)ProAmber.copy(alpha=.12f) else Color.Transparent,RoundedCornerShape(d(20f))).clickable{onNavigate(screen)};if(portrait)Column(itemModifier,horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center){DashboardIconView(icon,color,Modifier.size(d(25f)).graphicsLayer{scaleX=scale;scaleY=scale});Text(label,color=color,fontSize=t(9.5f),fontWeight=FontWeight.Bold,maxLines=1)}else Row(itemModifier.then(if(current==AppScreen.Dashboard) Modifier.dashboardLensClearance() else Modifier),horizontalArrangement=Arrangement.Center,verticalAlignment=Alignment.CenterVertically){DashboardIconView(icon,color,Modifier.size(d(34f)).graphicsLayer{scaleX=scale;scaleY=scale});Spacer(Modifier.width(d(13f)));Text(label,color=color,fontSize=t(17f),fontWeight=FontWeight.Bold)}}}}

@Composable fun NeonPanel(modifier:Modifier,content:@Composable BoxScope.()->Unit)=Box(modifier.clip(RoundedCornerShape(22.dp)).background(Color(0xF216181B),RoundedCornerShape(22.dp)).border(1.dp,ProEdge,RoundedCornerShape(22.dp)),content=content)
@Composable internal fun ProPanel(modifier:Modifier,content:@Composable BoxScope.()->Unit)=Box(modifier.clip(RoundedCornerShape(22.dp)).background(Brush.linearGradient(listOf(Color(0xFF1E2023),ProPanel)),RoundedCornerShape(22.dp)).border(1.dp,ProEdge,RoundedCornerShape(22.dp)),content=content)
fun formatTemp(value:Double?)=value?.roundToInt()?.let{"$it°F"}?:"—"
fun formatHumidity(value:Double?)=value?.roundToInt()?.let{"$it%"}?:"—"
