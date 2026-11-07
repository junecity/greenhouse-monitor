package studio.junecity.greenhouse.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import studio.junecity.greenhouse.model.*
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.min

@Composable
fun DashboardScreen(data: DashboardData, status: DeviceStatus, now: LocalDateTime, modifier: Modifier = Modifier, onNavigate:(AppScreen)->Unit = {}) {
    BoxWithConstraints(modifier.background(DashboardTokens.BackgroundDeep), contentAlignment = Alignment.Center) {
        val scale = min(maxWidth.value / DashboardTokens.CanvasWidth, maxHeight.value / DashboardTokens.CanvasHeight)
        val verticalScale = maxHeight.value / DashboardTokens.CanvasHeight
        val d: (Float) -> androidx.compose.ui.unit.Dp = { (it * scale).dp }
        val dy: (Float) -> androidx.compose.ui.unit.Dp = { (it * verticalScale).dp }
        val t: (Float) -> androidx.compose.ui.unit.TextUnit = { (it * scale).sp }
        Column(
            Modifier
                .fillMaxSize()
                .background(Brush.radialGradient(listOf(Color(0xFF062228), DashboardTokens.Background), radius = 900f * scale))
                .padding(horizontal = d(22f))
        ) {
            Header(status, now, scale, d, t, Modifier.height(dy(78f)))
            Row(Modifier.height(dy(276f)), horizontalArrangement = Arrangement.spacedBy(d(18f))) {
                GreenhousePanel(data.greenhouse, scale, d, t, Modifier.weight(.31f).fillMaxHeight())
                WeatherPanel(data.weather, scale, d, t, Modifier.weight(.24f).fillMaxHeight())
                CameraPreview(Modifier.weight(.45f).fillMaxHeight())
            }
            Spacer(Modifier.height(dy(14f)))
            BottomNavigation(AppScreen.Dashboard, d, t, onNavigate, Modifier.height(dy(64f)))
        }
    }
}

@Composable
private fun Header(status: DeviceStatus, now: LocalDateTime, scale: Float, d: (Float)->androidx.compose.ui.unit.Dp, t: (Float)->androidx.compose.ui.unit.TextUnit, modifier:Modifier) {
    Box(modifier.fillMaxWidth()) {
        Column(Modifier.align(Alignment.CenterStart)) {
            Text("junecity.studio", color=Color.White, fontSize=t(25f), fontWeight=FontWeight.Bold)
            Text("G A R D E N  ·  B U I L D  ·  I N S P I R E", color=Color.White, fontSize=t(10f), letterSpacing=t(2.2f))
        }
        Column(Modifier.align(Alignment.Center), horizontalAlignment=Alignment.CenterHorizontally) {
            val time = now.format(DateTimeFormatter.ofPattern("h:mm", Locale.US))
            val amPm = now.format(DateTimeFormatter.ofPattern("a", Locale.US))
            Row(verticalAlignment=Alignment.Bottom) {
                Text(time, color=Color.White, fontSize=t(52f), fontWeight=FontWeight.Bold)
                Text(" $amPm", color=Color.White, fontSize=t(27f), fontWeight=FontWeight.Bold, modifier=Modifier.padding(bottom=d(7f)))
            }
            Text(now.format(DateTimeFormatter.ofPattern("EEE, MMM d, yyyy", Locale.US)), color=Color(0xFFBEE4FF), fontSize=t(15f))
        }
        Row(Modifier.align(Alignment.CenterEnd).height(d(56f)), verticalAlignment=Alignment.CenterVertically) {
            StatusItem(DashboardIcon.Wifi, if(status.wifiConnected) "Wi-Fi\nConnected" else "Wi-Fi\nOffline", scale,d,t)
            Box(Modifier.padding(horizontal=d(24f)).width(d(1f)).height(d(50f)).background(Color(0xFF6A767C)))
            StatusItem(DashboardIcon.Battery, if(status.charging) "Charging\n${status.batteryPercent}%" else "Battery\n${status.batteryPercent}%", scale,d,t)
        }
    }
}

