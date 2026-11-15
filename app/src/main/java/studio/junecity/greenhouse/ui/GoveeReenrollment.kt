package studio.junecity.greenhouse.ui

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.CancellationException
import studio.junecity.greenhouse.data.GoveeApiClient
import studio.junecity.greenhouse.model.GoveeSensor

@Composable
internal fun GoveeReenrollment(key: String?, forgotten: Set<String>, onDismiss: () -> Unit, onAdd: (GoveeSensor) -> Unit) {
    var devices by remember(key) { mutableStateOf(emptyList<GoveeSensor>()) }
    var message by remember(key) { mutableStateOf("") }
    LaunchedEffect(key) {
        message = "Finding your Govee sensors…"
        try {
            devices = GoveeApiClient().discoverH5179(requireNotNull(key))
            message = ""
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            message = "Discovery failed. Check your connection and Govee key, then try again."
        }
    }
    val available = devices.filter { it.id in forgotten }
    AlertDialog(onDismissRequest = onDismiss, containerColor = DashboardTokens.PanelRaised,
        title = { Text("Add Govee sensors", color = Color.White) },
        text = {
            if (available.isEmpty()) Text(message.ifEmpty { "No previously forgotten sensors found on this Govee account." }, color = DashboardTokens.Muted)
            else LazyColumn { items(available, key = { it.id }) { device ->
                Column {
                    Text(device.name, color = Color.White)
                    Text(device.id.takeLast(8), color = DashboardTokens.Muted)
                    TextButton(onClick = { onAdd(device) }) { Text("Add sensor", color = DashboardTokens.Amber) }
                }
            } }
        }, confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } })
}
