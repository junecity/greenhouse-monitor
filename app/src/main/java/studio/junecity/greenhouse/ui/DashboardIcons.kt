package studio.junecity.greenhouse.ui

import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** Native Compose vectors transcribed from Greenhouse_Individual_Icon_Assets.zip. */
enum class DashboardIcon(val assetName:String,val paths:List<String>){
    House("house",listOf("M3 10 12 3l9 7v10a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1Z","M9 21v-8h6v8")),
    Layers("layers",listOf("m12 2 10 6-10 6L2 8Z","m2 12 10 6 10-6M2 16l10 6 10-6")),
    ChartBars("chart-bars",listOf("M4 13h2a1 1 0 0 1 1 1v6a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1v-6a1 1 0 0 1 1-1Z","M11 8h2a1 1 0 0 1 1 1v11a1 1 0 0 1-1 1h-2a1 1 0 0 1-1-1V9a1 1 0 0 1 1-1Z","M18 3h2a1 1 0 0 1 1 1v16a1 1 0 0 1-1 1h-2a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1Z")),
    Camera("camera",listOf("M3 7h4l2-3h6l2 3h4v13H3Z","M12 9a4 4 0 1 0 0 8 4 4 0 1 0 0-8")),
    Settings("settings",listOf("M12 9a3 3 0 1 0 0 6 3 3 0 1 0 0-6","M9.5 2.7h3.4l.595 2.55 1.87 .85 2.21-1.19 2.295 2.295-1.19 2.21 .85 1.87 2.55 .595v3.4l-2.55 .595-.85 1.87 1.19 2.21-2.295 2.295-2.21-1.19-1.87 .85-.595 2.55h-3.4l-.595-2.55-1.87-.85-2.21 1.19-2.295-2.295 1.19-2.21-.85-1.87-2.55-.595v-3.4l2.55-.595.85-1.87-1.19-2.21 2.295-2.295 2.21 1.19 1.87-.85Z")),
    Thermometer("thermometer",listOf("M10 14V5a3 3 0 0 1 6 0v9a5 5 0 1 1-6 0Z","M13 8v10","M13 17a1 1 0 1 0 0 2 1 1 0 1 0 0-2")),
    Droplet("droplet",listOf("M12 2C9 7 5 11 5 15a7 7 0 0 0 14 0c0-4-4-8-7-13Z")),
    Wifi("wifi",listOf("M2 9a15 15 0 0 1 20 0M5 13a10 10 0 0 1 14 0M9 17a4 4 0 0 1 6 0","M12 20a1 1 0 1 0 0 2 1 1 0 1 0 0-2")),
    CloudSun("cloud-sun",listOf("M16 4a3 3 0 1 0 0 6 3 3 0 1 0 0-6","M16 1v2M22 7h-2M20 3l-1 1M6 20a5 5 0 0 1-1-10 7 7 0 0 1 13-1 5.5 5.5 0 0 1 0 11Z")),
    Cloud("cloud",listOf("M6 19a5 5 0 0 1-1-10 7 7 0 0 1 13-1 5.5 5.5 0 0 1 0 11Z")),
    Sun("sun",listOf("M12 8a4 4 0 1 0 0 8 4 4 0 1 0 0-8","M12 2v3M12 19v3M2 12h3M19 12h3M5 5l2 2m10 10 2 2M19 5l-2 2M7 17l-2 2")),
    Rain("rain",listOf("M6 16a5 5 0 0 1-1-10 7 7 0 0 1 13-1 5.5 5.5 0 0 1 0 11Z","m7 19-1 3m6-3-1 3m6-3-1 3")),
    Snow("snow",listOf("M6 15a5 5 0 0 1-1-10 7 7 0 0 1 13-1 5.5 5.5 0 0 1 0 11Z","M7 19v4m-2-2h4m6-2v4m-2-2h4")),
    Storm("storm",listOf("M6 15a5 5 0 0 1-1-10 7 7 0 0 1 13-1 5.5 5.5 0 0 1 0 11Z","m13 14-4 5h4l-2 4 6-7h-4")),
    Flame("flame",listOf("M12 22c-5 0-8-4-8-8 0-4 4-7 5-11 2 2 2 5 2 6 3-1 4-4 4-6 4 4 5 8 5 11 0 4-3 8-8 8Z","M12 21c-2 0-3-2-3-4 0-2 2-3 3-5 1 2 3 3 3 5 0 2-1 4-3 4Z")),
    Snowflake("snowflake",listOf("M12 2v20M2 12h20M5 5l14 14M19 5 5 19M8 2l4 4 4-4M8 22l4-4 4 4")),
    Fan("fan",listOf("M12 10C8 2 16 1 18 5c1 3-1 5-4 6M14 12c9-3 10 5 6 7-3 2-5 0-7-5M11 14c-2 9-9 7-10 3-1-3 2-5 8-5","M12 10a2 2 0 1 0 0 4 2 2 0 1 0 0-4")),
    Gauge("gauge",listOf("M4 17a9 9 0 1 1 16 0","m12 14 5-6","M12 13a1 1 0 1 0 0 2 1 1 0 1 0 0-2")),
    HeatMap("heat-map",listOf("M7 3h10a4 4 0 0 1 4 4v10a4 4 0 0 1-4 4H7a4 4 0 0 1-4-4V7a4 4 0 0 1 4-4Z")),
    Calendar("calendar",listOf("M5 5h14a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2Z","M7 2v6m10-6v6M3 10h18")),
    Ruler("ruler",listOf("M4 3h6v18H4Zm10 12h7v6h-7Z","M4 7h3m-3 4h3m-3 4h3m7 3h3")),
    Bell("bell",listOf("M5 17h14l-2-3V9a5 5 0 0 0-10 0v5Z","M10 20a2 2 0 0 0 4 0")),
    Signal("signal",listOf("M3 16h3v5H3Z","M8 12h3v9H8Z","M13 8h3v13h-3Z","M18 4h3v17h-3Z")),
    Sliders("sliders",listOf("M3 6h18M3 12h18M3 18h18","M8 4a2 2 0 1 0 0 4 2 2 0 1 0 0-4","M16 10a2 2 0 1 0 0 4 2 2 0 1 0 0-4","M10 16a2 2 0 1 0 0 4 2 2 0 1 0 0-4")),
    Resize("resize",listOf("M6 5h12a1 1 0 0 1 1 1v12a1 1 0 0 1-1 1H6a1 1 0 0 1-1-1V6a1 1 0 0 1 1-1Z","M2 2h5v5H2ZM17 17h5v5h-5Z")),
    Download("download",listOf("M12 3v13m-5-5 5 5 5-5M4 18v3h16v-3")),
    Info("info",listOf("M12 3a9 9 0 1 0 0 18 9 9 0 1 0 0-18","M12 11v6m0-10v1")),
    Video("video",listOf("M4 5h10a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2Z","m16 10 6-4v12l-6-4Z")),
    Play("play",listOf("M12 3a9 9 0 1 0 0 18 9 9 0 1 0 0-18","m10 8 6 4-6 4Z")),
    Refresh("refresh",listOf("M20 8a9 9 0 0 0-15-2L3 8m0-5v5h5M4 16a9 9 0 0 0 15 2l2-2m0 5v-5h-5")),
    Check("check",listOf("M12 3a9 9 0 1 0 0 18 9 9 0 1 0 0-18","m7 12 3 3 7-7")),
    Plus("plus",listOf("M12 3v18M3 12h18"));
}

@Composable
fun DashboardIconView(icon:DashboardIcon,tint:Color,modifier:Modifier=Modifier,contentDescription:String?=icon.assetName){
    val vector=remember(icon){
        ImageVector.Builder(icon.assetName,24.dp,24.dp,24f,24f).apply{
            icon.paths.forEach{data->addPath(pathData=PathParser().parsePathString(data).toNodes(),fill=null,stroke=SolidColor(Color.White),strokeLineWidth=1.8f,strokeLineCap=StrokeCap.Round,strokeLineJoin=StrokeJoin.Round)}
        }.build()
    }
    Icon(vector,contentDescription,modifier,tint)
}
