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

// Preserve the original Zones assignments, including sensors with negative ID hashes.
private val sensorAccents = listOf(Color(0xFFFFBF20), Color(0xFF19EF8A), Color(0xFF10BFF3), Color(0xFFAE55ED))

internal fun sensorAccent(id: String): Color {
    val hash = kotlin.math.abs(id.hashCode().toLong())
    return sensorAccents[(hash % sensorAccents.size).toInt()]
}

@Composable
internal fun SensorReadingRow(id: String, title: String, subtitle: String, reading: String) {
    val accent = sensorAccent(id)
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
