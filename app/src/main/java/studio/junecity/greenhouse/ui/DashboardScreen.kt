package studio.junecity.greenhouse.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
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
import kotlin.math.sin

private val ProAmber=Color(0xFFFFB545)
private val ProBlue=Color(0xFF64C9FF)
private val ProGreen=Color(0xFF61E85A)
private val ProPanel=Color(0xF216181B)
private val ProEdge=Color(0xFF414449)

@Composable fun DashboardScreen(state:GreenhouseUiState,status:DeviceStatus,now:LocalDateTime,onNavigate:(AppScreen)->Unit,modifier:Modifier=Modifier){
    val context=LocalContext.current
    var cameraPermission by remember{mutableStateOf(ContextCompat.checkSelfPermission(context,Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED)}
    val cameraPermissionLauncher=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){cameraPermission=it}
    LaunchedEffect(Unit){if(!cameraPermission)cameraPermissionLauncher.launch(Manifest.permission.CAMERA)}
    ApprovedFrame(AppScreen.Dashboard,status,now,onNavigate,modifier,showTitle=false){d,t->
        val greenhouse=state.preferences.activeGreenhouse
        val fresh=state.sensors.filter{it.greenhouseId==greenhouse.id&&it.isFresh()}
        val temperature=SpatialInterpolator.areaAverage(SpatialInterpolator.samples(fresh,greenhouse,Metric.Temperature),greenhouse)
        val humidity=SpatialInterpolator.areaAverage(SpatialInterpolator.samples(fresh,greenhouse,Metric.Humidity),greenhouse)
        val vpd=SpatialInterpolator.areaAverage(SpatialInterpolator.samples(fresh,greenhouse,Metric.Vpd),greenhouse)
        val dewPoint=SpatialInterpolator.areaAverage(SpatialInterpolator.samples(fresh,greenhouse,Metric.DewPoint),greenhouse)
        Row(Modifier.fillMaxSize(),horizontalArrangement=Arrangement.spacedBy(d(14f))){
            Column(Modifier.weight(.59f).fillMaxHeight(),verticalArrangement=Arrangement.spacedBy(d(14f))){
                Row(Modifier.weight(.70f),horizontalArrangement=Arrangement.spacedBy(d(14f))){
                    Column(Modifier.weight(.46f),verticalArrangement=Arrangement.spacedBy(d(12f))){
                        WeatherCard(state,d,t,Modifier.weight(.38f))
                        Row(Modifier.weight(.31f),horizontalArrangement=Arrangement.spacedBy(d(12f))){MiniMetric("Average Temp",formatTemp(temperature),DashboardIcon.Thermometer,ProAmber,d,t,Modifier.weight(1f));MiniMetric("Average Humidity",formatHumidity(humidity),DashboardIcon.Drop,ProBlue,d,t,Modifier.weight(1f))}
                        Row(Modifier.weight(.31f),horizontalArrangement=Arrangement.spacedBy(d(12f))){MiniMetric("VPD",vpd?.let{"%.1f kPa".format(it)}?:"—",DashboardIcon.Chart,Color(0xFF8EEB75),d,t,Modifier.weight(1f));MiniMetric("Dew Point",formatTemp(dewPoint),DashboardIcon.Drop,ProBlue,d,t,Modifier.weight(1f))}
                    }
                    ClimateGauge(greenhouse.name,temperature,humidity,d,t,Modifier.weight(.54f).fillMaxHeight())
                }
                TrendCard(state.history,d,t,Modifier.weight(.30f).fillMaxWidth())
            }
            Column(Modifier.weight(.41f).fillMaxHeight(),verticalArrangement=Arrangement.spacedBy(d(14f))){
                ProPanel(Modifier.weight(.49f).fillMaxWidth()){
                    if(cameraPermission)FrontCameraFeed(d,t,Modifier.fillMaxSize())
                    else Box(Modifier.fillMaxSize().clickable{cameraPermissionLauncher.launch(Manifest.permission.CAMERA)},contentAlignment=Alignment.Center){Column(horizontalAlignment=Alignment.CenterHorizontally){DashboardIconView(DashboardIcon.Camera,ProAmber,Modifier.size(d(54f)));Text("Enable front camera",color=Color.White,fontSize=t(16f));Text("Tap to allow camera access",color=DashboardTokens.Muted,fontSize=t(11f))}}
                }
                SensorCard(fresh,d,t,Modifier.weight(.51f).fillMaxWidth())
            }
        }
    }
}

