package studio.junecity.greenhouse.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import studio.junecity.greenhouse.model.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.min

@Composable
fun StageScreen(state:GreenhouseUiState,onNavigate:(AppScreen)->Unit,onRefresh:()->Unit,onSaveApiKey:(String)->Unit,onAssignSensor:(String,String)->Unit,modifier:Modifier=Modifier){
    BoxWithConstraints(modifier.background(Brush.radialGradient(listOf(Color(0xFF062228),DashboardTokens.Background),radius=1200f)),contentAlignment=Alignment.Center){
        val s=min(maxWidth.value/1536f,maxHeight.value/432f).coerceAtLeast(.42f)
        val d:(Float)->androidx.compose.ui.unit.Dp={(it*s).dp};val t:(Float)->androidx.compose.ui.unit.TextUnit={(it*s).sp}
        Column(Modifier.fillMaxSize().padding(horizontal=d(22f))){
            ScreenHeader(state.screen,state.message,d,t)
            Box(Modifier.weight(1f).fillMaxWidth()){
                when(state.screen){
                    AppScreen.Zones->ZonesContent(state.sensors,d,t,onRefresh,onAssignSensor)
                    AppScreen.History->HistoryContent(state.history,d,t)
                    AppScreen.Cameras->CameraContent(d,t)
                    AppScreen.Settings->SettingsContent(state,d,t,onSaveApiKey,onRefresh)
                    else->Unit
                }
            }
            BottomNavigation(state.screen,d,t,onNavigate,Modifier.heightIn(min=d(56f),max=d(72f)).height(d(64f)))
            Spacer(Modifier.height(d(4f)))
        }
    }
}

@Composable private fun ScreenHeader(screen:AppScreen,message:String?,d:(Float)->androidx.compose.ui.unit.Dp,t:(Float)->androidx.compose.ui.unit.TextUnit){
    Row(Modifier.fillMaxWidth().height(d(68f)),verticalAlignment=Alignment.CenterVertically){
        Text("junecity.studio",color=Color.White,fontSize=t(24f),fontWeight=FontWeight.Bold)
        Spacer(Modifier.width(d(30f)));Text(screen.name,color=Color.White,fontSize=t(28f),fontWeight=FontWeight.SemiBold)
        Spacer(Modifier.weight(1f));if(message!=null)Text(message,color=DashboardTokens.Green,fontSize=t(13f),maxLines=2,overflow=TextOverflow.Ellipsis)
    }
}