@Composable
private fun StatusItem(icon: DashboardIcon, label:String, scale:Float,d:(Float)->androidx.compose.ui.unit.Dp,t:(Float)->androidx.compose.ui.unit.TextUnit) {
    Column(horizontalAlignment=Alignment.CenterHorizontally, modifier=Modifier.width(d(80f))) {
        DashboardIconView(icon,DashboardTokens.Green,Modifier.size(d(35f)))
        Text(label,color=Color.White,fontSize=t(11f),lineHeight=t(12f),textAlign=TextAlign.Center)
    }
}

@Composable
private fun Panel(modifier:Modifier, content:@Composable BoxScope.()->Unit) {
    Box(modifier.background(Brush.linearGradient(listOf(Color(0xE908191D),Color(0xF0031115))),RoundedCornerShape(18.dp)).border(1.dp,DashboardTokens.PanelEdge,RoundedCornerShape(18.dp)),content=content)
}

@Composable
private fun GreenhousePanel(reading:GreenhouseReading,scale:Float,d:(Float)->androidx.compose.ui.unit.Dp,t:(Float)->androidx.compose.ui.unit.TextUnit,modifier:Modifier) {
    Panel(modifier) {
        Column(Modifier.padding(horizontal=d(38f),vertical=d(16f))) {
            Row(verticalAlignment=Alignment.CenterVertically) {
                DashboardIconView(DashboardIcon.House,Color.White,Modifier.size(d(50f)))
                Spacer(Modifier.width(d(20f)))
                Column { Row(verticalAlignment=Alignment.CenterVertically) { Text("Greenhouse",color=Color.White,fontSize=t(23f),fontWeight=FontWeight.SemiBold); if(reading.isDemo) DemoBadge(d,t) }; Text("All Zones (4)⌄",color=DashboardTokens.Muted,fontSize=t(16f)) }
                Spacer(Modifier.weight(1f)); Text("›",color=Color(0xFFB8D2E7),fontSize=t(44f),fontWeight=FontWeight.Light)
            }
            Spacer(Modifier.height(d(17f)))
            Row(verticalAlignment=Alignment.CenterVertically) {
                DashboardIconView(DashboardIcon.Thermometer,DashboardTokens.Green,Modifier.size(d(62f)))
                Spacer(Modifier.width(d(34f)))
                Text("${reading.temperatureF}°F",color=DashboardTokens.Green,fontSize=t(72f),fontWeight=FontWeight.Bold)
            }
            Spacer(Modifier.height(d(3f)))
            Row(verticalAlignment=Alignment.CenterVertically) {
                DashboardIconView(DashboardIcon.Drop,DashboardTokens.Blue,Modifier.size(d(61f)))
                Spacer(Modifier.width(d(34f)))
                Column { Text("${reading.humidityPercent}%",color=DashboardTokens.Blue,fontSize=t(62f),fontWeight=FontWeight.Bold,lineHeight=t(62f)); Text("Humidity",color=DashboardTokens.Muted,fontSize=t(18f)) }
            }
        }
    }
}

