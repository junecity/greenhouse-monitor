package studio.junecity.greenhouse.ui

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import studio.junecity.greenhouse.cloud.ClientTemperatureAlarms
import studio.junecity.greenhouse.cloud.CloudDeviceRole
import studio.junecity.greenhouse.cloud.MonitoringAlert
import studio.junecity.greenhouse.settings.CloudEnrollment

@Composable
internal fun activeTemperatureAlarms(enrollment: CloudEnrollment?): List<MonitoringAlert> {
    if (enrollment?.role != CloudDeviceRole.ViewerClient) return emptyList()
    val context = LocalContext.current.applicationContext
    var alarms by remember(context, enrollment) { mutableStateOf(ClientTemperatureAlarms.active(context, enrollment)) }
    DisposableEffect(context, enrollment) {
        fun refresh() { alarms = ClientTemperatureAlarms.active(context, enrollment) }
        val unsubscribe = ClientTemperatureAlarms.watch(context, ::refresh)
        refresh()
        onDispose { unsubscribe() }
    }
    return alarms
}

@Composable
internal fun ClientAlarmPrompt(alarms: List<MonitoringAlert>, enrollment: CloudEnrollment?, showReadings: Boolean, onStopped: () -> Unit = {}) {
    if (alarms.isEmpty()) return
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        containerColor = DashboardTokens.Panel,
        titleContentColor = DashboardTokens.White,
        textContentColor = DashboardTokens.White,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                DashboardIconView(DashboardIcon.Bell, DashboardTokens.Danger, Modifier.size(24.dp), contentDescription = null)
                Text("Critical temperature alarm", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
        },
        text = {
            if (showReadings) {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(alarms, key = { it.id }) { alert ->
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            val occurred = DateUtils.formatDateTime(context, alert.occurredAtEpochMs,
                                DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME)
                            Text(occurred, color = DashboardTokens.Muted, style = MaterialTheme.typography.labelMedium)
                            Text(alert.message, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            } else {
                Text("You can stop this phone’s alarm while it reconnects to your account.")
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    ClientTemperatureAlarms.stop(context, enrollment, alarms.mapTo(mutableSetOf()) { it.id })
                    onStopped()
                }, modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = DashboardTokens.Amber, contentColor = DashboardTokens.Background)
            ) {
                Text(if (alarms.size == 1) "Stop alarm" else "Stop all alarms (${alarms.size})", fontWeight = FontWeight.Bold)
            }
        }
    )
}
