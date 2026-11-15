package studio.junecity.greenhouse.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.*
import studio.junecity.greenhouse.sensors.*
import studio.junecity.greenhouse.settings.*
import studio.junecity.greenhouse.model.GoveeSensor
import studio.junecity.greenhouse.cloud.CloudDeviceRole

@Composable
internal fun EnvironmentalConnection(house: String, onChanged: () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var open by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        open = SensorBluetooth.allowed(context)
        if (!open) message = "Allow Bluetooth scanning in Android settings."
    }
    OutlinedButton(onClick = {
        message = ""
        if (SensorBluetooth.allowed(context)) open = true else permissions.launch(SensorBluetooth.permissions)
    }) { Text("Add Bluetooth thermometer", color = DashboardTokens.Amber) }
    if (!open) {
        if (message.isNotEmpty()) Text(message, color = DashboardTokens.Muted)
        return
    }
    val secure = remember { SecureSettingsStore(context) }
    val owner = remember { secure.cloudEnrollment() }
    val scope = rememberCoroutineScope()
    val found = remember { mutableStateMapOf<String, String>() }
    var address by remember { mutableStateOf("") }
    var selectedName by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var scan by remember { mutableIntStateOf(0) }
    var scanStatus by remember { mutableStateOf("") }
    val id = environmentalId(address)
    LaunchedEffect(scan, busy, lifecycle) {
        if (!busy) lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            scanStatus = "Searching nearby…"
            try {
                withTimeoutOrNull(25_000) {
                    EnvironmentalBluetooth.discover(context).collect { found[it.address] = it.name ?: "Bluetooth thermometer" }
                }
                scanStatus = if (found.isEmpty()) "No standard sensors found nearby." else "Search complete"
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { scanStatus = e.message.orEmpty() }
        }
    }
    AlertDialog(onDismissRequest = { open = false; message = "" }, containerColor = DashboardTokens.PanelRaised,
        title = { Text("Bluetooth thermometer", color = Color.White) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Select a nearby sensor or enter its Bluetooth address. It must share temperature and humidity through the standard Environmental Sensing service.", color = Color.White)
                Text(scanStatus, color = DashboardTokens.Muted)
                for ((mac, name) in found.entries.sortedBy { it.key })
                    TextButton(enabled = !busy, onClick = { address = mac; selectedName = name; message = "" }) {
                        Text("$name · ${mac.takeLast(5)}", color = DashboardTokens.Amber)
                    }
                OutlinedTextField(address, { address = it; selectedName = ""; message = "" }, enabled = !busy,
                    singleLine = true, label = { Text("Bluetooth address") }, colors = darkFieldColors())
                TextButton(enabled = !busy, onClick = { scan++ }) { Text("Search again", color = DashboardTokens.Amber) }
                if (message.isNotEmpty()) Text(message, color = DashboardTokens.Amber)
            }
        },
        dismissButton = { TextButton(onClick = { open = false; message = "" }) { Text("Cancel", color = Color.White) } },
        confirmButton = { TextButton(enabled = id != null && !busy, onClick = {
            if (id != null) scope.launch {
                busy = true
                try {
                    val store = CurrentSensorStore(context, if (owner.isStandaloneClient()) "_standalone" else "")
                    check(store.load().none { it.id == id }) { "Already added to this phone." }
                    val (temp, rh) = EnvironmentalBluetooth.read(context, id.removePrefix("ess:"))
                    check(owner == secure.cloudEnrollment() && (owner?.role != CloudDeviceRole.ViewerClient || owner.isStandaloneClient())) { "Device setup changed. Open Devices again." }
                    if (owner != null && owner.isStandaloneClient() && !owner.localSensors) secure.saveCloudEnrollment(owner.copy(localSensors = true))
                    store.enroll(GoveeSensor(id, "BLE ESS", selectedName.ifBlank { "Thermometer · ${id.takeLast(5)}" }, temp, rh,
                        online = true, updatedAtEpochMs = System.currentTimeMillis(), greenhouseId = house))
                    onChanged()
                    message = "Bluetooth thermometer added."
                    open = false
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { message = e.message ?: "Could not read sensor." }
                finally { busy = false }
            }
        }) { Text(if (busy) "Reading sensor…" else "Check and add", color = DashboardTokens.Amber) } })
}
