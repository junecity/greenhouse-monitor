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
        if (ShellyBluetooth.allowed(context)) { attempt++; open = true }
        else status = "Allow Bluetooth scanning in Android permissions to add a Shelly."
    }
    OutlinedButton(onClick = {
        if (ShellyBluetooth.allowed(context)) { attempt++; open = true }
        else permission.launch(ShellyBluetooth.permissions)
    }) { Text("Add Shelly Bluetooth sensors", color = DashboardTokens.Amber) }
    if (!open && status.isNotEmpty()) Text(status, color = DashboardTokens.Muted)
    if (!open) return

    val owner = secure.cloudEnrollment()
    val store = remember(owner) { CurrentSensorStore(context, if (owner.isStandaloneClient()) "_standalone" else "") }
    val found = remember { mutableStateMapOf<String, ShellyAdvertisement>() }
    val added = remember(owner) { mutableStateListOf<String>().apply { addAll(store.load().map { it.id }) } }
    LaunchedEffect(attempt, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            status = "Listening for nearby sensors. Allow up to one minute."
            try {
                withTimeoutOrNull(90_000) {
                    discover(context).collect { ad ->
                        if (ad.model != null && (ad.beacon.encrypted || ad.beacon.temperatureF != null && ad.beacon.humidity != null))
                            found[ad.id] = ad
                    }
                }
                status = if (found.isEmpty()) "No sensors found. Check Bluetooth and move closer." else "Scan complete."
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { status = e.message ?: "Scan failed. Try again." }
        }
    }
    Dialog(onDismissRequest = { open = false; status = "" }) {
        Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp), color = DashboardTokens.PanelRaised) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Add to $houseName", style = MaterialTheme.typography.titleLarge, color = Color.White)
                Text(status, color = DashboardTokens.Muted)
                LazyColumn(Modifier.weight(1f, fill = false)) {
                    items(found.values.sortedBy { it.id }, key = { it.id }) { ad ->
                        val model = requireNotNull(ad.model)
                        val shortId = ad.id.takeLast(5)
                        val name = "${model.title} · $shortId"
                        Text(name, color = Color.White)
                        if (ad.beacon.encrypted) Text("Encrypted broadcast", color = DashboardTokens.Muted)
                        else TextButton(enabled = ad.id !in added, onClick = {
                            val current = secure.cloudEnrollment()
                            if (current != owner || current?.role == CloudDeviceRole.ViewerClient && !current.isStandaloneClient()) return@TextButton
                            if (current.isStandaloneClient() && current?.localSensors == false)
                                secure.saveCloudEnrollment(current.copy(localSensors = true))
                            store.update { old -> if (old.any { it.id == ad.id }) old else {
                                val device = GoveeSensor(ad.id, model.sku, name, greenhouseId = house)
                                old + (ad.update(device) ?: device)
                            } }
                            added.add(ad.id)
                            onChanged()
                        }) { Text(if (ad.id in added) "Added" else "Add sensor", color = DashboardTokens.Amber) }
                    }
                }
                Row {
                    TextButton(onClick = { attempt++ }) { Text("Scan again", color = DashboardTokens.Amber) }
                    TextButton(onClick = { open = false; status = "" }) { Text("Done", color = Color.White) }
                }
            }
        }
    }
}
