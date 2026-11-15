package studio.junecity.greenhouse.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import studio.junecity.greenhouse.cloud.CloudDeviceRole
import studio.junecity.greenhouse.model.GoveeSensor
import studio.junecity.greenhouse.sensors.*
import studio.junecity.greenhouse.settings.*

@Composable internal fun ShellyConnections(house: String, houseName: String, onChanged: () -> Unit,
    discover: (android.content.Context) -> kotlinx.coroutines.flow.Flow<ShellyAdvertisement> = { ShellyBluetooth.advertisements(it, true) }
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val secure = remember { SecureSettingsStore(context) }
    var open by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    var status by remember { mutableStateOf("") }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (SensorBluetooth.allowed(context)) { attempt++; open = true }
        else status = "Allow Bluetooth scanning in Android permissions to add a Shelly."
    }
    OutlinedButton(onClick = {
        if (SensorBluetooth.allowed(context)) { attempt++; open = true }
        else permission.launch(SensorBluetooth.permissions)
    }) { Text("Add Shelly Bluetooth sensors", color = DashboardTokens.Amber) }
    if (!open && status.isNotEmpty()) Text(status, color = DashboardTokens.Muted)
    if (!open) return

    val owner = secure.cloudEnrollment()
    val store = remember(owner) { CurrentSensorStore(context, if (owner.isStandaloneClient()) "_standalone" else "") }
    val found = remember { mutableStateMapOf<String, ShellyAdvertisement>() }
    val added = remember(owner) { mutableStateListOf<String>().apply { addAll(store.load().map { it.id }) } }
    var manual by remember { mutableStateOf(false) }
    var address by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(ShellyModel.Display) }
    var confirmation by remember { mutableStateOf("") }
    fun enroll(id: String, model: ShellyModel, ad: ShellyAdvertisement?): Boolean {
        val current = secure.cloudEnrollment()
        if (current != owner || current?.role == CloudDeviceRole.ViewerClient && !current.isStandaloneClient()) return false
        if (current.isStandaloneClient() && current?.localSensors == false)
            secure.saveCloudEnrollment(current.copy(localSensors = true))
        val name = "${model.title} · ${id.takeLast(5)}"
        val sensor = GoveeSensor(id, model.sku, name, greenhouseId = house)
        store.enroll(ad?.update(sensor) ?: sensor)
        if (id !in added) added.add(id)
        onChanged()
        return true
    }
    LaunchedEffect(attempt, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            status = "Tap Add sensor beside your Shelly. It may take a minute to appear."
            try {
                withTimeoutOrNull(90_000) {
                    discover(context).collect { ad ->
                        if (ad.model != null && (ad.beacon.encrypted || ad.beacon.temperatureF != null && ad.beacon.humidity != null))
                            found[ad.id] = ad
                    }
                }
                status = if (found.isEmpty()) "No sensors found. Move closer and scan again, or enter the Bluetooth address." else "Scan complete."
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { status = e.message ?: "Scan failed. Try again." }
        }
    }
    val id = shellyAddressId(address)
    val detected = found[id]
    Dialog(onDismissRequest = { open = false; status = "" }) {
        Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp), color = DashboardTokens.PanelRaised) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Add to $houseName", style = MaterialTheme.typography.titleLarge, color = Color.White)
                if (manual) {
                    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Which Shelly do you have?", color = Color.White)
                        Column(Modifier.selectableGroup()) {
                            for (model in listOf(ShellyModel.Display, ShellyModel.HT)) {
                                val chosen = (detected?.model ?: selected) == model
                                Row(Modifier.fillMaxWidth().selectable(chosen, role = Role.RadioButton,
                                    enabled = detected?.model == null, onClick = { selected = model }).heightIn(min = 48.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(chosen, null, colors = RadioButtonDefaults.colors(selectedColor = DashboardTokens.Amber, unselectedColor = DashboardTokens.Muted))
                                    Spacer(Modifier.width(8.dp))
                                    Text(if (model == ShellyModel.Display) "With display" else "Without display", color = Color.White)
                                }
                            }
                        }
                        OutlinedTextField(address, { address = it }, singleLine = true,
                            label = { Text("Bluetooth address") }, placeholder = { Text("AA:BB:CC:DD:EE:FF") },
                            supportingText = { Text(when {
                                address.trim().length >= 12 && id == null -> "Check the address. Use 12 characters from 0–9 and A–F."
                                id != null && id in added -> "Already added on this phone."
                                detected?.beacon?.encrypted == true -> "This sensor uses encrypted broadcasts."
                                else -> "Paste the 12-digit Bluetooth address from the sensor’s device information."
                            }) }, modifier = Modifier.fillMaxWidth(), colors = darkFieldColors())
                        TextButton(onClick = { manual = false }) { Text("Back to nearby sensors", color = DashboardTokens.Amber) }
                    }
                } else {
                    Text(confirmation.ifEmpty { status }, color = DashboardTokens.Muted)
                    LazyColumn(Modifier.weight(1f, fill = false)) {
                        items(found.values.sortedBy { it.id }, key = { it.id }) { ad ->
                            val model = requireNotNull(ad.model)
                            Text("${model.title} · ${ad.id.takeLast(5)}", color = Color.White)
                            if (ad.beacon.encrypted) Text("Encrypted broadcast", color = DashboardTokens.Muted)
                            else TextButton(enabled = ad.id !in added, onClick = { enroll(ad.id, model, ad) }) {
                                Text(if (ad.id in added) "Added" else "Add sensor", color = DashboardTokens.Amber)
                            }
                        }
                    }
                    TextButton(onClick = { confirmation = ""; manual = true }) { Text("Enter address", color = DashboardTokens.Amber) }
                }
                Row {
                    if (manual) Button(colors = ButtonDefaults.buttonColors(containerColor = DashboardTokens.Amber, contentColor = Color.Black),
                        enabled = id != null && id !in added && detected?.beacon?.encrypted != true,
                        onClick = {
                            if (id != null && enroll(id, detected?.model ?: selected, detected)) {
                                confirmation = if (detected == null) "Sensor saved. Waiting for its first reading." else "Sensor added."
                                manual = false
                                address = ""
                            }
                        }) { Text("Save sensor") }
                    if (!manual) TextButton(onClick = { confirmation = ""; attempt++ }) { Text("Scan again", color = DashboardTokens.Amber) }
                    TextButton(onClick = { open = false; status = "" }) { Text("Done", color = Color.White) }
                }
            }
        }
    }
}
