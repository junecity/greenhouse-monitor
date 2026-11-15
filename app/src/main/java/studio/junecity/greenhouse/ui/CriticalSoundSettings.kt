package studio.junecity.greenhouse.ui

import android.content.ActivityNotFoundException
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import studio.junecity.greenhouse.cloud.CriticalAlarm
import studio.junecity.greenhouse.cloud.TemperatureSound

@Composable
internal fun CriticalSoundSettings() {
    val context = LocalContext.current
    var sound by remember { mutableStateOf(TemperatureSound.selected(context)) }
    var loud by remember { mutableStateOf(CriticalAlarm.enabled(context)) }
    var menu by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Override low or muted volume", Modifier.weight(1f), color = DashboardTokens.White)
                Switch(checked = loud, onCheckedChange = {
                    CriticalAlarm.setEnabled(context, it)
                    loud = it
                    message = ""
                })
            }
            Text("Briefly uses maximum alarm volume for critical high or low temperature. Do Not Disturb and Android notification settings still apply.", color = DashboardTokens.Muted)
            Row {
                TextButton(onClick = { message = sound.test(context) }) { Text("Test sound", color = DashboardTokens.Amber) }
                TextButton(onClick = { openSettings() }) { Text("Sound settings", color = DashboardTokens.Amber) }
            }
            if (message.isNotEmpty()) Text(message, color = DashboardTokens.Muted)
        }
    }
}