@Composable
private fun WeatherPanel(weather:WeatherReading,scale:Float,d:(Float)->androidx.compose.ui.unit.Dp,t:(Float)->androidx.compose.ui.unit.TextUnit,modifier:Modifier) {
    Panel(modifier) {
        Column(Modifier.padding(horizontal=d(18f),vertical=d(16f))) {
            Row(verticalAlignment=Alignment.CenterVertically) {
                DashboardIconView(DashboardIcon.CloudSun,Color(0xFFDCEEFF),Modifier.size(d(62f)))
                Spacer(Modifier.width(d(15f)))
                Column { Row(verticalAlignment=Alignment.CenterVertically) { Text("Outdoor",color=Color.White,fontSize=t(23f),fontWeight=FontWeight.SemiBold); if(weather.isDemo) DemoBadge(d,t) }; Text(weather.location,color=DashboardTokens.Muted,fontSize=t(16f)) }
            }
            Spacer(Modifier.height(d(12f)))
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.Center,modifier=Modifier.fillMaxWidth()) {
                DashboardIconView(DashboardIcon.CloudSun,Color(0xFFDCEEFF),Modifier.size(d(78f)))
                Spacer(Modifier.width(d(18f)))
                Column(horizontalAlignment=Alignment.CenterHorizontally) { Text("${weather.temperatureF}°F",color=Color.White,fontSize=t(58f),fontWeight=FontWeight.Bold,lineHeight=t(58f)); Text(weather.condition,color=DashboardTokens.Muted,fontSize=t(17f)) }
            }
            Spacer(Modifier.weight(1f)); Box(Modifier.fillMaxWidth().height(d(1f)).background(DashboardTokens.Divider)); Spacer(Modifier.height(d(10f)))
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                MiniForecast(DashboardIcon.Thermometer,"${weather.tonightF}°F","Tonight",DashboardTokens.Green,d,t,Modifier.weight(1f))
                Box(Modifier.width(d(1f)).height(d(55f)).background(DashboardTokens.Divider))
                MiniForecast(DashboardIcon.Drop,"${weather.rainChancePercent}%","Chance of Rain",DashboardTokens.Blue,d,t,Modifier.weight(1f))
            }
        }
    }
}

@Composable private fun MiniForecast(icon:DashboardIcon,value:String,label:String,tint:Color,d:(Float)->androidx.compose.ui.unit.Dp,t:(Float)->androidx.compose.ui.unit.TextUnit,modifier:Modifier){ Row(modifier,verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.Center){DashboardIconView(icon,tint,Modifier.size(d(30f)));Spacer(Modifier.width(d(10f)));Column{Text(value,color=Color.White,fontSize=t(27f),fontWeight=FontWeight.Bold);Text(label,color=DashboardTokens.Muted,fontSize=t(13f))}} }

@Composable private fun DemoBadge(d:(Float)->androidx.compose.ui.unit.Dp,t:(Float)->androidx.compose.ui.unit.TextUnit){ Text(" DEMO ",color=DashboardTokens.Green,fontSize=t(8f),fontWeight=FontWeight.Bold,modifier=Modifier.padding(start=d(8f)).border(1.dp,DashboardTokens.Green,RoundedCornerShape(3.dp))) }

@Composable
fun BottomNavigation(current:AppScreen,d:(Float)->androidx.compose.ui.unit.Dp,t:(Float)->androidx.compose.ui.unit.TextUnit,onNavigate:(AppScreen)->Unit,modifier:Modifier=Modifier) {
    val items=listOf(Triple(DashboardIcon.House,"Dashboard",AppScreen.Dashboard),Triple(DashboardIcon.Grid,"Zones",AppScreen.Zones),Triple(DashboardIcon.Chart,"History",AppScreen.History),Triple(DashboardIcon.Camera,"Cameras",AppScreen.Cameras),Triple(DashboardIcon.Gear,"Settings",AppScreen.Settings))
    Row(modifier.fillMaxWidth().background(Brush.linearGradient(listOf(Color(0xFF061A1F),Color(0xFF001014))),RoundedCornerShape(d(28f))).padding(horizontal=d(28f),vertical=d(9f)),verticalAlignment=Alignment.CenterVertically) {
        items.forEachIndexed { index,(icon,label,screen) ->
            val active=current==screen
            if(index>0) Box(Modifier.width(d(1f)).height(d(35f)).background(DashboardTokens.Divider))
            Row(Modifier.weight(1f).fillMaxHeight().background(if(active) DashboardTokens.Green else Color.Transparent,RoundedCornerShape(d(24f))).clickable{onNavigate(screen)},horizontalArrangement=Arrangement.Center,verticalAlignment=Alignment.CenterVertically){DashboardIconView(icon,if(active) DashboardTokens.Background else Color(0xFFB9D4ED),Modifier.size(d(34f)));Spacer(Modifier.width(d(12f)));Text(label,color=if(active) DashboardTokens.Background else DashboardTokens.Muted,fontSize=t(15f),fontWeight=if(active) FontWeight.Bold else FontWeight.Normal)}
        }
    }
}
