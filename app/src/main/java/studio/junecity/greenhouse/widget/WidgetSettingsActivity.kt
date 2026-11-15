package studio.junecity.greenhouse.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import studio.junecity.greenhouse.model.sensorHouse
import studio.junecity.greenhouse.settings.*

class WidgetSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        if (AppWidgetManager.getInstance(this).getAppWidgetInfo(id)?.provider != ComponentName(this, ClimateWidget::class.java)) { finish(); return }
        val secure = SecureSettingsStore(this)
        val owner = secure.cloudEnrollment()
        val options = ClimateWidget.settings(this, id)
        val suffix = if (owner.isStandaloneClient()) "_standalone" else ""
        val prefs = AppPreferencesStore(this, suffix).load()
        val rows = CurrentSensorStore(this, suffix).load()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFFFFB347))) {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Greenhouse widget", style = MaterialTheme.typography.headlineSmall)
                        if (owner == null || !ClimateWidget.viewerReady(this@WidgetSettingsActivity, owner)) {
                            Text("Open Greenhouse Studio and sync your greenhouse before adding a widget.")
                            TextButton(onClick = { finish() }) { Text("Close") }
                        } else {
                            val ours = owner.widgetScope() == options.getString("scope", "")
                            var house by rememberSaveable { mutableStateOf(prefs.greenhouses.find { ours && it.id == options.getString("house","") }?.id ?: prefs.activeGreenhouse.id) }
                            val sensors = rows.filter { prefs.sensorHouse(it) == house }
                            var sensor by rememberSaveable(house) { mutableStateOf(sensors.find { ours && it.id == options.getString("sensor","") }?.id ?: "") }
                            var metric by rememberSaveable { mutableStateOf(WidgetMetric.entries.find { ours && it.name == options.getString("metric","") } ?: WidgetMetric.Both) }
                            var celsius by rememberSaveable { mutableStateOf(ours && options.getBoolean("celsius", false)) }
                            Choice("Greenhouse", prefs.greenhouses.first { it.id == house }.name, prefs.greenhouses.map { it.id to it.name }) { house = it }
                            Choice("Reading", sensors.find { it.id == sensor }?.name ?: "Greenhouse average",
                                listOf("" to "Greenhouse average") + sensors.map { it.id to it.name }) { sensor = it }
                            Choice("Show", metric.title, WidgetMetric.entries.map { it to it.title }) { metric = it }
                            if (metric != WidgetMetric.Humidity) Choice("Temperature unit", if (celsius) "Celsius" else "Fahrenheit",
                                listOf(false to "Fahrenheit", true to "Celsius")) { celsius = it }
                            Button(onClick = {
                                if (secure.cloudEnrollment() == owner) {
                                    options.edit().putString("scope", owner.widgetScope()).putString("house", house)
                                        .putString("sensor", sensor).putString("metric", metric.name).putBoolean("celsius", celsius).apply()
                                    ClimateWidget.render(this@WidgetSettingsActivity, id)
                                    setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,id))
                                }
                                finish()
                            }) { Text("Save widget") }
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun <T> Choice(label: String, value: String, choices: List<Pair<T,String>>, select: (T) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Box {
            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) { Text(value) }
            DropdownMenu(expanded, { expanded = false }) {
                choices.forEach { (key, name) -> DropdownMenuItem(text = { Text(name) }, onClick = { select(key); expanded = false }) }
            }
        }
    }
}
