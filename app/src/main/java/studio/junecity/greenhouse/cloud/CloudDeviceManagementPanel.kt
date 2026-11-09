package studio.junecity.greenhouse.cloud

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import studio.junecity.greenhouse.settings.SecureSettingsStore
import studio.junecity.greenhouse.ui.DashboardTokens
import java.util.UUID

internal fun connectivityServerCandidates(devices: List<AccountDevice>, preferredGreenhouseId: String): List<AccountDevice> {
    val servers = devices.filter { it.device.role == CloudDeviceRole.GreenhouseServer }
    val preferred = preferredGreenhouseId.takeIf(String::isNotBlank)?.let { greenhouseId ->
        servers.filter { it.device.greenhouseId == greenhouseId }
    }.orEmpty()
    return preferred.ifEmpty { servers }
}

@Composable
fun CloudDeviceManagementPanel(d: (Float) -> Dp, t: (Float) -> TextUnit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val secure = remember { SecureSettingsStore(context) }
    val enrollment = remember { secure.cloudEnrollment() }
    val configuration = remember { runCatching { CloudRuntime.configurationOrNull() }.getOrNull() }
    val gateway = remember(configuration, enrollment) {
        if (configuration != null && enrollment?.role == CloudDeviceRole.ViewerClient) runCatching {
            CognitoAuthManager(context, configuration).also(CognitoAuthManager::initialize).gateway(enrollment.deviceId)
        }.getOrNull() else null
    }
    var devices by remember { mutableStateOf<List<AccountDevice>>(emptyList()) }
    var requests by remember { mutableStateOf<List<ServerPairingRequest>>(emptyList()) }
    var invitation by remember { mutableStateOf<ServerPairingInvitation?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    suspend fun refresh() {
        val api = gateway ?: return
        devices = api.devices()
        requests = api.pendingServerPairings()
    }
    fun perform(action: suspend () -> Unit) {
        scope.launch {
            busy = true; message = null
            runCatching { action(); refresh() }.onFailure { message = accountErrorMessage(it) }
            busy = false
        }
    }

    LaunchedEffect(gateway) {
        if (gateway == null) return@LaunchedEffect
        while (true) {
            runCatching { refresh() }.onFailure { message = accountErrorMessage(it) }
            delay(5_000)
        }
    }

    Column(
        Modifier.fillMaxWidth().background(DashboardTokens.PanelRaised, RoundedCornerShape(d(18f)))
            .border(d(1f), DashboardTokens.PanelEdge, RoundedCornerShape(d(18f))).padding(d(18f)),
        verticalArrangement = Arrangement.spacedBy(d(10f))
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Manage Devices", color = Color.White, fontSize = t(22f), fontWeight = FontWeight.ExtraBold)
                Text("Clients sign in automatically. Greenhouse servers pair by temporary QR code.", color = DashboardTokens.Muted, fontSize = t(13f))
            }
            if (busy) CircularProgressIndicator(Modifier.size(d(26f)), color = DashboardTokens.Amber, strokeWidth = d(3f))
            else DeviceAction("Refresh", false, d, t) { perform { refresh() } }
        }
        when {
            configuration == null -> Text("Cloud device management is unavailable in this local build.", color = DashboardTokens.Muted, fontSize = t(14f))
            enrollment?.role != CloudDeviceRole.ViewerClient -> Text("Server phones are managed from an authorized client phone.", color = DashboardTokens.Muted, fontSize = t(14f))
            gateway == null -> Text("Sign in again to manage this account's devices.", color = DashboardTokens.Danger, fontSize = t(14f))
            else -> {
                message?.let { Text(it, color = DashboardTokens.Danger, fontSize = t(13f)) }
                requests.forEach { request ->
                    DeviceRow("Approval required", "${request.greenhouseName} · ${request.serverDeviceId.takeLast(8)}", DashboardTokens.Amber, d, t) {
                        DeviceAction("Approve", true, d, t) { perform { gateway.approveServerPairing(request.requestId); invitation = null } }
                    }
                }
                val clients = devices.filter { it.device.role == CloudDeviceRole.ViewerClient }
                val servers = devices.filter { it.device.role == CloudDeviceRole.GreenhouseServer }
                clients.forEach { item ->
                    val current = item.device.id == enrollment.deviceId
                    val clientLabel = when(item.device.clientRole){"PRIMARY_CLIENT"->"Primary client";"ADDITIONAL_CLIENT"->"Additional client";else->if(current)"This client" else "Authorized client"}
                    DeviceRow(clientLabel, when { !item.device.enabled -> "Access removed"; current || item.device.online -> "Online"; else -> "Authorized · status unavailable" }, if (item.device.enabled) DashboardTokens.Green else DashboardTokens.Danger, d, t) {
                        if (!current && item.device.enabled) DeviceAction("Remove", false, d, t) { perform { gateway.setDeviceEnabled(item.device.id, false) } }
                    }
                }
                servers.forEachIndexed { index, item ->
                    val active = item.device.greenhouseId == secure.cloudEnrollment()?.greenhouseId && item.device.enabled && !item.device.communicationSuspended
                    DeviceRow("Greenhouse ${index + 1}", when { !item.device.enabled -> "Server revoked"; item.device.communicationSuspended -> "Server ${item.device.id.takeLast(8)} · Disconnected"; item.device.online -> "Server ${item.device.id.takeLast(8)} · Online"; else -> "Server ${item.device.id.takeLast(8)} · Offline" }, if (item.device.online) DashboardTokens.Green else if(item.device.enabled) DashboardTokens.Amber else DashboardTokens.Danger, d, t) {
                        if (item.device.enabled) {
                            DeviceAction(if (active) "Selected" else "View", active, d, t) {
                                item.device.greenhouseId?.let { secure.saveCloudEnrollment(enrollment.copy(greenhouseId = it)) }
                            }
                            DeviceAction("Replace", false, d, t) {
                                perform {
                                    invitation = gateway.createServerInvitation(
                                        requireNotNull(item.device.greenhouseId), "Greenhouse ${index + 1}", item.device.id
                                    )
                                }
                            }
                            DeviceAction(if(item.device.communicationSuspended)"Reconnect" else "Disconnect", false, d, t) { perform { gateway.setDeviceConnected(item.device.id, item.device.communicationSuspended) } }
                            DeviceAction("Revoke Server", false, d, t) { perform { gateway.setDeviceEnabled(item.device.id, false) } }
                        } else {
                            DeviceAction("Pair Again", true, d, t) { perform { invitation = gateway.createServerInvitation(requireNotNull(item.device.greenhouseId), "Greenhouse ${index + 1}", item.device.id) } }
                        }
                    }
                }
                DeviceAction("Add Server", true, d, t, Modifier.fillMaxWidth()) {
                    perform {
                        val greenhouseId = "greenhouse-${UUID.randomUUID()}"
                        invitation = gateway.createServerInvitation(greenhouseId, "Greenhouse ${servers.count { it.device.enabled } + 1}")
                    }
                }
                invitation?.let { invite ->
                    Row(
                        Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(d(14f))).padding(d(10f)),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Image(serverPairingQrBitmap(serverPairingPayload(invite.displayCode)).asImageBitmap(), "Temporary server pairing QR code", Modifier.size(d(150f)))
                        Spacer(Modifier.width(d(16f)))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(d(6f))) {
                            Text("Scan on the greenhouse phone", color = DashboardTokens.Background, fontSize = t(17f), fontWeight = FontWeight.ExtraBold)
                            Text("Expires in 2 minutes. You must approve the server after it scans.", color = Color.DarkGray, fontSize = t(13f))
                            DeviceAction("Close QR", false, d, t) { invitation = null }
                        }
                    }
                }
            }
        }
    }
}

