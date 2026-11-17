package studio.junecity.greenhouse.ui

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import studio.junecity.greenhouse.cloud.*
import studio.junecity.greenhouse.model.*
import studio.junecity.greenhouse.settings.*

internal val LocalAlertGateway = staticCompositionLocalOf<(Context, CloudEnrollment) -> AuthenticatedCloudGateway> {
    { context, enrollment ->
        gatewayForEnrollment(context, requireNotNull(CloudRuntime.configurationOrNull()), enrollment)
    }
}

@Composable
internal fun AlertSettings(state: GreenhouseUiState, onPrefs: ((AppPreferences) -> AppPreferences) -> Unit) {
    val context = LocalContext.current
    val secure = remember(context) { SecureSettingsStore(context) }
    var owner by remember(secure) { mutableStateOf(secure.cloudEnrollment()) }
    DisposableEffect(secure) {
        val unsubscribe = secure.watchEnrollment { owner = secure.cloudEnrollment() }
        owner = secure.cloudEnrollment()
        onDispose { unsubscribe() }
    }
    val enrollment = owner
    val cloud = enrollment?.takeUnless { it.isStandaloneClient() }
    val viewer = enrollment?.role == CloudDeviceRole.ViewerClient && !state.standalone && enrollment.localSensors == false
    val house = state.preferences.activeGreenhouse
    val current = AlertRules.from(state.preferences.forAlertsIn(house.id))
    key(enrollment, house.id) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column {
                Text("Alert limits", color = DashboardTokens.White, fontWeight = FontWeight.Bold, fontSize = 23.sp)
                Text(house.name, color = DashboardTokens.Muted, fontSize = 15.sp)
            }
            if (viewer && cloud?.greenhouseId.isNullOrBlank()) {
                Text("Select or pair a greenhouse in Settings → Connectivity to set its alerts.", color = DashboardTokens.Muted)
            } else if (cloud == null) {
                var saved by remember { mutableStateOf(false) }
                RuleForm(current, false, false, "Save alerts") { rules ->
                    onPrefs { prefs -> prefs.withAlertRules(house.id, rules) }
                    saved = true
                }
                if (saved) Text("Saved on this phone.", color = DashboardTokens.Green)
            } else {
                RemoteAlertRules(cloud, current)
            }
            if (viewer && enrollment != null) CriticalSoundSettings(enrollment)
            else LocalAlertDelivery(state.preferences.notifications, onPrefs)
        }
    }
}

@Composable
private fun RemoteAlertRules(owner: CloudEnrollment, initial: AlertRules) {
    val context = LocalContext.current
    val factory = LocalAlertGateway.current
    val api = remember(owner, factory, context) { lazy { factory(context.applicationContext, owner) } }
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var reload by remember { mutableIntStateOf(0) }
    var record by remember { mutableStateOf<AlertRuleState?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var conflict by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }

    LaunchedEffect(reload) {
        try {
            record = api.value.alertRules(owner.greenhouseId)
            conflict = false
            error = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = ruleError(e, false)
        }
    }
    val published = record
    val pending = published != null && published.appliedRevision < published.revision
    // Only poll while a saved change awaits its server. A late poll cannot replace a newer draft.
    LaunchedEffect(published?.revision, pending, saving, conflict, lifecycle) {
        if (!pending || saving || conflict || published == null) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                delay(15_000)
                try {
                    val latest = api.value.alertRules(owner.greenhouseId)
                    if (latest.revision != published.revision) {
                        conflict = true
                        error = "Another phone changed these alerts. Reload the saved limits before editing."
                        break
                    }
                    record = latest
                    if (latest.appliedRevision >= latest.revision) break
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // The last confirmed state remains pending until the server acknowledges it.
                }
            }
        }
    }
    if (published == null && error == null) Text("Loading greenhouse alerts…", color = DashboardTokens.Muted)
    if (published != null) {
        if (published.rules != null) {
            Text(if (pending) "Saved · Waiting for greenhouse server" else "Applied on greenhouse server",
                color = if (pending) DashboardTokens.Amber else DashboardTokens.Green)
            if (pending) Text("The server applies changes at its next check-in. Both phones need the alert settings update.",
                color = DashboardTokens.Muted, fontSize = 14.sp)
        }
        key(published.revision) {
            RuleForm(published.rules ?: initial, true, saving || conflict, if (saving) "Saving…" else "Save alerts") { rules ->
                if (!saving && !conflict) {
                    saving = true
                    error = null
                    scope.launch {
                        try {
                            record = api.value.saveAlertRules(owner.greenhouseId, published.revision, rules)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            conflict = e is CloudApiException && e.statusCode == 409
                            error = ruleError(e, true)
                        } finally {
                            saving = false
                        }
                    }
                }
            }
        }
    }
    error?.let { message ->
        Text(message, color = DashboardTokens.Amber)
        if (published == null || conflict) {
            TextButton(onClick = { reload++ }) { Text(if (conflict) "Reload limits" else "Try again", color = DashboardTokens.Amber) }
        }
    }
}

