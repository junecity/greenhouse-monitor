package studio.junecity.greenhouse.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.TextUnit
import studio.junecity.greenhouse.model.GoveeSensor

@Composable
internal fun SensorBatteryLabel(sensor: GoveeSensor, fontSize: TextUnit, compact: Boolean = false) {
    val percent = sensor.batteryPercent
    val low = sensor.batteryLow == true || (percent != null && percent <= 20)
    val value = when {
        percent != null -> "${percent}%" + if (low) " · Low" else ""
        low -> "Low"
        compact -> return
        else -> "Unavailable"
    }
    val stale = (percent != null || low) && !sensor.isFresh()
    val label = if (stale && !compact) "Battery · $value (last reading)" else "Battery · $value"
    Text(label, fontSize = fontSize, color = if (low && !stale) DashboardTokens.Amber else DashboardTokens.Muted)
}
