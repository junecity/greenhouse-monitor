package studio.junecity.greenhouse.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import studio.junecity.greenhouse.cloud.CriticalAlarm
import studio.junecity.greenhouse.cloud.TemperatureSound

@Composable
internal fun CriticalSoundSettings() {
    val context = LocalContext.current
    var sound by remember { mutableStateOf(TemperatureSound.selected(context)) }
    var loud by remember { mutableStateOf(CriticalAlarm.enabled(context)) }
    var menu by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var soundIssue by remember { mutableStateOf<String?>(null) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, context, sound, loud) {
        fun checkSound() { soundIssue = CriticalAlarm.soundIssue(context, sound.channel(context), loud) }
        checkSound()
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                message = ""
                checkSound()
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    fun openSettings() {
        try {
            context.startActivity(sound.soundSettings(context))
            message = "Choose Sound in Android settings."
        } catch (_: ActivityNotFoundException) {
            message = "Open Greenhouse Studio notification settings on your phone to change the sound."
        }
    }
    Surface(color = DashboardTokens.PanelRaised, shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Critical temperature sound", color = DashboardTokens.White, style = MaterialTheme.typography.titleMedium)
            Box {
                TextButton(onClick = { menu = true }) { Text("${sound.label} ▾", color = DashboardTokens.Amber) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    TemperatureSound.entries.forEach { option ->
                        DropdownMenuItem(text = { Text(option.label) }, onClick = {
                            option.select(context)
                            sound = option
                            menu = false
                            message = ""
                            if (option == TemperatureSound.Custom) openSettings()
                        })
                    }
                }
            }
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(
                value = loud, role = Role.Switch, onValueChange = { enabled ->
                    CriticalAlarm.setEnabled(context, enabled)
                    loud = enabled
                    message = ""
                }
            ), verticalAlignment = Alignment.CenterVertically) {
                Text("Override low or muted volume", Modifier.weight(1f), color = DashboardTokens.White)
                Switch(checked = loud, onCheckedChange = null)
            }
            Text(if (loud) "Critical alarms repeat at full alarm volume, even on silent or vibrate. Tap Stop alarm in the app to silence them."
                else "Critical alarms repeat at your phone’s notification volume until you tap Stop alarm in the app.", color = DashboardTokens.Muted)
            soundIssue?.let { Text(it, color = DashboardTokens.Amber) }
            if (soundIssue?.contains("Do Not Disturb") == true) {
                TextButton(onClick = {
                    try {
                        context.startActivity(Intent(Settings.ACTION_ZEN_MODE_PRIORITY_SETTINGS))
                    } catch (_: ActivityNotFoundException) {
                        message = "Open Do Not Disturb in your phone’s settings and allow alarms."
                    }
                }) { Text("Do Not Disturb settings", color = DashboardTokens.Amber) }
            }
            Row {
                TextButton(onClick = { message = sound.test(context) }) { Text("Test sound", color = DashboardTokens.Amber) }
                TextButton(onClick = { openSettings() }) { Text("Sound settings", color = DashboardTokens.Amber) }
            }
            if (message.isNotEmpty() && message != soundIssue) Text(message, color = DashboardTokens.Muted)
        }
    }
}