/**
 * The primary client-side entry point for adding a greenhouse server.
 *
 * Opening Connectivity creates the short-lived invitation immediately so the
 * customer never has to discover a separate Add Server action before scanning.
 * The invitation still remains one-use and the server is not enrolled until the
 * signed-in client explicitly approves the pending request.
 */
@Composable
fun CloudServerPairingPanel(
    d: (Float) -> Dp,
    t: (Float) -> TextUnit,
    modifier: Modifier = Modifier
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val secure = remember { SecureSettingsStore(context) }
    val enrollment = remember { secure.cloudEnrollment() }
    val configuration = remember { runCatching { CloudRuntime.configurationOrNull() }.getOrNull() }
    val gateway = remember(configuration, enrollment) {
        if (configuration != null && enrollment?.role == CloudDeviceRole.ViewerClient) runCatching {
            CognitoAuthManager(context, configuration).also(CognitoAuthManager::initialize).gateway(enrollment.deviceId)
        }.getOrNull() else null
    }
    var invitation by remember { mutableStateOf<ServerPairingInvitation?>(null) }
    var pendingRequest by remember { mutableStateOf<ServerPairingRequest?>(null) }
    var devices by remember { mutableStateOf<List<AccountDevice>>(emptyList()) }
    var expiresInSeconds by remember { mutableStateOf(0L) }
    var approved by remember { mutableStateOf(false) }
    var confirmDisconnect by remember { mutableStateOf(false) }
    var confirmRevoke by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    suspend fun createInvitation(
        greenhouseId: String? = null,
        greenhouseName: String? = null,
        replaceServerDeviceId: String? = null
    ) {
        val api = requireNotNull(gateway)
        devices = api.devices()
        val serverNumber = devices.count { it.device.role == CloudDeviceRole.GreenhouseServer && it.device.enabled } + 1
        invitation = api.createServerInvitation(
            greenhouseId = greenhouseId ?: "greenhouse-${UUID.randomUUID()}",
            greenhouseName = greenhouseName ?: "Greenhouse $serverNumber",
            replaceServerDeviceId = replaceServerDeviceId
        )
        pendingRequest = null
        approved = false
        message = null
    }

    fun generateNewCode() {
        scope.launch {
            busy = true
            runCatching { createInvitation() }.onFailure { message = accountErrorMessage(it) }
            busy = false
        }
    }

    LaunchedEffect(gateway) {
        if (gateway == null) return@LaunchedEffect
        busy = true
        runCatching {
            devices = gateway.devices()
            pendingRequest = gateway.pendingServerPairings().firstOrNull()
            val selectedGreenhouse = enrollment?.greenhouseId.orEmpty()
            val knownServer = connectivityServerCandidates(devices, selectedGreenhouse).isNotEmpty()
            if (pendingRequest == null && !knownServer) createInvitation()
        }.onFailure { message = accountErrorMessage(it) }
        busy = false
        while (true) {
            val invite = invitation
            expiresInSeconds = invite?.let { ((it.expiresAtEpochMs - System.currentTimeMillis()) / 1_000L).coerceAtLeast(0L) } ?: 0L
            runCatching {
                devices = gateway.devices()
                pendingRequest = gateway.pendingServerPairings().firstOrNull()
            }.onFailure { message = accountErrorMessage(it) }
            if (invite != null && expiresInSeconds == 0L && pendingRequest == null && !approved) {
                runCatching { createInvitation() }.onFailure { message = accountErrorMessage(it) }
            }
            delay(1_000)
        }
    }

    Column(
        modifier.background(DashboardTokens.PanelRaised, RoundedCornerShape(d(18f)))
            .border(d(1f), DashboardTokens.PanelEdge, RoundedCornerShape(d(18f)))
            .padding(d(18f)),
        verticalArrangement = Arrangement.spacedBy(d(12f))
    ) {
        val selectedGreenhouse = enrollment?.greenhouseId.orEmpty()
        val selectedServers = connectivityServerCandidates(devices, selectedGreenhouse)
        val activeServer = selectedServers.firstOrNull { it.device.enabled && !it.device.communicationSuspended }
        val disconnectedServer = selectedServers.firstOrNull { it.device.enabled && it.device.communicationSuspended }
        val revokedServer = selectedServers.firstOrNull { !it.device.enabled }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Pair greenhouse server", color = Color.White, fontSize = t(24f), fontWeight = FontWeight.ExtraBold)
                Text(
                    "Scan this code on the phone that will stay in the greenhouse.",
                    color = DashboardTokens.Muted,
                    fontSize = t(15f),
                    lineHeight = t(19f)
                )
            }
            if (busy) CircularProgressIndicator(Modifier.size(d(24f)), color = DashboardTokens.Amber, strokeWidth = d(3f))
        }

        when {
            configuration == null -> Text("Server pairing is unavailable in this local build.", color = DashboardTokens.Muted, fontSize = t(13f))
            enrollment?.role != CloudDeviceRole.ViewerClient -> Text("Pairing codes are displayed on an authorized client phone.", color = DashboardTokens.Muted, fontSize = t(13f))
            gateway == null -> Text("Sign in again to create a server pairing code.", color = DashboardTokens.Danger, fontSize = t(13f))
            confirmDisconnect && activeServer != null -> {
                Text("Disconnect this greenhouse server?", color = DashboardTokens.Amber, fontSize = t(22f), fontWeight = FontWeight.ExtraBold)
                Text("Communication pauses, but the device credentials and greenhouse data remain registered. You can reconnect it without scanning again.", color = DashboardTokens.Muted, fontSize = t(15f), lineHeight = t(20f))
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(d(10f))) {
                    DeviceAction("Cancel", false, d, t, Modifier.fillMaxWidth()) { confirmDisconnect = false }
                    DeviceAction("Confirm Disconnect", true, d, t, Modifier.fillMaxWidth()) {
                        val server = activeServer
                        scope.launch {
                            busy = true
                            runCatching {
                                gateway.setDeviceConnected(server.device.id, false)
                                devices = gateway.devices(); invitation = null; pendingRequest = null; approved = false; confirmDisconnect = false
                                message = "Server disconnected. Reconnect it whenever you are ready."
                            }.onFailure { message = accountErrorMessage(it) }
                            busy = false
                        }
                    }
                }
            }
            confirmRevoke && activeServer != null -> {
                Text("Revoke this greenhouse server?", color = DashboardTokens.Danger, fontSize = t(22f), fontWeight = FontWeight.ExtraBold)
                Text("Monitoring data stays saved. The server loses access immediately and can be paired again with a new QR code.", color = DashboardTokens.Muted, fontSize = t(15f), lineHeight = t(20f))
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(d(10f))) {
                    DeviceAction("Cancel", false, d, t, Modifier.fillMaxWidth()) { confirmRevoke = false }
                    DeviceAction("Confirm Revoke Server", false, d, t, Modifier.fillMaxWidth(), danger = true) {
                        val server = activeServer
                        scope.launch {
                            busy = true
                            runCatching {
                                gateway.setDeviceEnabled(server.device.id, false)
                                devices = gateway.devices()
                                invitation = null; pendingRequest = null; approved = false; confirmRevoke = false
                                message = "Server access revoked. Choose Pair Again when ready."
                            }.onFailure { message = accountErrorMessage(it) }
                            busy = false
                        }
                    }
                }
            }
            approved -> {
                Text("Greenhouse server approved", color = DashboardTokens.Green, fontSize = t(18f), fontWeight = FontWeight.ExtraBold)
                Text("The other phone is now registered as this account's greenhouse server.", color = DashboardTokens.Muted, fontSize = t(13f))
                DeviceAction("Pair another server", true, d, t, Modifier.fillMaxWidth()) { generateNewCode() }
            }
            pendingRequest != null -> {
                Text("Approval required", color = DashboardTokens.Amber, fontSize = t(18f), fontWeight = FontWeight.ExtraBold)
                Text("The greenhouse phone scanned the code. Approve it to finish pairing.", color = DashboardTokens.Muted, fontSize = t(13f))
                DeviceAction("Approve greenhouse server", true, d, t, Modifier.fillMaxWidth()) {
                    val request = pendingRequest ?: return@DeviceAction
                    scope.launch {
                        busy = true
                        runCatching { gateway.approveServerPairing(request.requestId) }
                            .onSuccess {
                                secure.saveCloudEnrollment(requireNotNull(enrollment).copy(greenhouseId = request.greenhouseId))
                                approved = true; invitation = null; pendingRequest = null; message = null
                            }
                            .onFailure { message = accountErrorMessage(it) }
                        busy = false
                    }
                }
            }
            invitation == null && activeServer != null -> {
                Text("Greenhouse server connected", color = DashboardTokens.Green, fontSize = t(22f), fontWeight = FontWeight.ExtraBold)
                Text("Server ${activeServer.device.id.takeLast(8)} · ${if (activeServer.device.online) "Online" else "Authorized"}", color = Color.White, fontSize = t(17f), fontWeight = FontWeight.Bold)
                Text("Disconnect temporarily, revoke its credentials, or replace it without losing greenhouse settings.", color = DashboardTokens.Muted, fontSize = t(15f), lineHeight = t(20f))
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(d(10f))) {
                    DeviceAction("Replace Server", false, d, t, Modifier.fillMaxWidth()) {
                        val server = activeServer
                        scope.launch {
                            busy = true
                            runCatching {
                                createInvitation(selectedGreenhouse, "Greenhouse", server.device.id)
                            }.onFailure { message = accountErrorMessage(it) }
                            busy = false
                        }
                    }
                    DeviceAction("Disconnect Server", false, d, t, Modifier.fillMaxWidth()) { confirmDisconnect = true }
                    DeviceAction("Revoke Server", false, d, t, Modifier.fillMaxWidth(), danger = true) { confirmRevoke = true }
                }
            }
            invitation == null && disconnectedServer != null -> {
                Text("Greenhouse server disconnected", color = DashboardTokens.Amber, fontSize = t(22f), fontWeight = FontWeight.ExtraBold)
                Text("Credentials and greenhouse data are preserved. Reconnecting does not require a new QR code.", color = DashboardTokens.Muted, fontSize = t(15f), lineHeight = t(20f))
                DeviceAction("Reconnect Server", true, d, t, Modifier.fillMaxWidth()) {
                    val server = disconnectedServer
                    scope.launch {
                        busy = true
                        runCatching {
                            gateway.setDeviceConnected(server.device.id, true)
                            devices = gateway.devices()
                        }.onFailure { message = accountErrorMessage(it) }
                        busy = false
                    }
                }
            }
            invitation == null && revokedServer != null -> {
                Text("Greenhouse server revoked", color = DashboardTokens.Danger, fontSize = t(18f), fontWeight = FontWeight.ExtraBold)
                Text("Its old credential cannot reconnect. Pair again to issue a fresh device credential while preserving greenhouse data.", color = DashboardTokens.Muted, fontSize = t(13f))
                DeviceAction("Pair Again", true, d, t, Modifier.fillMaxWidth()) {
                    val server = revokedServer
                    scope.launch {
                        busy = true
                        runCatching { createInvitation(server.device.greenhouseId ?: selectedGreenhouse, "Greenhouse", server.device.id) }.onFailure { message = accountErrorMessage(it) }
                        busy = false
                    }
                }
            }
            invitation != null -> {
                val invite = requireNotNull(invitation)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(d(154f)).background(Color.White, RoundedCornerShape(d(12f))).padding(d(5f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            serverPairingQrBitmap(serverPairingPayload(invite.displayCode)).asImageBitmap(),
                            "Temporary server pairing QR code",
                            Modifier.fillMaxWidth()
                        )
                    }
                    Spacer(Modifier.width(d(14f)))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(d(7f))) {
                        Text("Ready to scan", color = DashboardTokens.Green, fontSize = t(17f), fontWeight = FontWeight.ExtraBold)
                        Text("Expires in ${expiresInSeconds / 60}:${(expiresInSeconds % 60).toString().padStart(2, '0')}", color = Color.White, fontSize = t(14f), fontWeight = FontWeight.Bold)
                        Text("On the greenhouse phone, choose Scan QR Code to Pair as Server.", color = DashboardTokens.Muted, fontSize = t(12f))
                        DeviceAction("Refresh code", false, d, t, Modifier.fillMaxWidth()) { generateNewCode() }
                    }
                }
            }
        }
        message?.let { Text(it, color = DashboardTokens.Danger, fontSize = t(15f), lineHeight = t(20f)) }
    }
}

