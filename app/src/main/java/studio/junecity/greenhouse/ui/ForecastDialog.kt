package studio.junecity.greenhouse.ui

import android.app.TimePickerDialog
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.graphics.Color
import studio.junecity.greenhouse.model.ForecastDay
import java.time.LocalDate
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
        Surface(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp).widthIn(max = 920.dp)
                .fillMaxWidth().fillMaxHeight(.94f),
            shape = RoundedCornerShape(20.dp),
            border = BorderStroke(1.dp, DashboardTokens.PanelEdge),
            color = DashboardTokens.Panel, contentColor = DashboardTokens.White
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    DashboardIconView(if (editing) DashboardIcon.Bell else DashboardIcon.Calendar,
                        DashboardTokens.Amber, Modifier.size(26.dp), contentDescription = null)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(if (editing) "Forecast alerts" else "7-day forecast", fontSize = 22.sp,
                            lineHeight = 26.sp, fontWeight = FontWeight.Bold)
                        Text(current?.location ?: "ZIP $zip", color = DashboardTokens.Muted, fontSize = 14.sp)
                    }
                    ForecastButton(if (editing) "Back" else "Close", if (editing) ({ editing = false }) else dismiss)
                }
                Spacer(Modifier.height(8.dp))
                if (editing) {
                    HorizontalDivider(color = DashboardTokens.Divider)
                    ForecastPreferences(alerts, { editing = false; refresh() })
                } else {
                    BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                        val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
                        val columns = when {
                            maxWidth >= 560.dp * fontScale -> 7
                            maxWidth >= 390.dp * fontScale -> 4
                            else -> 2
                        }
                        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            if (current == null || current.days.isEmpty()) {
                                Text("Forecast unavailable. Check the ZIP code and internet connection.",
                                    modifier = Modifier.padding(vertical = 16.dp))
                            } else {
                                val today = LocalDate.now(current.zone)
                                current.days.chunked(columns).forEach { week ->
                                    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                                        horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                        week.forEach { day ->
                                            ForecastDayCard(day, day.date == today, Modifier.weight(1f).fillMaxHeight())
                                        }
                                        repeat(columns - week.size) { Spacer(Modifier.weight(1f)) }
                                    }
                                }
                                val updated = Instant.ofEpochMilli(current.updatedAtEpochMs).atZone(current.zone)
                                    .format(DateTimeFormatter.ofPattern("MMM d, h:mm a z"))
                                Text("${if (current.stale) "Last available" else "Updated"} $updated · Open-Meteo",
                                    fontSize = 12.sp, color = DashboardTokens.Muted)
                            }
                        }
                    }
                    HorizontalDivider(color = DashboardTokens.Divider)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = refresh) {
                            DashboardIconView(DashboardIcon.Refresh, DashboardTokens.Muted, Modifier.size(16.dp), null)
                            Spacer(Modifier.width(6.dp))
                            Text("Refresh", color = DashboardTokens.White)
                        }
                        Button(onClick = { editing = true }, colors = ButtonDefaults.buttonColors(
                            containerColor = DashboardTokens.Amber, contentColor = DashboardTokens.Background)) {
                            DashboardIconView(DashboardIcon.Bell, DashboardTokens.Background, Modifier.size(16.dp), null)
                            Spacer(Modifier.width(6.dp))
                            Text("Set alerts", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

internal fun forecastIcon(condition: String): DashboardIcon = when (condition.lowercase()) {
    "thunderstorm" -> DashboardIcon.Storm
    "snow" -> DashboardIcon.Snow
    "rain", "drizzle" -> DashboardIcon.Rain
    "clear sky", "mainly clear" -> DashboardIcon.Sun
    "partly cloudy" -> DashboardIcon.CloudSun
    else -> DashboardIcon.Cloud
}

@Composable
private fun ForecastDayCard(day: ForecastDay, today: Boolean, modifier: Modifier) {
    val accent = if (today) DashboardTokens.Amber else DashboardTokens.White
    Surface(modifier = modifier, shape = RoundedCornerShape(12.dp),
        color = if (today) Color(0xFF30291F) else DashboardTokens.PanelRaised,
        border = BorderStroke(1.dp, if (today) DashboardTokens.AmberDark else DashboardTokens.PanelEdge)) {
        Column(Modifier.padding(horizontal = 4.dp, vertical = 9.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Text(if (today) "Today" else day.date.format(DateTimeFormatter.ofPattern("EEE")),
                color = accent, fontWeight = FontWeight.Bold, fontSize = 16.sp, lineHeight = 19.sp)
            Text(day.date.format(DateTimeFormatter.ofPattern("MMM d")), color = DashboardTokens.Muted,
                fontSize = 12.sp, lineHeight = 15.sp)
            Spacer(Modifier.height(6.dp))
            DashboardIconView(forecastIcon(day.condition), DashboardTokens.Amber, Modifier.size(24.dp), null)
            Text(day.condition, color = DashboardTokens.White, fontSize = 13.sp, lineHeight = 15.sp,
                textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
            Spacer(Modifier.height(6.dp))
            Text("High ${formatTemp(day.highF)}", fontSize = 15.sp, lineHeight = 18.sp,
                color = DashboardTokens.White, fontWeight = FontWeight.Bold)
            Text("Low ${formatTemp(day.lowF)}", fontSize = 13.sp, lineHeight = 16.sp, color = DashboardTokens.Muted)
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.semantics(mergeDescendants = true) {}) {
                DashboardIconView(DashboardIcon.Droplet, DashboardTokens.Blue, Modifier.size(13.dp), "Precipitation chance")
                Spacer(Modifier.width(3.dp))
                Text(day.rainChance?.let { "$it%" } ?: "—", color = DashboardTokens.Blue,
                    fontSize = 13.sp, lineHeight = 16.sp)
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
