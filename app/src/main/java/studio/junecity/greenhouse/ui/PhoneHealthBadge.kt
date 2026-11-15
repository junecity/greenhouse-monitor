package studio.junecity.greenhouse.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import studio.junecity.greenhouse.model.DeviceStatus
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
internal fun PhoneHealthBadge(status: DeviceStatus?, remote: Boolean, standalone: Boolean, compact: Boolean, nowMs: Long) {
    var details by remember(remote, standalone) { mutableStateOf(false) }
    val subject = if (standalone) "Phone" else "Server"
    val percent = status?.batteryPercent?.let { "$it%" } ?: "—"
    val signal = status?.signalDbm
    // Approximate strength bars. The detail panel always gives the reported dBm.
    val thresholds = if (status?.wifiConnected == true) listOf(-85,-75,-65,-55) else listOf(-115,-105,-95,-85)
    val bars = signal?.let { dbm -> thresholds.count { dbm >= it }.coerceAtLeast(1) }
    val age = status?.let { nowMs - it.reportedAt }
    val old = remote && age != null && (age > 180_000 || age < -60_000)
    val tint = if (old || status == null || status.network == "Offline") DashboardTokens.Amber else DashboardTokens.Green
    val network = status?.network ?: "Awaiting report"
    val updated = status?.reportedAt?.takeIf { it > 0 }?.let {
        DateTimeFormatter.ofPattern("MMM d, h:mm a").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(it))
    }
    val battery = if (status?.charging == true) "$percent charging" else percent
    val version = studio.junecity.greenhouse.cloud.compareServerVersion(status?.appVersion, status?.appVersionCode)
    val versionDiffers = remote && version in setOf(studio.junecity.greenhouse.cloud.ServerVersion.Older, studio.junecity.greenhouse.cloud.ServerVersion.Newer)
    val summary = if (status == null) "$subject · Awaiting report"
        else "$subject · Battery $battery · $network · " + (signal?.let { "$it dBm" } ?: "Signal unavailable") +
            if (old && updated != null) " · Last reported $updated" else ""
    Row(Modifier.fillMaxHeight().clickable { details = true }.semantics { contentDescription = summary },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(if (versionDiffers) "Version mismatch" else if (old) "$subject · Last seen" else subject, color=DashboardTokens.Muted, fontSize=if(compact) 11.sp else 13.sp)
        if (status != null) {
            Canvas(Modifier.size(17.dp, 10.dp)) {
                val w = size.width * .84f
                val stroke = 1.dp.toPx()
                drawRect(tint, size=Size(w, size.height), style=androidx.compose.ui.graphics.drawscope.Stroke(stroke))
                drawRect(tint, Offset(w, size.height*.3f), Size(size.width-w, size.height*.4f))
                status.batteryPercent?.let { drawRect(tint, Offset(stroke,stroke),Size((w-2*stroke)*it/100f,size.height-2*stroke)) }
            }
            Text(percent, color=Color.White, fontSize=13.sp)
            if (bars != null) Canvas(Modifier.size(16.dp, 12.dp)) {
                val step = size.width/4
                for (i in 0..3) {
                    val h = size.height * (i+1)/4
                    drawRect(if(i < bars) tint else DashboardTokens.MutedDark, Offset(i*step,size.height-h),Size(step*.7f,h))
                }
            }
        }
        Text(if (signal != null && !compact) "$network $signal dBm" else network, color=tint, fontSize=if(compact) 11.sp else 13.sp)
    }
    if (details) AlertDialog(onDismissRequest={details=false}, containerColor=DashboardTokens.Panel,
        title={Text(if (standalone) "Phone status" else "Greenhouse server", color=Color.White)},
        text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
            if (status == null) Text("Waiting for a health report from the paired greenhouse phone.", color=DashboardTokens.Muted)
            else {
                if (remote) Text("Client ${studio.junecity.greenhouse.BuildConfig.VERSION_NAME} · Server ${status.appVersion ?: "version unavailable"}",
                    color = if (versionDiffers) DashboardTokens.Amber else Color.White)
                Text("Battery · $battery", color=Color.White)
                Text("Connection · $network", color=Color.White)
                Text("Signal · " + (signal?.let { "$it dBm" } ?: "Unavailable"), color=Color.White)
                if (updated != null) Text("Last reported $updated", color=DashboardTokens.Muted)
            }
        }}, confirmButton={TextButton(onClick={details=false}){Text("Close",color=DashboardTokens.Amber)}})
}
