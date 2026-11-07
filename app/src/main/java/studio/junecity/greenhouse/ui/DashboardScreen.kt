package studio.junecity.greenhouse.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import studio.junecity.greenhouse.model.*
import studio.junecity.greenhouse.data.SpatialInterpolator
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

@Composable fun DashboardScreen(state:GreenhouseUiState,status:DeviceStatus,now:LocalDateTime,onNavigate:(AppScreen)->Unit,modifier:Modifier=Modifier){
    ApprovedFrame(AppScreen.Dashboard,status,now,onNavigate,modifier,showTitle=false){d,t->
        val greenhouse=state.preferences.activeGreenhouse;val fresh=state.sensors.filter{it.greenhouseId==greenhouse.id&&it.isFresh()};val temps=fresh.mapNotNull{it.temperatureF};val avgT=SpatialInterpolator.areaAverage(SpatialInterpolator.samples(fresh,greenhouse,Metric.Temperature),greenhouse);val avgH=SpatialInterpolator.areaAverage(SpatialInterpolator.samples(fresh,greenhouse,Metric.Humidity),greenhouse)
        Row(Modifier.fillMaxSize(),horizontalArrangement=Arrangement.spacedBy(d(14f))){
            NeonPanel(Modifier.weight(.31f).fillMaxHeight()){Box(Modifier.fillMaxSize()){CameraPreview(Modifier.fillMaxSize());Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha=.08f)));Row(Modifier.align(Alignment.TopStart).padding(d(16f)).background(Color(0xCC162326),RoundedCornerShape(d(22f))).padding(horizontal=d(16f),vertical=d(8f)),verticalAlignment=Alignment.CenterVertically){Box(Modifier.size(d(12f)).background(if(fresh.isNotEmpty())DashboardTokens.Green else Color.Gray,androidx.compose.foundation.shape.CircleShape));Spacer(Modifier.width(d(8f)));Text(if(fresh.isNotEmpty())"Monitoring" else "Camera preview",color=Color.White,fontSize=t(17f))}}}
            Column(Modifier.weight(.69f).fillMaxHeight(),verticalArrangement=Arrangement.spacedBy(d(12f))){
                Row(Modifier.weight(1f),horizontalArrangement=Arrangement.spacedBy(d(12f))){LargeStat("Avg Temperature",formatTemp(avgT),DashboardIcon.Thermometer,Color.White,d,t,Modifier.weight(1f));LargeStat("Avg Humidity",formatHumidity(avgH),DashboardIcon.Drop,Color(0xFF05C5F4),d,t,Modifier.weight(1f))}
                Row(Modifier.weight(.84f),horizontalArrangement=Arrangement.spacedBy(d(12f))){SmallStat("High",formatTemp(temps.maxOrNull()),"↑",DashboardTokens.Green,d,t,Modifier.weight(1f));SmallStat("Low",formatTemp(temps.minOrNull()),"↓",Color(0xFF09C7F4),d,t,Modifier.weight(1f));SmallStat("Sensors","${fresh.size} Online","◉",DashboardTokens.Green,d,t,Modifier.weight(1f))}
                NeonPanel(Modifier.weight(.9f).fillMaxWidth()){Row(Modifier.fillMaxSize().padding(horizontal=d(28f)),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween){DashboardIconView(DashboardIcon.CloudSun,Color(0xFF77CDFF),Modifier.size(d(70f)));Box(Modifier.width(d(2f)).height(d(54f)).background(Color.White));Column(horizontalAlignment=Alignment.CenterHorizontally){Text("Outside (${state.preferences.zipCode})",color=Color.White,fontSize=t(20f),fontWeight=FontWeight.Bold);Text(formatTemp(state.weather.temperatureF),color=Color(0xFF00BDEB),fontSize=t(46f),fontWeight=FontWeight.Bold)};Box(Modifier.width(d(2f)).height(d(54f)).background(Color.White));Column(horizontalAlignment=Alignment.End){Text(state.weather.condition,color=Color.White,fontSize=t(25f),fontWeight=FontWeight.Bold);Text(if(state.weather.stale)"Cached · stale" else state.weather.location,color=DashboardTokens.Muted,fontSize=t(12f))}}}
            }
        }
    }
}

