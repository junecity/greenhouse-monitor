package studio.junecity.greenhouse.cloud

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
                    DeviceRow(if (current) "This client" else "Authorized client", when { !item.device.enabled -> "Access removed"; current || item.device.online -> "Online"; else -> "Authorized · status unavailable" }, if (item.device.enabled) DashboardTokens.Green else DashboardTokens.Danger, d, t) {
                        if (!current && item.device.enabled) DeviceAction("Remove", false, d, t) { perform { gateway.setDeviceEnabled(item.device.id, false) } }
                    }
                }
                servers.forEachIndexed { index, item ->
                    val active = item.device.greenhouseId == secure.cloudEnrollment()?.greenhouseId && item.device.enabled
                    DeviceRow("Greenhouse ${index + 1}", when { !item.device.enabled -> "Server removed"; item.device.online -> "Server ${item.device.id.takeLast(8)} · Online"; else -> "Server ${item.device.id.takeLast(8)} · Offline" }, if (item.device.online) DashboardTokens.Green else if(item.device.enabled) DashboardTokens.Amber else DashboardTokens.Danger, d, t) {
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
                            DeviceAction("Remove", false, d, t) { perform { gateway.setDeviceEnabled(item.device.id, false) } }
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
                            Text("Expires in 5 minutes. You must approve the server after it scans.", color = Color.DarkGray, fontSize = t(13f))
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
    var expiresInSeconds by remember { mutableStateOf(0L) }
    var approved by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    suspend fun createInvitation() {
        val api = requireNotNull(gateway)
        val devices = api.devices()
        val serverNumber = devices.count { it.device.role == CloudDeviceRole.GreenhouseServer && it.device.enabled } + 1
        invitation = api.createServerInvitation(
            greenhouseId = "greenhouse-${UUID.randomUUID()}",
            greenhouseName = "Greenhouse $serverNumber"
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
            pendingRequest = gateway.pendingServerPairings().firstOrNull()
            if (pendingRequest == null) createInvitation()
        }.onFailure { message = accountErrorMessage(it) }
        busy = false
        while (true) {
            val invite = invitation
            expiresInSeconds = invite?.let { ((it.expiresAtEpochMs - System.currentTimeMillis()) / 1_000L).coerceAtLeast(0L) } ?: 0L
            runCatching {
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
            .padding(d(16f)),
        verticalArrangement = Arrangement.spacedBy(d(8f))
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Pair greenhouse server", color = Color.White, fontSize = t(20f), fontWeight = FontWeight.ExtraBold)
                Text("Scan this code on the phone that will stay in the greenhouse.", color = DashboardTokens.Muted, fontSize = t(12f))
            }
            if (busy) CircularProgressIndicator(Modifier.size(d(24f)), color = DashboardTokens.Amber, strokeWidth = d(3f))
        }

        when {
            configuration == null -> Text("Server pairing is unavailable in this local build.", color = DashboardTokens.Muted, fontSize = t(13f))
            enrollment?.role != CloudDeviceRole.ViewerClient -> Text("Pairing codes are displayed on an authorized client phone.", color = DashboardTokens.Muted, fontSize = t(13f))
            gateway == null -> Text("Sign in again to create a server pairing code.", color = DashboardTokens.Danger, fontSize = t(13f))
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
                            .onSuccess { approved = true; invitation = null; pendingRequest = null; message = null }
                            .onFailure { message = accountErrorMessage(it) }
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
        message?.let { Text(it, color = DashboardTokens.Danger, fontSize = t(12f)) }
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
private fun DeviceAction(label: String, primary: Boolean, d: (Float) -> Dp, t: (Float) -> TextUnit, modifier: Modifier = Modifier, onClick: () -> Unit) {
    if (primary) Button(
        onClick = onClick, modifier = modifier.height(d(42f)), shape = RoundedCornerShape(d(14f)),
        colors = ButtonDefaults.buttonColors(containerColor = DashboardTokens.Amber, contentColor = DashboardTokens.Background)
    ) { Text(label, fontSize = t(13f), fontWeight = FontWeight.ExtraBold) }
    else OutlinedButton(
        onClick = onClick, modifier = modifier.height(d(42f)), shape = RoundedCornerShape(d(14f)),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
    ) { Text(label, fontSize = t(13f), fontWeight = FontWeight.Bold) }
}
