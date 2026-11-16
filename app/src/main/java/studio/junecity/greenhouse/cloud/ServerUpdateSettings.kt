package studio.junecity.greenhouse.cloud

import android.content.ActivityNotFoundException
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import studio.junecity.greenhouse.BuildConfig
import studio.junecity.greenhouse.settings.CloudEnrollment
import studio.junecity.greenhouse.settings.SecureSettingsStore
import studio.junecity.greenhouse.ui.DashboardTokens

private data class UpdateSettingsState(val optedIn: Boolean, val mode: String, val installStatus: String?)

private fun readUpdateSettings(context: Context, enrollment: CloudEnrollment) = UpdateSettingsState(
    ManagedServerUpdate.directUpdatesOptedIn(context, enrollment),
    ManagedServerUpdate.mode(context, enrollment),
    ManagedServerUpdate.status(context, enrollment.deviceId)
)

@Composable
internal fun ServerUpdateSettings(enrollment: CloudEnrollment, d: (Float) -> Dp, t: (Float) -> TextUnit) {
    if (!BuildConfig.DIRECT_SERVER_UPDATES || enrollment.role != CloudDeviceRole.GreenhouseServer) return
    val context = LocalContext.current
    val managed = ManagedServerUpdate.enabled(context)
    if (!managed && !ManagedServerUpdate.canEnableDirectUpdates(context)) return
    val secure = remember(context) { SecureSettingsStore(context) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var state by remember(context, enrollment) { mutableStateOf(readUpdateSettings(context, enrollment)) }
    var error by remember(enrollment) { mutableStateOf<String?>(null) }
    DisposableEffect(context, enrollment, lifecycle) {
        fun refresh() { state = readUpdateSettings(context, enrollment) }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh()
        }
        lifecycle.addObserver(observer)
        val stopObserving = ManagedServerUpdate.observeChanges(context) { refresh() }
        onDispose { lifecycle.removeObserver(observer); stopObserving() }
    }

    val shape = RoundedCornerShape(d(18f))
    Column(Modifier.fillMaxWidth().background(DashboardTokens.PanelRaised, shape)
        .border(d(1f), DashboardTokens.PanelEdge, shape).padding(d(18f)),
        verticalArrangement = Arrangement.spacedBy(d(12f))) {
        Text("Remote updates", color = Color.White, fontWeight = FontWeight.Bold, fontSize = t(24f))
        if (!managed && enrollment.deviceSecret.isNullOrBlank()) {
            Text("Pair this phone from your client’s Connectivity settings to enable remote updates.",
                color = DashboardTokens.Muted, fontSize = t(16f))
            return@Column
        }
        val detail = when (state.mode) {
            "MANAGED" -> "This managed phone installs Greenhouse Studio updates requested by a paired client."
            "SELF_UPDATE" -> "Ready. Tap Update server on your client phone when a release is available. Android may occasionally ask you to confirm here."
            "CONFIRM_ON_SERVER" -> "Ready. Start updates on your client phone, then tap Install on this phone when Android asks."
            else -> if (state.optedIn) "One more step. In Android settings, turn on Allow from this source for Greenhouse Studio, then return here."
                else "Let your paired client update Greenhouse Studio on this phone. Each update must match the installed app’s signature."
        }
        Text(detail, color = DashboardTokens.Muted, fontSize = t(16f), lineHeight = t(22f))

        if (state.mode == "MANUAL") {
            DeviceAction(if (state.optedIn) "Open Android settings" else "Allow remote updates", true, d, t, Modifier.fillMaxWidth()) {
                if (secure.cloudEnrollment() != enrollment) return@DeviceAction
                error = null
                ManagedServerUpdate.setDirectUpdatesEnabled(context, enrollment, true)
                state = readUpdateSettings(context, enrollment)
                if (state.mode == "MANUAL") {
                    try { context.startActivity(ManagedServerUpdate.installSourceSettings(context)) }
                    catch (_: ActivityNotFoundException) {
                        error = "Open Android Settings, search for Install unknown apps, and select Greenhouse Studio."
                    } catch (_: SecurityException) {
                        error = "Android restricted this permission. Check Install unknown apps in Android Settings."
                    }
                }
            }
        }
        if (state.installStatus == "DECLINED" && state.mode != "MANUAL") {
            Text("Update declined. Your client can request it again.", color = DashboardTokens.Muted, fontSize = t(15f))
        }
        if (state.installStatus == "APPROVAL_REQUIRED" && state.mode != "MANUAL") {
            DeviceAction("Approve update", true, d, t, Modifier.fillMaxWidth()) {
                if (secure.cloudEnrollment() != enrollment) return@DeviceAction
                error = if (ManagedServerUpdate.openPendingApproval(context, enrollment)) null
                    else "The installer is preparing a fresh confirmation. Try again after the next server check-in."
            }
        }
        if (state.optedIn && !managed) {
            DeviceAction("Turn off remote updates", false, d, t, Modifier.fillMaxWidth()) {
                if (secure.cloudEnrollment() != enrollment) return@DeviceAction
                ManagedServerUpdate.setDirectUpdatesEnabled(context, enrollment, false)
                state = readUpdateSettings(context, enrollment)
                error = null
            }
        }
        error?.let { Text(it, color = DashboardTokens.Danger, fontSize = t(15f)) }
    }
}