private fun ruleError(e: Exception, saving: Boolean): String {
    return when ((e as? CloudApiException)?.statusCode) {
        401, 403 -> "Access to these alert settings was denied. Check your account and greenhouse pairing."
        409 -> "Another phone changed these alerts. Reload the saved limits before editing."
        else -> if (saving) "Couldn’t confirm the save. Check your connection and tap Save alerts to retry."
            else "Couldn’t load the greenhouse alert settings. Check your connection and try again."
    }
}

@Composable
private fun RuleForm(initial: AlertRules, paired: Boolean, busy: Boolean, action: String, save: (AlertRules) -> Unit) {
    var lowTemp by rememberSaveable(initial) { mutableStateOf(number(initial.lowTemperatureF)) }
    var highTemp by rememberSaveable(initial) { mutableStateOf(number(initial.highTemperatureF)) }
    var lowRh by rememberSaveable(initial) { mutableStateOf(number(initial.lowHumidity)) }
    var highRh by rememberSaveable(initial) { mutableStateOf(number(initial.highHumidity)) }
    var temperature by rememberSaveable(initial) { mutableStateOf(initial.temperatureAlerts) }
    var humidity by rememberSaveable(initial) { mutableStateOf(initial.humidityAlerts) }
    var offline by rememberSaveable(initial) { mutableStateOf(initial.offlineAlerts) }
    val low = lowTemp.toDoubleOrNull()
    val high = highTemp.toDoubleOrNull()
    val dry = lowRh.toDoubleOrNull()
    val wet = highRh.toDoubleOrNull()
    val temperatureValid = low != null && high != null && low in -150.0..250.0 && high in -150.0..250.0 && low < high
    val humidityValid = dry != null && wet != null && dry in 0.0..100.0 && wet in 0.0..100.0 && dry < wet

    AlertCard {
        Text("Temperature", style = MaterialTheme.typography.titleMedium, color = DashboardTokens.White, fontWeight = FontWeight.Bold)
        if (paired) Text("A sensor reaching either limit triggers a critical alarm on paired phones.", color = DashboardTokens.Muted, fontSize = 14.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            LimitField("Low °F", lowTemp, Modifier.weight(1f), !busy) { lowTemp = it }
            LimitField("High °F", highTemp, Modifier.weight(1f), !busy) { highTemp = it }
        }
        if (!temperatureValid) Text("Enter −150 to 250°F, with the low limit below the high limit.", color = DashboardTokens.Amber)
    }
    AlertCard {
        Text("Humidity", style = MaterialTheme.typography.titleMedium, color = DashboardTokens.White, fontWeight = FontWeight.Bold)
        if (paired) {
            AlertSwitch("Humidity alarms", humidity, !busy) { humidity = it }
            Text("Alert paired phones when a sensor reaches either humidity limit.", color = DashboardTokens.Muted, fontSize = 14.sp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            LimitField("Low % RH", lowRh, Modifier.weight(1f), !busy, KeyboardType.Decimal) { lowRh = it }
            LimitField("High % RH", highRh, Modifier.weight(1f), !busy, KeyboardType.Decimal) { highRh = it }
        }
        if (!humidityValid) Text("Enter 0 to 100% RH, with the low limit below the high limit.", color = DashboardTokens.Amber)
    }
    AlertCard {
        Text(if (paired) "Routine alerts on the greenhouse phone" else "Routine alerts",
            style = MaterialTheme.typography.titleMedium, color = DashboardTokens.White, fontWeight = FontWeight.Bold)
        if (paired) Text("Paired phones always receive critical temperature alerts.", color = DashboardTokens.Muted, fontSize = 14.sp)
        AlertSwitch("Temperature", temperature, !busy) { temperature = it }
        if (!paired) AlertSwitch("Humidity", humidity, !busy) { humidity = it }
        AlertSwitch("Sensor offline", offline, !busy) { offline = it }
    }
    Button(enabled = !busy && temperatureValid && humidityValid,
        onClick = {
            val rules = initial.copy(lowTemperatureF = requireNotNull(low), highTemperatureF = requireNotNull(high),
                lowHumidity = requireNotNull(dry), highHumidity = requireNotNull(wet), temperatureAlerts = temperature,
                humidityAlerts = humidity, offlineAlerts = offline)
            if (rules.valid()) save(rules)
        }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(containerColor = DashboardTokens.Amber, contentColor = DashboardTokens.Background)
    ) { Text(action, fontWeight = FontWeight.Bold) }
}

private fun number(value: Double) = if (value.isFinite() && value == value.toInt().toDouble()) value.toInt().toString() else value.toString()

@Composable
private fun LimitField(label: String, value: String, modifier: Modifier, enabled: Boolean, keyboard: KeyboardType = KeyboardType.Ascii, onChange: (String) -> Unit) {
    OutlinedTextField(value, onValueChange = { onChange(it.replace(',', '.')) }, modifier = modifier, singleLine = true, enabled = enabled,
        label = { Text(label) }, keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        colors = OutlinedTextFieldDefaults.colors(focusedTextColor = DashboardTokens.White, unfocusedTextColor = DashboardTokens.White,
            cursorColor = DashboardTokens.Amber, focusedBorderColor = DashboardTokens.Amber, unfocusedBorderColor = DashboardTokens.PanelEdge,
            focusedLabelColor = DashboardTokens.Amber, unfocusedLabelColor = DashboardTokens.Muted))
}

@Composable
private fun AlertCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(color = DashboardTokens.PanelRaised, shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, DashboardTokens.PanelEdge)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

@Composable
private fun AlertSwitch(label: String, checked: Boolean, enabled: Boolean = true, onChecked: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value = checked, enabled = enabled,
        role = Role.Switch, onValueChange = onChecked), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), color = DashboardTokens.White)
        Switch(checked, onCheckedChange = null, enabled = enabled,
            colors = SwitchDefaults.colors(checkedThumbColor = DashboardTokens.Background, checkedTrackColor = DashboardTokens.Amber))
    }
}

