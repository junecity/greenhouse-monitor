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
import studio.junecity.greenhouse.backup.*
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
    var legacy by remember(uri) { mutableStateOf(false) }
    val localScope = localBackupScope(enrollment)
    val local = remember(localScope) { localScope?.let { LocalBackups(context, it) } }
    var copies by remember(local) { mutableStateOf(local?.copies().orEmpty()) }
    var chosenCopy by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(local) {
        while (true) {
            copies = withContext(Dispatchers.IO) { local?.copies().orEmpty() }
            kotlinx.coroutines.delay(10_000)
        }
    }
    val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) {
        chosenCopy = null
        importing = false
        uri = it
    }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
        chosenCopy = null
        importing = true
        uri = it
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Backup & restore", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text(if (viewer) "Save this phone’s forecast alerts. For greenhouse settings and complete sensor history, open Backup on the greenhouse phone."
            else "Save floor plans, sensor assignments, readings, alert rules and greenhouse settings in a backup file.",
            color = DashboardTokens.Muted, fontSize = 16.sp)
        Text("On this phone", color = Color.White, fontWeight = FontWeight.Bold)
        Text("Automatic daily copies · Latest three kept here", color = DashboardTokens.Muted)
        if (copies.isEmpty()) Text("Waiting for the first backup", color = DashboardTokens.Muted)
        copies.forEach { file ->
            val date = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(java.util.Date(file.lastModified()))
            OutlinedButton({
                importing = true
                chosenCopy = file.name
                uri = Uri.fromFile(file)
            }, enabled = !busy) { Text("Restore · $date", color = Color.White) }
        }
        HorizontalDivider(color = DashboardTokens.Divider)
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
        title = { Text(if (importing) "Restore this backup?" else "Save backup?", color = Color.White) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(when {
                    !importing -> "Anyone with this file can read the greenhouse data. Keep it somewhere private."
                    viewer -> "Replace this phone’s forecast alert settings with the saved settings."
                    else -> "Replace greenhouse settings and sensor assignments. Saved readings will be merged with existing history. On a new phone, enter your sensor API key separately."
                }, color = Color.White)
                if (legacy) OutlinedTextField(password, { password = it }, singleLine = true, enabled = !busy,
                    label = { Text("Backup password") }, visualTransformation = PasswordVisualTransformation(), colors = darkFieldColors())

            }
        }, dismissButton = { TextButton({ uri = null }, enabled = !busy) { Text("Cancel") } },
        confirmButton = {
            TextButton(enabled = !busy && (!legacy || password.isNotEmpty()), onClick = {
                val secret = password.toCharArray()
                busy = true
                message = ""
                scope.launch {
                    var needsPassword = false
                    try {
                        val count = withContext(Dispatchers.IO) {
                            val localCopy = chosenCopy?.let { name -> requireNotNull(local?.copies()?.find { it.name == name }) }
                            val target = if (localCopy != null) requireNotNull(localScope) else verifiedBackupScope(context, enrollment)
                            val backup = CustomerBackup(context, enrollment)
                            val resolver = context.contentResolver
                            if (localCopy != null) localCopy.inputStream().use { backup.restore(it, secret, target) }
                            else if (importing) requireNotNull(resolver.openInputStream(selected)).use { backup.restore(it, secret, target) }
                            else requireNotNull(resolver.openOutputStream(selected, "wt")).use { backup.write(it, target) }
                        }
                        if (importing) onRestored()
                        val action = if (importing) "Restored" else "Saved"
                        message = if (viewer) "$action forecast alert settings." else "$action greenhouse settings and $count historical readings."
                    } catch (e: LegacyPasswordRequired) {
                        legacy = true
                        needsPassword = true
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
                        busy = false
                        if (!needsPassword) uri = null
                    }
                }
            }) { Text(if (busy) "Working…" else if (importing) "Restore" else "Save", color = DashboardTokens.Amber) }
        })
}
