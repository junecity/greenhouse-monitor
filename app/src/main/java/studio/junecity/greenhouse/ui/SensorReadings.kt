package studio.junecity.greenhouse.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val sensorAccents = listOf(0xFFFFBF20, 0xFF19EF8A, 0xFF10BFF3, 0xFFAE55ED,
    0xFFFF7994, 0xFFB8EA51, 0xFF82A0FF, 0xFFFF9962, 0xFF53E4C4, 0xFFE98DF3).map { Color(it) }

internal fun sensorSlotColor(slot: Int): Color {
    sensorAccents.getOrNull(slot)?.let { return it }
    val index = slot - sensorAccents.size
    val hue = ((index.toLong() * 137 + 17) % 360).toFloat()
    val saturation = .55f + ((index / 360) % 4) * .10f
    val value = .97f - ((index / 1440) % 3) * .09f
    return Color.hsv(hue, saturation, value)
}

internal fun sensorAccent(id: String) = sensorSlotColor(studio.junecity.greenhouse.model.originalSensorSlot(id))
internal val LocalSensorSlots = compositionLocalOf<Map<String, Int>> { emptyMap() }
internal fun Map<String, Int>.accent(id: String) = this[id]?.let(::sensorSlotColor) ?: sensorAccent(id)

@Composable
internal fun sensorSlots(state: studio.junecity.greenhouse.model.GreenhouseUiState): Map<String, Int> =
    remember(state.sensorColorSlots, state.sensors, state.history, state.dailyHistory) {
        val ids = linkedSetOf<String>()
        state.sensors.mapTo(ids) { it.id }
        state.history.mapTo(ids) { it.sensorId }
        state.dailyHistory.mapTo(ids) { it.sensorId }
        studio.junecity.greenhouse.model.assignSensorSlots(state.sensorColorSlots, ids)
    }

@Composable
internal fun SensorReadingRow(id: String, title: String, subtitle: String, reading: String) {
    val accent = LocalSensorSlots.current.accent(id)
    val shape = RoundedCornerShape(9.dp)
    Row(
        Modifier.fillMaxWidth().background(DashboardTokens.PanelRaised, shape)
            .border(1.dp, DashboardTokens.PanelEdge, shape).padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Spacer(Modifier.size(4.dp, 25.dp).background(accent, RoundedCornerShape(3.dp)))
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 14.sp, lineHeight = 16.sp,
                fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(subtitle, color = DashboardTokens.Muted, fontSize = 12.sp, lineHeight = 14.sp)
        }
        Text(reading, color = accent, fontWeight = FontWeight.Bold, fontSize = 17.sp)
    }
}

@Composable
internal fun PanelAction(label: String, modifier: Modifier = Modifier, action: () -> Unit) {
    OutlinedButton(
        onClick = action, modifier = modifier, shape = RoundedCornerShape(9.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, DashboardTokens.PanelEdge),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = DashboardTokens.PanelRaised,
            contentColor = DashboardTokens.Amber),
        contentPadding = PaddingValues(horizontal = 5.dp, vertical = 4.dp)
    ) {
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
internal fun ZoneTools(onVolume: () -> Unit, onCompare: () -> Unit, onReplay: () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth >= 200.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                PanelAction("3D view", Modifier.weight(1f), onVolume)
                PanelAction("Compare", Modifier.weight(1f), onCompare)
                PanelAction("Replay", Modifier.weight(1f), onReplay)
            }
        } else {
            var expanded by remember { mutableStateOf(false) }
            PanelAction("Zone tools ▾", Modifier.fillMaxWidth()) { expanded = true }
            DropdownMenu(expanded, { expanded = false }) {
                listOf("3D view" to onVolume, "Compare" to onCompare, "Replay" to onReplay).forEach { (title, open) ->
                    DropdownMenuItem(text = { Text(title) }, onClick = { expanded = false; open() })
                }
            }
        }
    }
}