@Composable
private fun LocalAlertDelivery(n: NotificationPreferences, onPrefs: ((AppPreferences) -> AppPreferences) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    fun update(transform: (NotificationPreferences) -> NotificationPreferences) {
        onPrefs { it.copy(notifications = transform(it.notifications)) }
    }
    TextButton(onClick = { expanded = !expanded }) {
        Text(if (expanded) "Hide delivery options" else "Alert delivery on this phone", color = DashboardTokens.Amber)
    }
    if (expanded) AlertCard {
        AlertSwitch("Routine notifications", n.masterEnabled) { v -> update { it.copy(masterEnabled = v) } }
        AlertSwitch("On-screen pop-ups", n.popupsEnabled) { v -> update { it.copy(popupsEnabled = v) } }
        AlertSwitch("Sound", n.soundEnabled) { v -> update { it.copy(soundEnabled = v) } }
        AlertSwitch("Vibration", n.vibrationEnabled) { v -> update { it.copy(vibrationEnabled = v) } }
        AlertSwitch("Repeat every ${n.repeatMinutes} minutes", n.repeatedAlerts) { v -> update { it.copy(repeatedAlerts = v) } }
        val snoozed = n.snoozedUntilEpochMs > System.currentTimeMillis()
        TextButton(onClick = { update { it.copy(snoozedUntilEpochMs = if (snoozed) 0L else System.currentTimeMillis() + 60 * 60_000L) } }) {
            Text(if (snoozed) "Clear snooze" else "Snooze for 1 hour", color = DashboardTokens.Amber)
        }
    }
}
