package studio.junecity.greenhouse.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

enum class DashboardIcon { House, Thermometer, Drop, Wifi, Battery, CloudSun, Camera, Grid, Chart, Gear, Flame, Snowflake, Fan, Leaf }

@Composable
fun DashboardIconView(icon: DashboardIcon, tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width; val h = size.height; val s = minOf(w, h); val sw = s * .09f
        when (icon) {
            DashboardIcon.House -> {
                val roof = Path().apply { moveTo(w*.08f,h*.43f); lineTo(w*.5f,h*.08f); lineTo(w*.92f,h*.43f); close() }
                drawPath(roof,tint); drawRect(tint,Offset(w*.2f,h*.4f),Size(w*.6f,h*.5f))
                drawRect(DashboardTokens.Background,Offset(w*.43f,h*.63f),Size(w*.18f,h*.27f))
            }
            DashboardIcon.Thermometer -> {
                drawRoundRect(tint,Offset(w*.38f,h*.08f),Size(w*.24f,h*.62f),cornerRadius=androidx.compose.ui.geometry.CornerRadius(w*.12f),style=Stroke(sw))
                drawCircle(tint,s*.22f,Offset(w*.5f,h*.74f),style=Stroke(sw)); drawLine(tint,Offset(w*.5f,h*.28f),Offset(w*.5f,h*.72f),sw,StrokeCap.Round)
            }
            DashboardIcon.Drop -> {
                val p=Path().apply { moveTo(w*.5f,h*.03f); cubicTo(w*.25f,h*.36f,w*.17f,h*.48f,w*.17f,h*.67f); cubicTo(w*.17f,h*.89f,w*.32f,h*.98f,w*.5f,h*.98f); cubicTo(w*.68f,h*.98f,w*.83f,h*.89f,w*.83f,h*.67f); cubicTo(w*.83f,h*.48f,w*.75f,h*.36f,w*.5f,h*.03f); close() }
                drawPath(p,tint)
                drawArc(Color.White.copy(alpha=.85f),15f,80f,false,topLeft=Offset(w*.34f,h*.58f),size=Size(w*.29f,h*.27f),style=Stroke(sw*.55f,cap=StrokeCap.Round))
            }
            DashboardIcon.Wifi -> {
                for(i in 0..2) drawArc(tint,220f,100f,false,topLeft=Offset(w*(.08f+i*.13f),h*(.08f+i*.13f)),size=Size(w*(.84f-i*.26f),h*(.74f-i*.16f)),style=Stroke(sw,cap=StrokeCap.Round))
                drawCircle(tint,s*.08f,Offset(w*.5f,h*.83f))
            }
            DashboardIcon.Battery -> {
                drawRoundRect(tint,Offset(w*.2f,h*.13f),Size(w*.6f,h*.75f),androidx.compose.ui.geometry.CornerRadius(s*.06f),style=Stroke(sw))
                drawRect(tint,Offset(w*.4f,h*.03f),Size(w*.2f,h*.1f)); val bolt=Path().apply{moveTo(w*.54f,h*.26f);lineTo(w*.38f,h*.55f);lineTo(w*.5f,h*.55f);lineTo(w*.44f,h*.78f);lineTo(w*.65f,h*.46f);lineTo(w*.53f,h*.46f);close()};drawPath(bolt,tint)
            }
            DashboardIcon.CloudSun -> {
                drawCircle(DashboardTokens.Amber,s*.25f,Offset(w*.55f,h*.35f))
                repeat(8){i->val a=i*PI/4;drawLine(DashboardTokens.Amber,Offset(w*.55f+(cos(a)*s*.34f).toFloat(),h*.35f+(sin(a)*s*.34f).toFloat()),Offset(w*.55f+(cos(a)*s*.45f).toFloat(),h*.35f+(sin(a)*s*.45f).toFloat()),sw*.6f,StrokeCap.Round)}
                drawOval(tint,topLeft=Offset(w*.14f,h*.5f),size=Size(w*.72f,h*.38f));drawCircle(tint,s*.21f,Offset(w*.42f,h*.58f));drawCircle(tint,s*.25f,Offset(w*.62f,h*.54f))
            }
            DashboardIcon.Camera -> { drawRoundRect(tint,Offset(w*.08f,h*.25f),Size(w*.84f,h*.62f),androidx.compose.ui.geometry.CornerRadius(s*.08f),style=Stroke(sw));drawCircle(tint,s*.2f,Offset(w*.5f,h*.56f),style=Stroke(sw));drawRect(tint,Offset(w*.32f,h*.15f),Size(w*.2f,h*.12f)) }
            DashboardIcon.Grid -> repeat(2){x->repeat(2){y->drawRoundRect(tint,Offset(w*(.13f+x*.45f),h*(.13f+y*.45f)),Size(w*.29f,h*.29f),androidx.compose.ui.geometry.CornerRadius(s*.04f),style=Stroke(sw*.8f))}}
            DashboardIcon.Chart -> { for(i in 0..2){val x=w*(.2f+i*.3f);drawRoundRect(tint,Offset(x,h*(.63f-i*.18f)),Size(w*.12f,h*(.25f+i*.18f)),androidx.compose.ui.geometry.CornerRadius(s*.05f),style=Stroke(sw*.75f))} }
            DashboardIcon.Gear -> { drawCircle(tint,s*.34f,Offset(w*.5f,h*.5f),style=Stroke(sw*1.5f));drawCircle(tint,s*.1f,Offset(w*.5f,h*.5f)); repeat(8){i->val a=i*PI/4;drawLine(tint,Offset(w*.5f+(cos(a)*s*.34f).toFloat(),h*.5f+(sin(a)*s*.34f).toFloat()),Offset(w*.5f+(cos(a)*s*.46f).toFloat(),h*.5f+(sin(a)*s*.46f).toFloat()),sw*1.4f,StrokeCap.Square)} }
            DashboardIcon.Flame -> {
                val p=Path().apply{moveTo(w*.52f,h*.05f);cubicTo(w*.60f,h*.30f,w*.82f,h*.38f,w*.79f,h*.66f);cubicTo(w*.76f,h*.89f,w*.62f,h*.97f,w*.48f,h*.97f);cubicTo(w*.28f,h*.97f,w*.17f,h*.82f,w*.20f,h*.62f);cubicTo(w*.23f,h*.43f,w*.39f,h*.35f,w*.52f,h*.05f);close()};drawPath(p,tint)
                val inner=Path().apply{moveTo(w*.51f,h*.46f);cubicTo(w*.62f,h*.61f,w*.64f,h*.72f,w*.58f,h*.82f);cubicTo(w*.52f,h*.91f,w*.39f,h*.88f,w*.37f,h*.77f);cubicTo(w*.35f,h*.67f,w*.43f,h*.58f,w*.51f,h*.46f);close()};drawPath(inner,DashboardTokens.Panel)
            }
            DashboardIcon.Snowflake -> repeat(3){i->val a=i*PI/3;val dx=(cos(a)*s*.38f).toFloat();val dy=(sin(a)*s*.38f).toFloat();drawLine(tint,Offset(w*.5f-dx,h*.5f-dy),Offset(w*.5f+dx,h*.5f+dy),sw,StrokeCap.Round)}
            DashboardIcon.Fan -> {
                drawCircle(tint,s*.08f,Offset(w*.5f,h*.5f))
                repeat(3){i->val a=i*2*PI/3;val cx=w*.5f+(cos(a)*s*.22f).toFloat();val cy=h*.5f+(sin(a)*s*.22f).toFloat();drawOval(tint,Offset(cx-s*.12f,cy-s*.24f),Size(s*.24f,s*.36f))}
            }
            DashboardIcon.Leaf -> {
                val p=Path().apply{moveTo(w*.18f,h*.72f);cubicTo(w*.18f,h*.25f,w*.53f,h*.09f,w*.88f,h*.12f);cubicTo(w*.90f,h*.50f,w*.70f,h*.84f,w*.36f,h*.82f);cubicTo(w*.28f,h*.82f,w*.22f,h*.78f,w*.18f,h*.72f);close()};drawPath(p,tint,style=Stroke(sw,cap=StrokeCap.Round));drawLine(tint,Offset(w*.18f,h*.88f),Offset(w*.67f,h*.37f),sw,StrokeCap.Round)
            }
        }
    }
}