@Composable private fun WeatherCard(state:GreenhouseUiState,d:(Float)->androidx.compose.ui.unit.Dp,t:(Float)->androidx.compose.ui.unit.TextUnit,modifier:Modifier){ProPanel(modifier){Row(Modifier.fillMaxSize().padding(horizontal=d(22f)),verticalAlignment=Alignment.CenterVertically){DashboardIconView(DashboardIcon.CloudSun,ProAmber,Modifier.size(d(62f)));Spacer(Modifier.width(d(18f)));Column(Modifier.weight(1f)){Text("Outdoor",color=Color.White,fontSize=t(16f));Text(formatTemp(state.weather.temperatureF),color=Color.White,fontSize=t(34f),fontWeight=FontWeight.Black)};Box(Modifier.width(d(1f)).fillMaxHeight(.62f).background(ProEdge));Spacer(Modifier.width(d(18f)));Column{Text(state.weather.location,color=DashboardTokens.Muted,fontSize=t(12f));Text(state.weather.condition,color=Color.White,fontSize=t(14f),fontWeight=FontWeight.Bold)}}}}

@Composable private fun MiniMetric(label:String,value:String,icon:DashboardIcon,color:Color,d:(Float)->androidx.compose.ui.unit.Dp,t:(Float)->androidx.compose.ui.unit.TextUnit,modifier:Modifier){ProPanel(modifier){Row(Modifier.fillMaxSize().padding(d(16f)),verticalAlignment=Alignment.CenterVertically){DashboardIconView(icon,color,Modifier.size(d(48f)));Spacer(Modifier.width(d(14f)));Column{Text(label,color=DashboardTokens.Muted,fontSize=t(11f),maxLines=1);Text(value,color=Color.White,fontSize=t(24f),fontWeight=FontWeight.Black,maxLines=1)}}}}

@Composable private fun ClimateGauge(name:String,temp:Double?,humidity:Double?,d:(Float)->androidx.compose.ui.unit.Dp,t:(Float)->androidx.compose.ui.unit.TextUnit,modifier:Modifier){ProPanel(modifier){Column(Modifier.fillMaxSize().padding(d(14f)),horizontalAlignment=Alignment.CenterHorizontally){Box(Modifier.weight(1f).aspectRatio(1f)){Canvas(Modifier.fillMaxSize()){val stroke=size.minDimension*.055f;drawArc(Color(0xFF292C30),135f,270f,false,style=Stroke(stroke,cap=StrokeCap.Round));val progress=(((temp?:40.0)-40)/60).coerceIn(0.0,1.0).toFloat();drawArc(ProAmber,135f,270f*progress,false,style=Stroke(stroke,cap=StrokeCap.Round))};Column(Modifier.align(Alignment.Center),horizontalAlignment=Alignment.CenterHorizontally){Text(name,color=Color.White,fontSize=t(16f),fontWeight=FontWeight.ExtraBold,maxLines=1);Text(formatTemp(temp),color=Color.White,fontSize=t(52f),fontWeight=FontWeight.Black);Text("${formatHumidity(humidity)} RH",color=Color.White,fontSize=t(18f))}};Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceEvenly){ClimateMode("♨","HEAT",true,d,t);ClimateMode("❄","COOL",false,d,t);ClimateMode("◎","FAN",false,d,t)}}}}