@Composable private fun ZonesContent(sensors:List<GoveeSensor>,d:(Float)->androidx.compose.ui.unit.Dp,t:(Float)->androidx.compose.ui.unit.TextUnit,onRefresh:()->Unit,onAssign:(String,String)->Unit){
    var selectedId by remember(sensors){mutableStateOf(sensors.firstOrNull()?.id)};val selected=sensors.firstOrNull{it.id==selectedId}
    Row(Modifier.fillMaxSize(),horizontalArrangement=Arrangement.spacedBy(d(16f))){
        SurfacePanel(Modifier.weight(.27f).fillMaxHeight()){
            Column(Modifier.fillMaxSize().padding(d(14f))){Row(verticalAlignment=Alignment.CenterVertically){Text("Sensors",color=Color.White,fontSize=t(22f),fontWeight=FontWeight.Bold);Spacer(Modifier.weight(1f));TextButton(onClick=onRefresh){Text("Refresh",color=DashboardTokens.Green,fontSize=t(13f))}}
                if(sensors.isEmpty())Text("Connect your Govee account in Settings, then discover H5179 sensors.",color=DashboardTokens.Muted,fontSize=t(14f))
                LazyColumn(verticalArrangement=Arrangement.spacedBy(d(7f))){items(sensors,key={it.id}){sensor->SensorRow(sensor,sensor.id==selectedId,d,t){selectedId=sensor.id}}}
            }
        }
        SurfacePanel(Modifier.weight(.48f).fillMaxHeight()){
            Box(Modifier.fillMaxSize().padding(d(14f)).background(Color(0xFF071D1B),RoundedCornerShape(d(14f))).border(1.dp,DashboardTokens.PanelEdge,RoundedCornerShape(d(14f)))){
                Text("GREENHOUSE SENSOR MAP",color=DashboardTokens.Muted,fontSize=t(11f),modifier=Modifier.align(Alignment.TopCenter).padding(d(10f)))
                if(sensors.isEmpty())Text("Sensor positions appear here after discovery",color=DashboardTokens.Muted,fontSize=t(15f),modifier=Modifier.align(Alignment.Center))
                sensors.forEachIndexed{i,sensor->val x=if(i%2==0).28f else .72f;val y=.30f+(i/2)*.32f;Box(Modifier.fillMaxSize()){Box(Modifier.align(Alignment.TopStart).fillMaxWidth(x).fillMaxHeight(y).offset(d(-24f),d(-24f)).size(d(48f)).background(Brush.radialGradient(listOf(zoneColor(i).copy(alpha=.8f),Color.Transparent)),CircleShape));Column(Modifier.align(if(i%2==0)Alignment.CenterStart else Alignment.CenterEnd).padding(horizontal=d(26f)),horizontalAlignment=Alignment.CenterHorizontally){Box(Modifier.size(d(18f)).background(zoneColor(i),CircleShape));Text(sensor.zone,color=DashboardTokens.Muted,fontSize=t(10f));Text(sensor.name,color=Color.White,fontSize=t(13f),fontWeight=FontWeight.Bold);Text(sensor.temperatureF?.let{"%.1f°F".format(it)}?:"--",color=zoneColor(i),fontSize=t(18f),fontWeight=FontWeight.Bold)}}}
            }
        }
        SurfacePanel(Modifier.weight(.25f).fillMaxHeight()){
            Column(Modifier.padding(d(18f))){Text(selected?.name?:"Sensor details",color=Color.White,fontSize=t(23f),fontWeight=FontWeight.Bold);Spacer(Modifier.height(d(8f)));Detail("Model",selected?.sku?:"—",d,t);Detail("Temperature",selected?.temperatureF?.let{"%.1f°F".format(it)}?:"—",d,t);Detail("Humidity",selected?.humidityPercent?.let{"%.0f%%".format(it)}?:"—",d,t);Detail("Status",if(selected?.online==true)"Online" else "Offline",d,t);Detail("Assigned zone",selected?.zone?:"—",d,t);Text("Assign to zone",color=DashboardTokens.Muted,fontSize=t(11f));listOf(listOf("Zone 1","Zone 2"),listOf("Zone 3","Zone 4")).forEach{row->Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(d(4f))){row.forEach{zone->OutlinedButton(onClick={selected?.let{onAssign(it.id,zone)}},modifier=Modifier.weight(1f),contentPadding=PaddingValues(d(2f)),colors=ButtonDefaults.outlinedButtonColors(containerColor=if(selected?.zone==zone)DashboardTokens.Green else Color.Transparent)){Text(zone,fontSize=t(9f),color=if(selected?.zone==zone)DashboardTokens.Background else Color.White)}}}};Spacer(Modifier.weight(1f));Text("Assignments persist on this phone. Readings are saved locally for 30 days.",color=DashboardTokens.Muted,fontSize=t(11f))}
        }
    }
}

