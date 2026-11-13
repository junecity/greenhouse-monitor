package studio.junecity.greenhouse.ui

import android.app.TimePickerDialog
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import studio.junecity.greenhouse.data.ForecastAlerts
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import studio.junecity.greenhouse.model.WeatherReading
import java.time.Instant
import java.time.LocalTime
import java.time.format.DateTimeFormatter

@Composable
internal fun ForecastDialog(weather: WeatherReading, zip: String, refresh: () -> Unit, dismiss: () -> Unit) {
    val context = LocalContext.current
    val alerts = remember(context) { ForecastAlerts(context.applicationContext) }
    var editing by remember { mutableStateOf(false) }
    val current = weather.takeIf { it.zipCode == zip && it.updatedAtEpochMs > 0 }
    Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).widthIn(max = 680.dp)
            .fillMaxWidth().fillMaxHeight(.95f), shape = RoundedCornerShape(20.dp),
            color = DashboardTokens.Panel, contentColor = DashboardTokens.White) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(if (editing) "Forecast alerts" else "7-day forecast", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        Text(current?.location ?: "ZIP $zip", color = DashboardTokens.Muted)
                    }
                    ForecastButton(if (editing) "Back" else "Close", if (editing) ({ editing = false }) else dismiss)
                }
                HorizontalDivider(color = DashboardTokens.Divider)
                if (editing) {
                    ForecastPreferences(alerts, { editing = false; refresh() })
                } else {
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                        if (current == null || current.days.isEmpty()) {
                            Text("Forecast unavailable. Check the ZIP code and internet connection.",
                                modifier = Modifier.padding(vertical = 16.dp))
                        } else {
                            for (day in current.days) {
                                Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(day.date.format(DateTimeFormatter.ofPattern("EEE, MMM d")), fontWeight = FontWeight.Bold)
                                        Text(day.condition, color = DashboardTokens.Muted)
                                    }
                                    Column(horizontalAlignment = Alignment.End) {
                                        Text("High ${formatTemp(day.highF)}  ·  Low ${formatTemp(day.lowF)}", fontWeight = FontWeight.SemiBold)
                                        Text("Precip. ${day.rainChance?.let { "$it%" } ?: "—"}", color = DashboardTokens.Blue)
                                    }
                                }
                                HorizontalDivider(color = DashboardTokens.Divider)
                            }
                        }
                        current?.let {
                            val updated = Instant.ofEpochMilli(it.updatedAtEpochMs).atZone(it.zone)
                                .format(DateTimeFormatter.ofPattern("MMM d, h:mm a z"))
                            Text("${if (it.stale) "Last available" else "Updated"} $updated · Open-Meteo", color = DashboardTokens.Muted)
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        ForecastButton("Refresh", refresh)
                        ForecastButton("Set alerts", { editing = true })
                    }
                }
            }
        }
    }
}

@Composable
private fun ForecastPreferences(alerts: ForecastAlerts, saved: () -> Unit) {
    val context = LocalContext.current
    var rules by remember { mutableStateOf(alerts.load()) }
    var low by remember { mutableStateOf(rules.lowF.toString()) }
    var high by remember { mutableStateOf(rules.highF.toString()) }
    val lowValue = low.toIntOrNull()
    val highValue = high.toIntOrNull()
    val valid = (!rules.cold || lowValue in -150..150) && (!rules.heat || highValue in -150..150) &&
        (!(rules.cold && rules.heat) || (lowValue != null && highValue != null && lowValue < highValue))
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text("Notify this phone when today’s or tomorrow’s outdoor forecast reaches a limit.",
            color = DashboardTokens.Muted, modifier = Modifier.padding(top = 8.dp))
        ForecastSwitch("Low temperature", rules.cold) { rules = rules.copy(cold = it) }
        if (rules.cold) TemperatureField("At or below (°F)", low) { low = it }
        ForecastSwitch("High temperature", rules.heat) { rules = rules.copy(heat = it) }
        if (rules.heat) TemperatureField("At or above (°F)", high) { high = it }
        ForecastSwitch("Daily forecast reminder", rules.reminder) { rules = rules.copy(reminder = it) }
        if (rules.reminder) {
            val time = LocalTime.of(rules.minuteOfDay / 60, rules.minuteOfDay % 60)
            ForecastButton("Around ${time.format(DateTimeFormatter.ofPattern("h:mm a"))} · phone time", {
                TimePickerDialog(context, { _, h, m -> rules = rules.copy(minuteOfDay = h * 60 + m) },
                    time.hour, time.minute, android.text.format.DateFormat.is24HourFormat(context)).show()
            })
        }
        if (!valid) Text("Use limits from −150 to 150°F, with the low below the high.", color = DashboardTokens.Danger)
        if (!alerts.notificationsAvailable()) ForecastButton("Enable phone notifications", {
            context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
        })
        Button(onClick = {
            alerts.save(rules.copy(lowF = lowValue ?: rules.lowF, highF = highValue ?: rules.highF))
            saved()
        }, enabled = valid, colors = ButtonDefaults.buttonColors(containerColor = DashboardTokens.Amber,
            contentColor = DashboardTokens.Background)) { Text("Save alerts") }
    }
}

@Composable
private fun ForecastSwitch(label: String, value: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), fontWeight = FontWeight.Bold)
        Switch(checked = value, onCheckedChange = change,
            modifier = Modifier.semantics { contentDescription = label },
            colors = SwitchDefaults.colors(checkedTrackColor = DashboardTokens.Amber))
    }
}

@Composable
private fun TemperatureField(label: String, value: String, change: (String) -> Unit) {
    OutlinedTextField(value = value, onValueChange = change, label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
        colors = OutlinedTextFieldDefaults.colors(focusedTextColor = DashboardTokens.White, unfocusedTextColor = DashboardTokens.White,
            focusedLabelColor = DashboardTokens.Amber, unfocusedLabelColor = DashboardTokens.Muted))
}

@Composable
private fun ForecastButton(label: String, action: () -> Unit) {
    TextButton(onClick = action) { Text(label, color = DashboardTokens.Amber, fontWeight = FontWeight.Bold) }
}
