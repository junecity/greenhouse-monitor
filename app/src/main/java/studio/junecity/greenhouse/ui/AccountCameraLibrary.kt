package studio.junecity.greenhouse.ui

import androidx.core.content.edit
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.json.JSONObject
import studio.junecity.greenhouse.cloud.*
import studio.junecity.greenhouse.settings.SecureSettingsStore
import java.util.UUID

private data class CameraHouse(val id: String, val name: String, val ready: Boolean, val detail: String)
private data class RecordingOrder(val id: String, val created: Long)

internal fun cameraReadiness(reason: String) = when (reason) {
    "ready" -> "Ready"
    "offline" -> "Server offline"
    "update_server" -> "Update the greenhouse phone"
    "greenhouse_paused" -> "Monitoring paused"
    "camera_unavailable" -> "Open the server app and check camera access"
    else -> "Camera unavailable"
}

private fun recordingMessage(state: String) = when (state) {
    "queued" -> "Waiting for server"
    "recording" -> "Recording and uploading…"
    "uploaded" -> "Saved to cloud playback"
    "expired" -> "Request expired"
    else -> "Recording failed"
}

/** Remote commands are scoped to the paired device. Persist the ID before sending so a lost response can be retried. */
@Composable
internal fun AccountCameraLibrary(api: AuthenticatedCloudGateway, currentHouse: String, currentName: String,
    library: @Composable (String, Int) -> Unit) {
    val context = LocalContext.current
    val owner = remember(api) { SecureSettingsStore(context).cloudEnrollment() }
    val store = remember { context.getSharedPreferences("remote-camera-orders", 0) }
    fun key(house: String) = "${owner?.tenantId}/${owner?.deviceId}/$house"
    var houses by remember(api) { mutableStateOf(emptyList<CameraHouse>()) }
    var selected by remember(currentHouse) { mutableStateOf(currentHouse) }
    var refresh by remember { mutableIntStateOf(0) }
    var libraryRevision by remember { mutableIntStateOf(0) }
    var inventoryError by remember { mutableStateOf(false) }
    val orders = remember(api) { mutableStateMapOf<String, RecordingOrder>() }
    val messages = remember(api) { mutableStateMapOf<String, String>() }
    fun start(house: CameraHouse) {
        if (!house.ready || orders.containsKey(house.id) || owner == null) return
        val order = RecordingOrder(UUID.randomUUID().toString(), System.currentTimeMillis())
        store.edit { putString(key(house.id), "${order.id}|${order.created}") }
        messages[house.id] = "Requesting recording…"
        orders[house.id] = order
    }
    LaunchedEffect(api, refresh) {
        inventoryError = false
        val plan = try { api.getGreenhousePlan() } catch (e: CancellationException) { throw e }
            catch (_: Exception) { inventoryError = true; null }
        val names = plan?.houses.orEmpty().mapNotNull { row -> row.serverGreenhouseId?.let { it to row.name } }.toMap().toMutableMap()
        names.putIfAbsent(currentHouse, currentName)
        houses = names.map { (id, name) ->
            val access = try { api.archiveRequest(id, "access") } catch (e: CancellationException) { throw e }
                catch (_: Exception) { null }
            val ready = access?.optBoolean("remoteRecordingAvailable") == true
            CameraHouse(id, name, ready, cameraReadiness(access?.optString("remoteRecordingReason").orEmpty()))
        }
        if (selected !in names) selected = currentHouse
        for (house in houses) {
            val parts = store.getString(key(house.id), null)?.split('|') ?: continue
            val at = parts.getOrNull(1)?.toLongOrNull() ?: continue
            if (System.currentTimeMillis() - at in 0..600_000L) orders.putIfAbsent(house.id, RecordingOrder(parts.first(), at))
            else store.edit { remove(key(house.id)) }
        }
    }
    for ((id, order) in orders.toMap()) {
        key(id, order.id) {
            LaunchedEffect(api, order.id) {
                val body = JSONObject().put("requestId", order.id)
                var submitted = false
                var delayMs = 2_000L
                while (System.currentTimeMillis() - order.created < 600_000L) {
                    val status = try {
                        val result = api.archiveRequest(id, if (submitted) "record-status" else "record", body)
                        submitted = true
                        delayMs = 5_000L
                        result.getString("status")
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (e: Exception) {
                        if (e is CloudApiException && e.statusCode in 400..499 && e.statusCode !in setOf(401, 408)) {
                            messages[id] = when (e.statusCode) {
                                429 -> "Daily cloud clip limit reached"
                                409 -> "Server busy or unavailable. Refresh camera status."
                                403 -> "Check monitoring and cloud recording access"
                                else -> "Recording request unavailable"
                            }
                            break
                        }
                        messages[id] = "Waiting for connection…"
                        delay(delayMs)
                        delayMs = (delayMs * 2).coerceAtMost(30_000L)
                        continue
                    }
                    messages[id] = recordingMessage(status)
                    if (status !in setOf("queued", "recording")) {
                        if (status == "uploaded") libraryRevision++
                        break
                    }
                    delay(delayMs)
                }
                if (System.currentTimeMillis() - order.created >= 600_000L) messages[id] = "Request expired. Refresh playback."
                store.edit { remove(key(id)) }
                orders.remove(id)
            }
        }
    }
    Text("Greenhouse cameras", color = Color.White, style = MaterialTheme.typography.titleMedium)
    if (inventoryError) Text("Couldn’t load all greenhouses.", color = DashboardTokens.Amber)
    for (house in houses) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(house.name, color = Color.White)
                Text(messages[house.id] ?: house.detail, color = DashboardTokens.Muted, style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = { selected = house.id }, enabled = selected != house.id) {
                Text(if (selected == house.id) "Viewing" else "View clips")
            }
            TextButton(onClick = { start(house) }, enabled = owner != null && house.ready && house.id !in orders) { Text("Record 1 min") }
        }
    }
    Row {
        if (houses.count { it.ready } > 1) TextButton(enabled = owner != null && orders.isEmpty(), onClick = {
            houses.filter { it.ready }.forEach(::start)
        }) { Text("Record all ready cameras") }
        TextButton(onClick = { refresh++ }) { Text("Refresh cameras") }
    }
    HorizontalDivider(color = DashboardTokens.PanelEdge)
    Text(houses.firstOrNull { it.id == selected }?.name ?: currentName, color = Color.White,
        style = MaterialTheme.typography.titleMedium)
    key(selected) { library(selected, libraryRevision) }
}