@Composable private fun ClimateMode(icon:String,label:String,active:Boolean,d:(Float)->androidx.compose.ui.unit.Dp,t:(Float)->androidx.compose.ui.unit.TextUnit){Column(horizontalAlignment=Alignment.CenterHorizontally){Box(Modifier.size(d(44f)).background(if(active)ProAmber.copy(alpha=.14f) else Color(0xFF202327),CircleShape).border(d(1f),if(active)ProAmber else ProEdge,CircleShape),contentAlignment=Alignment.Center){Text(icon,color=if(active)ProAmber else Color.Gray,fontSize=t(18f))};Text(label,color=if(active)ProAmber else Color.Gray,fontSize=t(10f))}}

@Composable private fun TrendCard(history:List<HistoryReading>,d:(Float)->androidx.compose.ui.unit.Dp,t:(Float)->androidx.compose.ui.unit.TextUnit,modifier:Modifier){ProPanel(modifier){Column(Modifier.fillMaxSize().padding(d(18f))){Text("24-Hour Trend",color=Color.White,fontSize=t(17f),fontWeight=FontWeight.ExtraBold);Spacer(Modifier.height(d(6f)));Canvas(Modifier.weight(1f).fillMaxWidth()){fun curve(color:Color,base:Float,phase:Float){val p=Path();for(i in 0..60){val x=size.width*i/60f;val y=size.height*(base+(sin(i*.38+phase)*.10).toFloat());if(i==0)p.moveTo(x,y)else p.lineTo(x,y)};drawPath(p,color,style=Stroke(4f,cap=StrokeCap.Round))};curve(ProAmber,.35f,0f);curve(ProBlue,.72f,.8f)}}}}

@Composable private fun SensorCard(sensors:List<GoveeSensor>,d:(Float)->androidx.compose.ui.unit.Dp,t:(Float)->androidx.compose.ui.unit.TextUnit,modifier:Modifier){ProPanel(modifier){Column(Modifier.fillMaxSize().padding(d(16f))){Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text("Sensors (${sensors.size})",color=Color.White,fontSize=t(18f),fontWeight=FontWeight.ExtraBold);Text("Live readings",color=DashboardTokens.Muted,fontSize=t(10f))};Spacer(Modifier.height(d(8f)));if(sensors.isEmpty())Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){Text("Connect H5179 sensors in Settings",color=DashboardTokens.Muted,fontSize=t(13f))}else sensors.take(4).forEach{sensor->Row(Modifier.weight(1f).fillMaxWidth().padding(vertical=d(4f)).background(Color(0xFF1E2023),RoundedCornerShape(d(18f))).border(d(1f),ProEdge,RoundedCornerShape(d(18f))).padding(horizontal=d(14f)),verticalAlignment=Alignment.CenterVertically){Box(Modifier.size(d(10f)).background(ProGreen,CircleShape));Spacer(Modifier.width(d(12f)));Text(sensor.zone,color=Color.White,fontSize=t(13f),modifier=Modifier.weight(1f));Text(formatTemp(sensor.temperatureF),color=Color.White,fontSize=t(16f),fontWeight=FontWeight.Black);Spacer(Modifier.width(d(22f)));Text(formatHumidity(sensor.humidityPercent),color=Color.White,fontSize=t(16f),fontWeight=FontWeight.Black)}}}}}

