package studio.junecity.greenhouse.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import studio.junecity.greenhouse.backup.CustomerBackup
import studio.junecity.greenhouse.backup.verifiedBackupScope
import studio.junecity.greenhouse.cloud.CloudDeviceRole
import studio.junecity.greenhouse.settings.SecureSettingsStore
import studio.junecity.greenhouse.settings.isStandaloneClient
import java.time.LocalDate

@Composable
internal fun BackupPanel(onRestored: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val enrollment = remember { SecureSettingsStore(context).cloudEnrollment() }
    val viewer = enrollment?.role == CloudDeviceRole.ViewerClient && !enrollment.isStandaloneClient()
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var message by rememberSaveable { mutableStateOf("") }
    var uri by rememberSaveable { mutableStateOf<Uri?>(null) }
    var importing by rememberSaveable { mutableStateOf(false) }
    var password by remember(uri) { mutableStateOf("") }
    var repeat by remember(uri) { mutableStateOf("") }
    val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) {
        importing = false
        uri = it
    }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
        importing = true
        uri = it
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Backup & restore", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text(if (viewer) "Save this phone’s forecast alerts. For greenhouse settings and complete sensor history, open Backup on the greenhouse phone."
            else "Save floor plans, sensor assignments, readings, alert rules and greenhouse settings in one encrypted file.",
            color = DashboardTokens.Muted, fontSize = 16.sp)
        Button({ create.launch("Greenhouse-${LocalDate.now()}.ghbackup") }, enabled = !busy,
            modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = DashboardTokens.Amber, contentColor = DashboardTokens.Background)) {
            Text("Save backup")
        }
        OutlinedButton({ open.launch(arrayOf("*/*")) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text("Restore from file", color = Color.White)
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = DashboardTokens.Amber)
        if (message.isNotEmpty()) Text(message, color = Color.White, fontSize = 16.sp)
    }

    val selected = uri ?: return
    AlertDialog(onDismissRequest = { if (!busy) uri = null }, containerColor = DashboardTokens.PanelRaised,
        title = { Text(if (importing) "Restore this backup?" else "Protect your backup", color = Color.White) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(when {
                    !importing -> "Choose a backup password of at least 10 characters. Keep it for recovery."
                    viewer -> "Replace this phone’s forecast alert settings with the saved settings."
                    else -> "Replace greenhouse settings and sensor assignments. Saved readings will be merged with existing history. On a new phone, enter your sensor API key separately."
                }, color = Color.White)
                OutlinedTextField(password, { password = it }, singleLine = true, enabled = !busy,
                    label = { Text("Backup password") }, visualTransformation = PasswordVisualTransformation(), colors = darkFieldColors())
                if (!importing) OutlinedTextField(repeat, { repeat = it }, singleLine = true, enabled = !busy,
                    label = { Text("Confirm password") }, visualTransformation = PasswordVisualTransformation(), colors = darkFieldColors())
            }
        }, dismissButton = { TextButton({ uri = null }, enabled = !busy) { Text("Cancel") } },
        confirmButton = {
            TextButton(enabled = !busy && password.length >= 10 && (importing || password == repeat), onClick = {
                val secret = password.toCharArray()
                busy = true
                message = ""
                scope.launch {
                    try {
                        val count = withContext(Dispatchers.IO) {
                            val target = verifiedBackupScope(context, enrollment)
                            val backup = CustomerBackup(context, enrollment)
                            val resolver = context.contentResolver
                            if (importing) requireNotNull(resolver.openInputStream(selected)).use { backup.restore(it, secret, target) }
                            else requireNotNull(resolver.openOutputStream(selected, "wt")).use { backup.write(it, secret, target) }
                        }
                        if (importing) onRestored()
                        val action = if (importing) "Restored" else "Saved"
                        message = if (viewer) "$action forecast alert settings." else "$action greenhouse settings and $count historical readings."
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        message = when (e) {
                            is IllegalArgumentException, is IllegalStateException -> e.message?.takeIf { it.startsWith("This backup") ||
                                it.startsWith("Choose a Greenhouse") || it.startsWith("Phone setup") || it.startsWith("This phone") }
                            else -> null
                        } ?: if (importing) "Restore failed. Check your connection, backup file and password."
                            else "Backup failed. Check your connection and available storage, then save a new file."
                    } finally {
                        secret.fill(' ')
                        password = ""
                        repeat = ""
                        busy = false
                        uri = null
                    }
                }
            }) { Text(if (busy) "Working…" else if (importing) "Restore" else "Save", color = DashboardTokens.Amber) }
        })
}