@Composable
private fun DeviceRow(title: String, subtitle: String, status: Color, d: (Float) -> Dp, t: (Float) -> TextUnit, actions: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Color(0x55101416), RoundedCornerShape(d(14f))).padding(horizontal = d(14f), vertical = d(10f)),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Spacer(Modifier.size(d(10f)).background(status, RoundedCornerShape(d(8f))))
        Spacer(Modifier.width(d(10f)))
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = t(16f), fontWeight = FontWeight.Bold)
            Text(subtitle, color = DashboardTokens.Muted, fontSize = t(12f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(d(7f)), verticalAlignment = Alignment.CenterVertically) { actions() }
    }
}

@Composable
private fun DeviceAction(
    label: String,
    primary: Boolean,
    d: (Float) -> Dp,
    t: (Float) -> TextUnit,
    modifier: Modifier = Modifier,
    danger: Boolean = false,
    onClick: () -> Unit
) {
    val buttonModifier = modifier.heightIn(min = d(54f))
    val labelText: @Composable (Color) -> Unit = { color ->
        Text(
            text = label,
            color = color,
            fontSize = t(16f),
            fontWeight = FontWeight.ExtraBold,
            maxLines = 1
        )
    }
    when {
        danger -> Button(
            onClick = onClick,
            modifier = buttonModifier,
            shape = RoundedCornerShape(d(14f)),
            colors = ButtonDefaults.buttonColors(containerColor = DashboardTokens.Danger, contentColor = Color.White),
            contentPadding = PaddingValues(horizontal = d(18f), vertical = d(10f))
        ) { labelText(Color.White) }
        primary -> Button(
            onClick = onClick,
            modifier = buttonModifier,
            shape = RoundedCornerShape(d(14f)),
            colors = ButtonDefaults.buttonColors(containerColor = DashboardTokens.Amber, contentColor = DashboardTokens.Background),
            contentPadding = PaddingValues(horizontal = d(18f), vertical = d(10f))
        ) { labelText(DashboardTokens.Background) }
        else -> OutlinedButton(
            onClick = onClick,
            modifier = buttonModifier,
            shape = RoundedCornerShape(d(14f)),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
            contentPadding = PaddingValues(horizontal = d(18f), vertical = d(10f))
        ) { labelText(Color.White) }
    }
}