@Composable private fun SensorRow(sensor:GoveeSensor,selected:Boolean,d:(Float)->androidx.compose.ui.unit.Dp,t:(Float)->androidx.compose.ui.unit.TextUnit,onClick:()->Unit){
    Surface(onClick=onClick,color=if(selected)Color(0xFF123A3B) else Color(0xFF07171B),shape=RoundedCornerShape(d(10f)),border=androidx.compose.foundation.BorderStroke(1.dp,if(selected)DashboardTokens.Green else DashboardTokens.PanelEdge)){
        Row(Modifier.fillMaxWidth().padding(d(10f)),verticalAlignment=Alignment.CenterVertically){Box(Modifier.size(d(12f)).background(if(sensor.online)DashboardTokens.Green else Color.Gray,CircleShape));Spacer(Modifier.width(d(9f)));Column(Modifier.weight(1f)){Text(sensor.name,color=Color.White,fontSize=t(14f),maxLines=1);Text("${sensor.zone} · ${if(sensor.online)"online" else "offline"}",color=DashboardTokens.Muted,fontSize=t(10f))};Text(sensor.temperatureF?.let{"%.1f°".format(it)}?:"--",color=Color.White,fontSize=t(16f),fontWeight=FontWeight.Bold);Spacer(Modifier.width(d(8f)));Text(sensor.humidityPercent?.let{"%.0f%%".format(it)}?:"--",color=DashboardTokens.Blue,fontSize=t(15f))}
    }
}

@Composable private fun HistoryContent(history:List<HistoryReading>,d:(Float)->androidx.compose.ui.unit.Dp,t:(Float)->androidx.compose.ui.unit.TextUnit){
    SurfacePanel(Modifier.fillMaxSize()){Column(Modifier.padding(d(18f))){Text("Temperature & humidity history",color=Color.White,fontSize=t(23f),fontWeight=FontWeight.Bold);Text("Latest 200 readings · retained for 30 days",color=DashboardTokens.Muted,fontSize=t(12f));Spacer(Modifier.height(d(12f)));if(history.isEmpty())Text("History will populate after live H5179 readings are received.",color=DashboardTokens.Muted,fontSize=t(15f));LazyColumn(verticalArrangement=Arrangement.spacedBy(d(4f))){items(history){r->Row(Modifier.fillMaxWidth().background(Color(0xFF07171B),RoundedCornerShape(d(8f))).padding(horizontal=d(14f),vertical=d(8f))){Text(r.sensorName,color=Color.White,fontSize=t(14f),modifier=Modifier.weight(1f));Text("%.1f°F".format(r.temperatureF),color=DashboardTokens.Green,fontSize=t(15f),modifier=Modifier.width(d(100f)));Text("%.0f%%".format(r.humidityPercent),color=DashboardTokens.Blue,fontSize=t(15f),modifier=Modifier.width(d(80f)));Text(DateTimeFormatter.ofPattern("MMM d, h:mm a").format(Instant.ofEpochMilli(r.timestampEpochMs).atZone(ZoneId.systemDefault())),color=DashboardTokens.Muted,fontSize=t(12f))}}}}
    }
}

@Composable private fun CameraContent(d:(Float)->androidx.compose.ui.unit.Dp,t:(Float)->androidx.compose.ui.unit.TextUnit){Row(Modifier.fillMaxSize(),horizontalArrangement=Arrangement.spacedBy(d(16f))){CameraPreview(Modifier.weight(.7f).fillMaxHeight());SurfacePanel(Modifier.weight(.3f).fillMaxHeight()){Column(Modifier.padding(d(18f))){Text("Greenhouse Camera",color=Color.White,fontSize=t(22f),fontWeight=FontWeight.Bold);Spacer(Modifier.height(d(10f)));Text("Demonstration preview",color=DashboardTokens.Green,fontSize=t(14f));Text("Streaming remains disabled until a camera source is configured in a future camera integration.",color=DashboardTokens.Muted,fontSize=t(13f),modifier=Modifier.padding(top=d(8f)))}}}}

