package studio.junecity.greenhouse.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import studio.junecity.greenhouse.model.GoveeSensor
import studio.junecity.greenhouse.sensors.*
import studio.junecity.greenhouse.settings.CurrentSensorStore

@Composable
internal fun GoveeBatteryLink(sensor: GoveeSensor, store: CurrentSensorStore, authorized: () -> Boolean, onChanged: () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var open by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        open = SensorBluetooth.allowed(context)
        message = if (open) "" else "Allow Bluetooth scanning in Android settings to read battery levels."
    }
    TextButton(onClick = {
        if (SensorBluetooth.allowed(context)) open = true else permission.launch(SensorBluetooth.permissions)
    }) { Text("Find Bluetooth battery", color = DashboardTokens.Amber) }
    if (message.isNotEmpty()) Text(message, color = DashboardTokens.Muted)
    if (!open) return
    val found = remember { mutableStateMapOf<String, GoveeBatteryPacket>() }
    var attempt by remember { mutableIntStateOf(0) }
    var status by remember { mutableStateOf("") }
    LaunchedEffect(attempt, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            found.clear()
            status = "Looking for nearby H5179 sensors…"
            try {
                withTimeoutOrNull(45_000) {
                    GoveeBattery.packets(context, true).collect { found[it.address] = it }
                }
                status = if (found.isEmpty()) "No H5179 battery reports yet. Move closer and scan again." else "Scan complete."
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { status = e.message ?: "Bluetooth scan failed." }
        }
    }
    AlertDialog(onDismissRequest = { open = false }, containerColor = DashboardTokens.PanelRaised,
        title = { Text(sensor.name, color = Color.White) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Select this sensor’s Bluetooth address to link its battery reading.", color = Color.White)
                Text(status, color = DashboardTokens.Muted)
                LazyColumn(Modifier.heightIn(max = 230.dp)) {
                    items(found.values.sortedBy { it.address }, key = { it.address }) { packet ->
                        TextButton(onClick = {
                            if (!authorized()) { open = false; return@TextButton }
                            val result = store.update { rows ->
                                if (rows.any { it.id != sensor.id && GoveeBattery.addressFor(it) == packet.address }) rows
                                else {
                                    val linked = rows.map {
                                        if (it.id != sensor.id || GoveeBattery.addressFor(it) == packet.address) it
                                        else it.copy(batteryRadioAddress = packet.address, batteryObservedAt = null, batteryPercent = null)
                                    }
                                    GoveeBattery.apply(linked, packet)
                                }
                            }
                            if (result.singleOrNull { GoveeBattery.addressFor(it) == packet.address }?.id == sensor.id) {
                                onChanged()
                                open = false
                            } else status = "This address is unavailable for this sensor."
                        }) { Text("${packet.address}  ·  ${packet.percent}%", color = DashboardTokens.Amber) }
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = { open = false }) { Text("Done", color = DashboardTokens.Amber) } },
        dismissButton = { TextButton(onClick = { attempt++ }) { Text("Scan again", color = Color.White) } })
}