@Composable fun ApprovedFrame(current:AppScreen,status:DeviceStatus,now:LocalDateTime,onNavigate:(AppScreen)->Unit,modifier:Modifier=Modifier,showTitle:Boolean=true,content:@Composable ((Float)->androidx.compose.ui.unit.Dp,(Float)->androidx.compose.ui.unit.TextUnit)->Unit){
    BoxWithConstraints(modifier.background(Brush.radialGradient(listOf(Color(0xFF06262A),Color(0xFF001014)),radius=1500f))){val sx=maxWidth.value/1875f;val sy=maxHeight.value/839f;val s=minOf(sx,sy).coerceAtLeast(.45f);val d:(Float)->androidx.compose.ui.unit.Dp={(it*s).dp};val t:(Float)->androidx.compose.ui.unit.TextUnit={(it*s).sp};Column(Modifier.fillMaxSize().padding(horizontal=d(18f),vertical=d(12f))){Box(Modifier.fillMaxWidth().height(d(76f))){if(showTitle)Text(current.name,color=Color.White,fontSize=t(34f),fontWeight=FontWeight.Bold,modifier=Modifier.align(Alignment.CenterStart).padding(start=d(28f)));Row(Modifier.align(Alignment.CenterEnd).padding(end=d(20f)),verticalAlignment=Alignment.CenterVertically){DashboardIconView(DashboardIcon.Wifi,DashboardTokens.Green,Modifier.size(d(34f)));Text(if(status.wifiConnected)" Online" else " Offline",color=if(status.wifiConnected)DashboardTokens.Green else Color(0xFFFFB23F),fontSize=t(18f));Box(Modifier.padding(horizontal=d(22f)).width(d(1f)).height(d(42f)).background(DashboardTokens.Muted));Text(now.format(DateTimeFormatter.ofPattern("MMM d, h:mm a")),color=Color.White,fontSize=t(18f),fontWeight=FontWeight.SemiBold)}};Box(Modifier.weight(1f).fillMaxWidth()){content(d,t)};Spacer(Modifier.height(d(12f)));AppBottomNavigation(current,d,t,onNavigate,Modifier.height(d(112f)).fillMaxWidth())}}
}

@Composable fun AppBottomNavigation(current:AppScreen,d:(Float)->androidx.compose.ui.unit.Dp,t:(Float)->androidx.compose.ui.unit.TextUnit,onNavigate:(AppScreen)->Unit,modifier:Modifier=Modifier){val items=listOf(Triple(DashboardIcon.House,"Dashboard",AppScreen.Dashboard),Triple(DashboardIcon.Grid,"Zones",AppScreen.Zones),Triple(DashboardIcon.Chart,"History",AppScreen.History),Triple(DashboardIcon.Camera,"Cameras",AppScreen.Cameras),Triple(DashboardIcon.Gear,"Settings",AppScreen.Settings));Row(modifier.background(Color(0xC9001519),RoundedCornerShape(d(36f))).border(d(1f),Color(0xFF8FECFA),RoundedCornerShape(d(36f))).padding(d(14f)),verticalAlignment=Alignment.CenterVertically){items.forEachIndexed{i,(icon,label,screen)->if(i>0)Box(Modifier.width(d(1f)).height(d(54f)).background(Color(0xFF87BBC3)));val active=screen==current;Row(Modifier.weight(1f).fillMaxHeight().background(if(active)DashboardTokens.Green else Color.Transparent,RoundedCornerShape(d(28f))).clickable{onNavigate(screen)},horizontalArrangement=Arrangement.Center,verticalAlignment=Alignment.CenterVertically){DashboardIconView(icon,if(active)DashboardTokens.Background else Color.White,Modifier.size(d(36f)));Spacer(Modifier.width(d(15f)));Text(label,color=if(active)DashboardTokens.Background else Color.White,fontSize=t(18f),fontWeight=if(active)FontWeight.Bold else FontWeight.Medium)}}}}
@Composable fun NeonPanel(modifier:Modifier,content:@Composable BoxScope.()->Unit)=Box(modifier.background(Color(0xB900171A),RoundedCornerShape(22.dp)).border(2.dp,Color(0xFF8DEBFA),RoundedCornerShape(22.dp)),content=content)
@Composable private fun LargeStat(label:String,value:String,icon:DashboardIcon,tint:Color,d:(Float)->androidx.compose.ui.unit.Dp,t:(Float)->androidx.compose.ui.unit.TextUnit,modifier:Modifier){NeonPanel(modifier){Row(Modifier.fillMaxSize().padding(horizontal=d(34f)),verticalAlignment=Alignment.CenterVertically){DashboardIconView(icon,tint,Modifier.size(d(82f)));Spacer(Modifier.width(d(38f)));Column{Text(label,color=Color.White,fontSize=t(25f),fontWeight=FontWeight.Bold);Text(value,color=Color.White,fontSize=t(48f),fontWeight=FontWeight.Bold)}}}}
@Composable private fun SmallStat(label:String,value:String,symbol:String,tint:Color,d:(Float)->androidx.compose.ui.unit.Dp,t:(Float)->androidx.compose.ui.unit.TextUnit,modifier:Modifier){NeonPanel(modifier){Row(Modifier.fillMaxSize().padding(horizontal=d(28f)),verticalAlignment=Alignment.CenterVertically){Text(symbol,color=tint,fontSize=t(54f),fontWeight=FontWeight.Bold);Spacer(Modifier.width(d(26f)));Column{Text(label,color=Color.White,fontSize=t(22f),fontWeight=FontWeight.Bold);Text(value,color=tint,fontSize=t(32f),fontWeight=FontWeight.Bold)}}}}
fun formatTemp(value:Double?)=value?.roundToInt()?.let{"$it°F"}?:"—"
fun formatHumidity(value:Double?)=value?.roundToInt()?.let{"$it%"}?:"—"