@Composable fun ApprovedFrame(current:AppScreen,status:DeviceStatus,now:LocalDateTime,onNavigate:(AppScreen)->Unit,modifier:Modifier=Modifier,showTitle:Boolean=true,content:@Composable ((Float)->androidx.compose.ui.unit.Dp,(Float)->androidx.compose.ui.unit.TextUnit)->Unit){BoxWithConstraints(modifier.background(Brush.radialGradient(listOf(Color(0xFF1C1D1F),Color(0xFF08090A)),radius=1500f))){val densityScale=minOf(maxWidth.value/800f,maxHeight.value/360f).coerceIn(.85f,1.30f);val geometryScale=.43f*densityScale;val textScale=(geometryScale*2.05f).coerceIn(.88f,1.22f);val d:(Float)->androidx.compose.ui.unit.Dp={(it*geometryScale).dp};val t:(Float)->androidx.compose.ui.unit.TextUnit={(it*textScale).sp};ProvideTextStyle(LocalTextStyle.current.copy(fontWeight=FontWeight.Bold)){Column(Modifier.fillMaxSize().padding(horizontal=d(4f),vertical=d(6f))){Box(Modifier.fillMaxWidth().height(d(76f))){if(showTitle)Text(current.name,color=Color.White,fontSize=t(22f),fontWeight=FontWeight.ExtraBold,modifier=Modifier.align(Alignment.CenterStart).padding(start=d(28f)));Row(Modifier.align(Alignment.CenterEnd).padding(end=d(20f)),verticalAlignment=Alignment.CenterVertically){DashboardIconView(DashboardIcon.Wifi,ProGreen,Modifier.size(d(34f)));Text(if(status.wifiConnected)" Online" else " Offline",color=if(status.wifiConnected)ProGreen else ProAmber,fontSize=t(18f));Box(Modifier.padding(horizontal=d(22f)).width(d(1f)).height(d(42f)).background(ProEdge));Text(now.format(DateTimeFormatter.ofPattern("MMM d, h:mm a")),color=Color.White,fontSize=t(18f))}};Box(Modifier.weight(1f).fillMaxWidth()){content(d,t)};Spacer(Modifier.height(d(6f)));AppBottomNavigation(current,d,t,onNavigate,Modifier.height(d(112f)).fillMaxWidth())}}}}

@Composable fun AppBottomNavigation(current:AppScreen,d:(Float)->androidx.compose.ui.unit.Dp,t:(Float)->androidx.compose.ui.unit.TextUnit,onNavigate:(AppScreen)->Unit,modifier:Modifier=Modifier){val items=listOf(Triple(DashboardIcon.House,"Dashboard",AppScreen.Dashboard),Triple(DashboardIcon.Grid,"Zones",AppScreen.Zones),Triple(DashboardIcon.Chart,"History",AppScreen.History),Triple(DashboardIcon.Camera,"Cameras",AppScreen.Cameras),Triple(DashboardIcon.Gear,"Settings",AppScreen.Settings));Row(modifier.clip(RoundedCornerShape(d(36f))).background(Color(0xF516181B)).border(d(1f),ProEdge,RoundedCornerShape(d(36f))).padding(d(14f)),verticalAlignment=Alignment.CenterVertically){items.forEach{(icon,label,screen)->val active=screen==current;Row(Modifier.weight(1f).fillMaxHeight().background(if(active)ProAmber.copy(alpha=.12f) else Color.Transparent,RoundedCornerShape(d(28f))).clickable{onNavigate(screen)},horizontalArrangement=Arrangement.Center,verticalAlignment=Alignment.CenterVertically){DashboardIconView(icon,if(active)ProAmber else Color.White,Modifier.size(d(34f)));Spacer(Modifier.width(d(13f)));Text(label,color=if(active)ProAmber else Color.White,fontSize=t(17f),fontWeight=FontWeight.Bold)}}}}

@Composable fun NeonPanel(modifier:Modifier,content:@Composable BoxScope.()->Unit)=Box(modifier.clip(RoundedCornerShape(22.dp)).background(Color(0xF216181B),RoundedCornerShape(22.dp)).border(1.dp,ProEdge,RoundedCornerShape(22.dp)),content=content)
@Composable private fun ProPanel(modifier:Modifier,content:@Composable BoxScope.()->Unit)=Box(modifier.clip(RoundedCornerShape(22.dp)).background(Brush.linearGradient(listOf(Color(0xFF1E2023),ProPanel)),RoundedCornerShape(22.dp)).border(1.dp,ProEdge,RoundedCornerShape(22.dp)),content=content)
fun formatTemp(value:Double?)=value?.roundToInt()?.let{"$it°F"}?:"—"
fun formatHumidity(value:Double?)=value?.roundToInt()?.let{"$it%"}?:"—"
