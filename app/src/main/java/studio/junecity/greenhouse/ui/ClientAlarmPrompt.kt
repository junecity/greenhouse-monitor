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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import studio.junecity.greenhouse.cloud.ClientTemperatureAlarms
import studio.junecity.greenhouse.cloud.CloudDeviceRole
import studio.junecity.greenhouse.cloud.MonitoringAlert
import studio.junecity.greenhouse.cloud.MonitoringAlertType
import studio.junecity.greenhouse.settings.CloudEnrollment

@Composable
internal fun activeClimateAlarms(enrollment: CloudEnrollment?): List<MonitoringAlert> {
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
internal fun ClientAlarmPrompt(
    alarms: List<MonitoringAlert>,
    enrollment: CloudEnrollment?,
    showReadings: Boolean,
    openedIncidentId: String? = null,
    onStopped: (Set<String>) -> Unit = {}
) {
    if (alarms.isEmpty()) return
    val context = LocalContext.current
    fun stop(ids: Set<String>) {
        ClientTemperatureAlarms.stop(context, enrollment, ids)
        onStopped(ids)
    }
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        containerColor = DashboardTokens.Panel,
        titleContentColor = DashboardTokens.White,
        textContentColor = DashboardTokens.White,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                DashboardIconView(DashboardIcon.Bell, DashboardTokens.Danger, Modifier.size(24.dp), contentDescription = null)
                val title = when {
                    alarms.all { it.type == MonitoringAlertType.CriticalHumidity } -> "Critical humidity alarm"
                    alarms.all { it.type == MonitoringAlertType.CriticalTemperature } -> "Critical temperature alarm"
                    alarms.all { it.type == MonitoringAlertType.SensorCommunicationFailure } -> "Sensor offline alarm"
                    alarms.all { it.type.isClimateAlarm } -> "Critical greenhouse alarms"
                    else -> "Greenhouse alarms"
                }
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
        },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (!showReadings) item {
                    Text("You can stop this phone’s alarm while it reconnects to your account.")
                }
                if (showReadings || alarms.size > 1) {
                    items(alarms.sortedByDescending { it.id == openedIncidentId }, key = { it.id }) { alert ->
                        val occurred = DateUtils.formatDateTime(context, alert.occurredAtEpochMs,
                            DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME)
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                val timeLabel = if (showReadings && alert.resolvedAtEpochMs != null) {
                                    val ended = DateUtils.formatDateTime(context, alert.resolvedAtEpochMs,
                                        DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME)
                                    "Started $occurred · Ended $ended"
                                } else occurred
                                Text(timeLabel, color = DashboardTokens.Muted, style = MaterialTheme.typography.labelMedium)
                                if (showReadings) Text(alert.message, style = MaterialTheme.typography.bodyLarge)
                                else if (alert.id == openedIncidentId) Text("Opened notification")
                            }
                            if (alarms.size > 1) {
                                TextButton(onClick = { stop(setOf(alert.id)) }, modifier = Modifier.semantics {
                                    contentDescription = if (showReadings) "Stop alarm. ${alert.message}" else "Stop alarm from $occurred"
                                }) {
                                    Text("Stop this alarm", color = DashboardTokens.Amber, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (alarms.size == 1) {
                Button(onClick = { stop(setOf(alarms.single().id)) }, modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = DashboardTokens.Amber, contentColor = DashboardTokens.Background)) {
                    Text("Stop alarm", fontWeight = FontWeight.Bold)
                }
            } else {
                TextButton(onClick = { stop(alarms.mapTo(mutableSetOf()) { it.id }) }) {
                    Text("Stop all alarms (${alarms.size})", color = DashboardTokens.Muted)
                }
            }
        }
    )
}
