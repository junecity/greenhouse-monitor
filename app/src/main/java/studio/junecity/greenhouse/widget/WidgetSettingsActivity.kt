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
        val ready = owner != null && ClimateWidget.viewerReady(this, owner)
        val suffix = if (owner.isStandaloneClient()) "_standalone" else ""
        val prefs = if (ready) AppPreferencesStore(this, suffix).load() else null
        val rows = if (ready) CurrentSensorStore(this, suffix).load() else emptyList()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFFFFB347))) {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Greenhouse widget", style = MaterialTheme.typography.headlineSmall)
                        if (owner == null) {
                            Text("Sign in to Greenhouse Studio to add a widget.")
                            TextButton(onClick = { finish() }) { Text("Close") }
                        } else {
                            val savedScope = options.getString("scope", null)
                            val sameSource = owner.widgetScope() == savedScope
                            val ours = owner.ownsWidget(savedScope)
                            val houses = prefs?.greenhouses.orEmpty()
                            var house by rememberSaveable { mutableStateOf(houses.find { sameSource && it.id == options.getString("house", "") }?.id.orEmpty()) }
                            val selectedHouse = house.ifEmpty { prefs?.activeGreenhouse?.id.orEmpty() }
                            val sensors = rows.filter { prefs?.sensorHouse(it) == selectedHouse }
                            var sensor by rememberSaveable(house) { mutableStateOf(sensors.find { sameSource && it.id == options.getString("sensor", "") }?.id.orEmpty()) }
                            var metric by rememberSaveable { mutableStateOf(WidgetMetric.entries.find { ours && it.name == options.getString("metric","") } ?: WidgetMetric.Both) }
                            var celsius by rememberSaveable { mutableStateOf(ours && options.getBoolean("celsius", false)) }
                            if (!ready) Text("Your widget will show readings when the greenhouse server syncs.")
                            Choice("Greenhouse", houses.find { it.id == house }?.name ?: "Current greenhouse",
                                listOf("" to "Current greenhouse") + houses.map { it.id to it.name }) { house = it }
                            Choice("Reading", sensors.find { it.id == sensor }?.name ?: "Greenhouse average",
                                listOf("" to "Greenhouse average") + sensors.map { it.id to it.name }) { sensor = it }
                            Choice("Show", metric.title, WidgetMetric.entries.map { it to it.title }) { metric = it }
                            if (metric != WidgetMetric.Humidity) Choice("Temperature unit", if (celsius) "Celsius" else "Fahrenheit",
                                listOf(false to "Fahrenheit", true to "Celsius")) { celsius = it }
                            Button(onClick = {
                                val current = secure.cloudEnrollment()
                                if (current != null && current.ownsWidget(owner.widgetScope())) {
                                    options.edit().putString("scope", owner.widgetScope()).putString("house", if (sensor.isEmpty()) house else selectedHouse)
                                        .putString("sensor", sensor).putString("metric", metric.name).putBoolean("celsius", celsius).apply()
                                    ClimateWidget.refresh(this@WidgetSettingsActivity, id)
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
