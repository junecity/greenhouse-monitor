package studio.junecity.greenhouse.ui

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import studio.junecity.greenhouse.data.ZoneClimate
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import studio.junecity.greenhouse.model.*
import java.util.Locale
import kotlin.math.*

private fun Double.airNumber(digits: Int = 0) = String.format(Locale.getDefault(), "%,.${digits}f", this)

@Composable internal fun FanAirflowDialog(house: GreenhouseConfig, save: ((String, List<CirculationFan>) -> Unit)?, onDismiss: () -> Unit) {
    var fans by remember(house.id) { mutableStateOf(house.circulationFans) }
    var editAim by remember { mutableStateOf<Int?>(null) }
    var details by remember { mutableStateOf(false) }
    val estimate = remember(house, fans) { house.airCirculation(fans) }
    fun update(index: Int, fan: CirculationFan) {
        fans = fans.mapIndexed { i, old -> if (i == index) fan else old }
    }
    Dialog(onDismissRequest = { if (editAim != null) editAim = null else onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth(.96f).fillMaxHeight(.94f), shape = RoundedCornerShape(20.dp), color = DashboardTokens.Panel) {
            val index = editAim
            if (index != null) FanDirectionEditor(house, fans, index, { editAim = null }) { fan ->
                update(index, fan)
                editAim = null
            } else Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Air circulation", fontWeight = FontWeight.Bold, fontSize = 22.sp, color = Color.White)
                        Text(house.name, color = DashboardTokens.Muted, fontSize = 14.sp)
                    }
                    TextButton(onClick = onDismiss) { Text("Close", color = DashboardTokens.Muted) }
                    if (save != null) TextButton(onClick = { save(house.id, fans); onDismiss() }) {
                        Text("Save setup", color = DashboardTokens.Amber)
                    }
                }
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(.4f).fillMaxHeight().background(DashboardTokens.PanelRaised, RoundedCornerShape(14.dp))
                        .verticalScroll(rememberScrollState()).padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Estimated airflow", color = DashboardTokens.Muted)
                        Text("${estimate.combinedCfm.airNumber()} CFM", color = DashboardTokens.Amber, fontSize = 25.sp, fontWeight = FontWeight.Bold)
                        Text("${estimate.volumesPerHour.airNumber(1)} greenhouse volumes/hr", color = Color.White)
                        estimate.minutesPerVolume?.let { Text("One volume in ${it.airNumber(1)} min", color = Color.White) }
                        estimate.discharge?.takeIf { estimate.combinedCfm > 0 }?.let { force ->
                            val direction = force.heading?.let { "${it.roundToInt() % 360}° from plan top" } ?: "Opposing flows balance"
                            Text("Net outlet momentum", color = DashboardTokens.Muted)
                            Text("${force.newtons.airNumber(2)} N · $direction", color = Color.White)
                        }
                        TextButton(onClick = { details = !details }) { Text(if (details) "Hide assumptions" else "How it’s estimated", color = DashboardTokens.Amber) }
                        if (details) {
                            Text("Two equal fans share a high-speed sizing estimate of 2 CFM per sq ft. Low uses 50% and medium 75%. Outlet speeds assume a 12-inch diameter.", color = Color.White)
                            Text("Interior volume ≈ ${estimate.volumeFt3.airNumber()} ft³, using 90% of peak height for each section. Recirculated air can pass through both fans. Actual air speed at a plant depends on distance and obstacles.", color = DashboardTokens.Muted)
                            Text("Net momentum combines the two outlet jets by speed and aim. Opposing jets can balance while both fans keep moving air.", color = DashboardTokens.Muted)
                            estimate.outlets.forEachIndexed { i, flow -> Text("Fan ${i+1} outlet dynamic pressure ≈ ${flow.dynamicPressurePa.airNumber(1)} Pa", color = DashboardTokens.Muted) }
                        }
                    }
                    Column(Modifier.weight(.6f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(if (save == null) "Preview settings · save on the greenhouse phone" else "Match the speeds set on your fans.", color = DashboardTokens.Muted)
                        fans.forEachIndexed { i, fan ->
                            Column(Modifier.fillMaxWidth().background(DashboardTokens.PanelRaised, RoundedCornerShape(14.dp)).padding(horizontal = 8.dp, vertical = 4.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("Fan ${i + 1}", Modifier.weight(1f), color = Color.White, fontWeight = FontWeight.Bold)
                                    TextButton(onClick = { editAim = i }) {
                                        Text("Edit height & direction", color = DashboardTokens.Amber)
                                    }
                                }
                                Text("${planNumber(fan.heightFeet)} ft high · ${fan.aim?.let { "${planNumber(it.degrees)}°" } ?: "Position unset"}", color = DashboardTokens.Muted, fontSize = 14.sp)
                                Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                                    FanSpeed.entries.forEach { speed ->
                                        val selected = speed == fan.speed
                                        Box(Modifier.weight(1f).heightIn(min = 48.dp)
                                            .background(if (selected) DashboardTokens.Amber else DashboardTokens.Panel, RoundedCornerShape(8.dp))
                                            .selectable(selected, role = Role.RadioButton) { update(i, fan.copy(speed = speed)) }
                                            .semantics { contentDescription = "Fan ${i + 1} ${speed.name}" },
                                            contentAlignment = Alignment.Center) {
                                            Text(speed.name, color = if (selected) Color.Black else Color.White, fontSize = 14.sp)
                                        }
                                    }
                                }
                                estimate.placed.find { it.index == i }?.let { fanPath ->
                                    val crossing = if (fanPath.acrossJunction) " · across junction" else ""
                                    Text("${fanPath.wallFeet.airNumber(1)} ft to wall along aim$crossing", color = DashboardTokens.Muted, fontSize = 14.sp)
                                }
                                val outlet = estimate.outlets[i]
                                Text("${outlet.cfm.airNumber()} CFM · ${outlet.milesPerHour.airNumber(1)} mph at outlet", color = DashboardTokens.Muted, fontSize = 14.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

private class PlanFrame(size: Size, padding: Float, house: GreenhouseConfig) {
    private val scale = min((size.width - 2 * padding) / house.planLengthFeet, (size.height - 2 * padding) / house.planWidthFeet).coerceAtLeast(0.001)
    private val w = (scale * house.planLengthFeet).toFloat()
    private val h = (scale * house.planWidthFeet).toFloat()
    private val left = (size.width - w) / 2
    private val top = (size.height - h) / 2
    fun point(p: NormalizedPosition) = Offset(left + w * p.x.toFloat(), top + h * p.y.toFloat())
    fun normalized(p: Offset) = NormalizedPosition(((p.x - left) / w).toDouble(), ((p.y - top) / h).toDouble())
}

@Composable internal fun FanDirectionEditor(house: GreenhouseConfig, fans: List<CirculationFan>, index: Int,
    onBack: () -> Unit, onApply: (CirculationFan) -> Unit) {
    var start by remember { mutableStateOf<NormalizedPosition?>(null) }
    val saved = fans[index]
    var origin by remember { mutableStateOf(saved.aim?.origin) }
    var headingText by remember { mutableStateOf(saved.aim?.let { planNumber(it.degrees) }.orEmpty()) }
    var heightText by remember { mutableStateOf(planNumber(saved.heightFeet)) }
    var hint by remember { mutableStateOf(if (origin == null) "Tap the fan’s location inside the floor plan." else "Tap the plan to move this fan.") }
    val heading = headingText.replace(',', '.').toDoubleOrNull()?.takeIf { it in 0.0..360.0 }
    val mount = heightText.replace(',', '.').toDoubleOrNull()
    val location = start ?: origin
    val sectionHeight = location?.let { ZoneClimate.sectionHeight(house, it.y * house.planWidthFeet) } ?: ZoneClimate.height(house)
    val validHeight = mount != null && mount in 0.0..sectionHeight
    val candidate = if (location != null && heading != null) {
        saved.aim?.takeIf { it.origin == location && headingText == planNumber(it.degrees) }
            ?: FanAim(location, heading % 360)
    } else null
    val validAim = candidate != null || (location == null && headingText.isBlank())
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Fan ${index + 1} setup", Modifier.weight(1f), color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            TextButton(onClick = onBack) { Text("Cancel", color = DashboardTokens.Muted) }
            TextButton(enabled = validHeight && validAim, onClick = { onApply(saved.copy(aim = candidate, heightFeet = requireNotNull(mount))) }) { Text("Apply", color = if (validHeight && validAim) DashboardTokens.Amber else DashboardTokens.Muted) }
        }
        val plan: @Composable (Modifier) -> Unit = { modifier ->
            Column(modifier) {
                Text(hint, color = DashboardTokens.Muted)
                Canvas(Modifier.fillMaxWidth().weight(1f).background(DashboardTokens.PanelRaised, RoundedCornerShape(12.dp))
                    .semantics { contentDescription = "Fan direction floor plan" }
                    .pointerInput(house.id, start) {
                        detectTapGestures { tap ->
                            val frame = PlanFrame(Size(size.width.toFloat(), size.height.toFloat()), 32.dp.toPx(), house)
                            val point = frame.normalized(tap)
                            val pending = start
                            if (pending == null) {
                                if (house.containsPoint(point.x * house.planLengthFeet, point.y * house.planWidthFeet)) {
                                    start = point
                                    hint = "Tap where the fan blows or enter a direction."
                                } else hint = "Place the fan inside the greenhouse outline."
                            } else {
                                val aim = house.fanAim(pending, point)
                                if (aim != null) {
                                    origin = aim.origin
                                    headingText = planNumber(aim.degrees)
                                    start = null
                                    hint = "${aim.degrees.roundToInt() % 360}° from plan top · Apply or tap to reposition."
                                } else hint = "Tap farther from the fan to set its direction."
                            }
                        }
                    }) {
                    val frame = PlanFrame(size, 32.dp.toPx(), house)
                    val path = Path()
                    house.outline().forEachIndexed { i, vertex ->
                        val p = frame.point(vertex)
                        if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
                    }
                    path.close()
                    drawPath(path, Color(0xff203339))
                    drawPath(path, DashboardTokens.Muted, style = Stroke(1.5.dp.toPx()))
                    fans.forEachIndexed { i, fan ->
                        if (i == index && candidate == null) return@forEachIndexed
                        val aim = (if (i == index) candidate else fan.aim) ?: return@forEachIndexed
                        val p = frame.point(aim.origin)
                        val theta = Math.toRadians(aim.degrees)
                        val axis = Offset(sin(theta).toFloat(), -cos(theta).toFloat())
                        val side = Offset(-axis.y, axis.x)
                        val tip = p + axis * 28.dp.toPx()
                        val tint = if (i == 0) DashboardTokens.Amber else DashboardTokens.Blue
                        drawCircle(tint, 4.dp.toPx(), p)
                        if (i == index) drawCircle(Color.White, 7.dp.toPx(), p, style = Stroke(1.dp.toPx()))
                        drawLine(tint, p, tip, 2.5.dp.toPx())
                        for (sign in listOf(-1, 1)) drawLine(tint, tip, tip - axis * 8.dp.toPx() + side * (sign * 5.dp.toPx()), 2.5.dp.toPx())
                    }
                    start?.takeIf { candidate == null }?.let { drawCircle(if (index == 0) DashboardTokens.Amber else DashboardTokens.Blue, 5.dp.toPx(), frame.point(it)) }
                }
            }
        }
        val fieldColors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = Color.White, unfocusedTextColor = Color.White, errorTextColor = Color.White,
            focusedLabelColor = DashboardTokens.Amber, unfocusedLabelColor = DashboardTokens.Muted,
            focusedBorderColor = DashboardTokens.Amber, unfocusedBorderColor = DashboardTokens.Muted,
            cursorColor = DashboardTokens.Amber)
        val fields: @Composable (Modifier) -> Unit = { modifier ->
            Column(modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(heightText, { heightText = it },
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Fan height in feet" },
                    label = { Text("Height above floor (ft)") }, singleLine = true,
                    colors = fieldColors, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), isError = !validHeight)
                if (!validHeight) Text("Use 0–${planNumber(sectionHeight)} ft for this section.", color = DashboardTokens.Amber)
                OutlinedTextField(headingText, { headingText = it },
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Fan direction in degrees" },
                    label = { Text("Direction (°)") }, singleLine = true,
                    colors = fieldColors, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    isError = headingText.isNotBlank() && heading == null)
                Text(if (headingText.isNotBlank() && heading == null) "Enter 0–360°."
                    else "Clockwise from plan top · 0° top, 90° right, 180° bottom, 270° left.",
                    fontSize = 13.sp, color = DashboardTokens.Muted)
            }
        }
        BoxWithConstraints(Modifier.fillMaxSize()) {
            if (maxWidth >= 480.dp) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    plan(Modifier.weight(1f).fillMaxHeight())
                    fields(Modifier.weight(.85f))
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    plan(Modifier.weight(1f).fillMaxWidth())
                    fields(Modifier.fillMaxWidth().heightIn(max = this@BoxWithConstraints.maxHeight * .5f))
                }
            }
        }
    }
}
