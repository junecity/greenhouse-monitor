package studio.junecity.greenhouse.ui

import android.widget.Toast
import java.io.Writer
import java.io.File
import studio.junecity.greenhouse.model.HistoryReading
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import studio.junecity.greenhouse.cloud.CloudDeviceRole
import studio.junecity.greenhouse.data.HistoryStore
import studio.junecity.greenhouse.data.viewerHistorySuffix
import studio.junecity.greenhouse.model.GreenhouseUiState
import studio.junecity.greenhouse.settings.SecureSettingsStore
import studio.junecity.greenhouse.settings.isStandaloneClient

internal fun historyCsvText(value: String): String {
    val text = if (value.trimStart().firstOrNull() in listOf('=', '+', '-', '@')) "'$value" else value
    return "\"" + text.replace("\"", "\"\"") + "\""
}

internal fun writeHistoryCsv(writer: Writer, assignments: Map<String, String>, houseId: String,
    visit: ((HistoryReading) -> Unit) -> Unit): Int {
    var count = 0
    writer.appendLine("time_utc,sensor_id,sensor_name,temperature_f,relative_humidity_percent,light_level")
    visit { row ->
        if ((assignments[row.sensorId] ?: "main") == houseId) {
            writer.appendLine(listOf(Instant.ofEpochMilli(row.timestampEpochMs).toString(),
                historyCsvText(row.sensorId), historyCsvText(row.sensorName), row.temperatureF.toString(),
                row.humidityPercent.toString(), historyCsvText(row.lightLevel.orEmpty())).joinToString(","))
            count++
        }
    }
    return count
}

@Composable
internal fun historyExporter(state: GreenhouseUiState): () -> Unit {
    val context = LocalContext.current
    val secure = remember(context) { SecureSettingsStore(context) }
    val owner = secure.cloudEnrollment()
    val house = state.preferences.activeGreenhouse
    val source = org.json.JSONArray(listOf(owner?.tenantId, owner?.deviceId, owner?.greenhouseId,
        owner.isStandaloneClient(), house.id)).toString()
    var requested by rememberSaveable { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        val matches = requested == source && secure.cloudEnrollment() == owner
        requested = null
        if (uri != null && matches) scope.launch {
            val message = try {
                val assignments = state.preferences.sensorGreenhouseIds + state.sensors.associate { it.id to it.greenhouseId }
                val suffix = when {
                    owner.isStandaloneClient() -> "_standalone"
                    owner?.role == CloudDeviceRole.ViewerClient -> viewerHistorySuffix(owner)
                    else -> ""
                }
                val count = withContext(Dispatchers.IO) {
                    val staged = File.createTempFile("history-export-", ".csv", context.cacheDir)
                    try {
                        check(secure.cloudEnrollment() == owner)
                        val rows = HistoryStore(context, suffix).use { database ->
                            staged.bufferedWriter().use { writer -> writeHistoryCsv(writer, assignments, house.id, database::visitReadings) }
                        }
                        fun publish() {
                            checkNotNull(context.contentResolver.openOutputStream(uri, "wt")).use { output ->
                                staged.inputStream().use { it.copyTo(output) }
                            }
                        }
                        if (owner != null) checkNotNull(secure.withCurrentEnrollment(owner, ::publish))
                        else if (secure.cloudEnrollment() == null) publish() else error("Enrollment changed")
                        rows
                    } finally { staged.delete() }
                }
                "Exported $count readings for ${house.name}."
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { "Export interrupted. Choose Export saved readings to try again." }
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }
    return {
        requested = source
        val filename = house.name.replace(Regex("[^\\p{L}\\p{N} _-]"), "_").take(80).ifBlank { "Greenhouse" }
        picker.launch("$filename readings.csv")
    }
}