@Composable private fun SettingsContent(state:GreenhouseUiState,d:(Float)->androidx.compose.ui.unit.Dp,t:(Float)->androidx.compose.ui.unit.TextUnit,onSave:(String)->Unit,onRefresh:()->Unit){
    var key by remember{mutableStateOf("")}
    Row(Modifier.fillMaxSize(),horizontalArrangement=Arrangement.spacedBy(d(16f))){
        SurfacePanel(Modifier.weight(1f).fillMaxHeight()){Column(Modifier.padding(d(18f))){Text("Govee H5179 setup",color=Color.White,fontSize=t(22f),fontWeight=FontWeight.Bold);Text("In Govee Home: Profile → Settings → Apply for API Key. Paste the key below. It is encrypted with Android Keystore and never included in the APK.",color=DashboardTokens.Muted,fontSize=t(13f));OutlinedTextField(key,{key=it},label={Text("Govee API key")},visualTransformation=PasswordVisualTransformation(),singleLine=true,modifier=Modifier.fillMaxWidth().padding(vertical=d(10f)),colors=OutlinedTextFieldDefaults.colors(focusedTextColor=Color.White,unfocusedTextColor=Color.White,focusedBorderColor=DashboardTokens.Green,focusedLabelColor=DashboardTokens.Green));Row{Button(onClick={onSave(key);key=""},modifier=Modifier.weight(1f),colors=ButtonDefaults.buttonColors(containerColor=DashboardTokens.Green,contentColor=DashboardTokens.Background),contentPadding=PaddingValues(horizontal=d(4f))){Text("Save & discover",fontSize=t(13f))};Spacer(Modifier.width(d(8f)));OutlinedButton(onClick=onRefresh,modifier=Modifier.weight(.65f),contentPadding=PaddingValues(horizontal=d(3f))){Text(if(state.refreshing)"Refreshing…" else "Refresh",color=DashboardTokens.Green,fontSize=t(12f))}};Spacer(Modifier.height(d(8f)));Text(if(state.goveeConnected)"Govee account connected · ${state.sensors.size} H5179 found" else "Not connected",color=if(state.goveeConnected)DashboardTokens.Green else DashboardTokens.Muted,fontSize=t(13f))}}
        SurfacePanel(Modifier.weight(1f).fillMaxHeight()){Column(Modifier.padding(d(18f))){Text("Remote monitoring & alerts",color=Color.White,fontSize=t(22f),fontWeight=FontWeight.Bold);Text("Install this APK on another Android phone and connect the same Govee account to monitor readings through Govee's authenticated cloud API. Each phone stores its own 30-day history.",color=DashboardTokens.Muted,fontSize=t(13f));Spacer(Modifier.height(d(12f)));Detail("Foreground refresh","2 minutes",d,t);Detail("Background refresh","15 minutes",d,t);Detail("High temperature","95°F",d,t);Detail("Low temperature","45°F",d,t);Detail("Humidity range","30–85%",d,t);Text("Android notifications are raised when a live sensor moves outside these thresholds. Location is used only to request local weather from Open-Meteo.",color=DashboardTokens.Muted,fontSize=t(12f),modifier=Modifier.padding(top=d(10f)))}}
    }
}

@Composable private fun SurfacePanel(modifier:Modifier,content:@Composable BoxScope.()->Unit){Box(modifier.background(Color(0xE908191D),RoundedCornerShape(16.dp)).border(1.dp,DashboardTokens.PanelEdge,RoundedCornerShape(16.dp)),content=content)}
@Composable private fun Detail(label:String,value:String,d:(Float)->androidx.compose.ui.unit.Dp,t:(Float)->androidx.compose.ui.unit.TextUnit){Row(Modifier.fillMaxWidth().padding(vertical=d(6f))){Text(label,color=DashboardTokens.Muted,fontSize=t(13f),modifier=Modifier.weight(1f));Text(value,color=Color.White,fontSize=t(14f),fontWeight=FontWeight.SemiBold)}}
private fun zoneColor(index:Int)=listOf(DashboardTokens.Green,DashboardTokens.Blue,Color(0xFFFF9418),Color(0xFFA839F4))[index%4]
