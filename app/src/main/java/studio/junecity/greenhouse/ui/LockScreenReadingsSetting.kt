package studio.junecity.greenhouse.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import studio.junecity.greenhouse.settings.SecureSettingsStore
import studio.junecity.greenhouse.widget.ClimateWidget
import studio.junecity.greenhouse.widget.LockScreenClimate

private enum class NoticeAccess { Allowed, AppBlocked, ChannelBlocked }

private fun noticeAccess(context: Context): NoticeAccess = when {
    !NotificationManagerCompat.from(context).areNotificationsEnabled() -> NoticeAccess.AppBlocked
    LockScreenClimate.channelBlocked(context) -> NoticeAccess.ChannelBlocked
    else -> NoticeAccess.Allowed
}

@Composable
internal fun LockScreenReadingsSetting(t: (Float) -> TextUnit) {
    val context = LocalContext.current
    val secure = remember(context) { SecureSettingsStore(context) }
    var enrollment by remember(secure) { mutableStateOf(secure.cloudEnrollment()) }
    DisposableEffect(secure) {
        val unsubscribe = secure.watchEnrollment { enrollment = secure.cloudEnrollment() }
        enrollment = secure.cloudEnrollment()
        onDispose { unsubscribe() }
    }
    val owner = enrollment ?: return
    var enabled by remember(owner) { mutableStateOf(LockScreenClimate.enabled(context, owner)) }
    var access by remember(owner) { mutableStateOf(noticeAccess(context)) }
    var manualSettings by remember(owner) { mutableStateOf(false) }

    fun syncAccess() {
        val previous = access
        access = noticeAccess(context)
        if (enabled && previous != access) ClimateWidget.lockScreenChanged(context)
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { syncAccess() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) syncAccess()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(enabled, role = Role.Switch) { value ->
        LockScreenClimate.setEnabled(context, owner, value)
        enabled = LockScreenClimate.enabled(context, owner)
        access = noticeAccess(context)
        manualSettings = false
        if (enabled && Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }, verticalAlignment = Alignment.CenterVertically) {
        Text("Lock screen readings", modifier = Modifier.weight(1f), color = Color.White, fontSize = t(17f))
        Switch(checked = enabled, onCheckedChange = null,
            colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = DashboardTokens.Amber))
    }
    Text("Show the selected greenhouse’s average temperature and humidity in a silent notification anyone can read while this phone is locked.",
        color = DashboardTokens.Muted, fontSize = t(15f), lineHeight = t(20f))
    if (enabled && access == NoticeAccess.Allowed) {
        Text("If the readings are hidden, allow silent notifications on your lock screen in Android Settings.",
            color = DashboardTokens.Muted, fontSize = t(15f), lineHeight = t(20f))
    }
    if (enabled && access != NoticeAccess.Allowed) {
        Text(if (access == NoticeAccess.AppBlocked) "Android has blocked Greenhouse Studio notifications."
            else "Android has blocked the lock screen readings notification.",
            color = DashboardTokens.Amber, fontSize = t(15f))
        TextButton(onClick = {
            val intent = if (access == NoticeAccess.ChannelBlocked)
                Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_CHANNEL_ID, LockScreenClimate.CHANNEL_ID)
            else Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            try {
                context.startActivity(intent.putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
            } catch (_: ActivityNotFoundException) {
                manualSettings = true
            }
        }) { Text("Notification settings", color = DashboardTokens.Amber) }
        if (manualSettings) Text("Open Android Settings → Apps → Greenhouse Studio → Notifications.",
            color = DashboardTokens.Muted, fontSize = t(15f))
    }
}
