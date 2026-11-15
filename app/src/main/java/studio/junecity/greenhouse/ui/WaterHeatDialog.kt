package studio.junecity.greenhouse.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import studio.junecity.greenhouse.data.*
import studio.junecity.greenhouse.model.GreenhouseUiState
import studio.junecity.greenhouse.model.sensorHouse
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
internal fun WaterHeatDialog(state: GreenhouseUiState, store: HeatingStore, learned: LearnedTemperature?,
    night: HeatingNight?, fuel: EnergyUnit, coefficient: HeatCoefficient, price: Double?, close: () -> Unit) {
    val house = state.preferences.activeGreenhouse
    var volume by remember(store) { mutableStateOf(store.waterGallons().toString().removeSuffix(".0")) }
    val gallons = volume.toDoubleOrNull()?.takeIf { it in 0.1..1_000_000.0 }
    var assumptions by remember { mutableStateOf(false) }
    val weather = state.weather
    val sensors = remember(state.sensors, state.preferences) {
        state.sensors.filter { state.preferences.sensorHouse(it) == house.id }
    }
    val ids = remember(sensors) { sensors.map { it.id }.toSet() }
    var selected by remember(store) { mutableStateOf(store.waterSensorId()) }
    var choosing by remember { mutableStateOf(false) }
    val now = System.currentTimeMillis()
    val validWeather = weather.zipCode == state.preferences.zipCode && weather.updatedAtEpochMs > 0
    var estimate by remember(store, gallons, selected, weather, state.history, price, ids, learned, fuel, night, coefficient) {
        mutableStateOf<WaterHeatEstimate?>(null)
    }
    LaunchedEffect(store, gallons, selected, weather, state.history, price, ids, learned, fuel, night, coefficient, now / 60_000L) {
        if (validWeather && gallons != null && night != null && learned != null) {
            estimate = withContext(Dispatchers.Default) {
                WaterHeat.estimate(state.history, ids, weather, night, now, learned.fahrenheit, gallons, fuel, coefficient, price, selected)
            }
        }
    }
    val result = estimate
    fun energy(btu: Double?) = btu?.let { String.format(Locale.US, "%.2f kWh", it / EnergyUnit.Kwh.btu) } ?: "—"
    val setup: @Composable () -> Unit = {
        OutlinedTextField(volume, { volume = it }, singleLine = true, isError = gallons == null,
            label = { Text("Total water (US gallons)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth())
        Box {
            OutlinedButton(onClick = { choosing = true }) {
                Text("Barrel location · " + (if (selected == null) "Greenhouse average" else sensors.find { it.id == selected }?.name ?: "Choose sensor"))
            }
            DropdownMenu(expanded = choosing, onDismissRequest = { choosing = false }) {
                DropdownMenuItem(text = { Text("Greenhouse average") }, onClick = { selected = null; choosing = false })
                sensors.forEach { sensor -> DropdownMenuItem(text = { Text(sensor.name) },
                    onClick = { selected = sensor.id; choosing = false }) }
            }
        }
        Button(enabled = gallons != null, onClick = {
            gallons?.let { store.saveWaterSetup(it, selected) }
            close()
        }) { Text("Save setup") }
    }
    val estimates: @Composable () -> Unit = {
        when {
            gallons == null -> Text("Enter 0.1 to 1,000,000 US gallons.")
            !validWeather -> Text("Waiting for local weather and sunrise and sunset times.")
            learned == null -> Text("Learning your greenhouse’s overnight temperature from sensor history.")
            night == null || result == null -> Text("Waiting for daylight sensor readings and matching outdoor weather for this night.")
            else -> {
                Text("Potential savings tonight  " + money(result.savings), color = DashboardTokens.Amber,
                    fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
                HeatRow("Extra heat escaping by day", energy(result.excessBtu))
                HeatRow("Stored at sunset", energy(result.sunsetBtu))
                HeatRow("Useful heat overnight", energy(result.avoidedBtu))
                HeatRow("Heating without added storage", money(result.estimatedBill?.let { it + requireNotNull(result.savings) }))
                HeatRow("Heating with added storage", money(result.estimatedBill))
                Text(String.format(Locale.US, "%.1f daylight hours sampled · %s", result.sampledHours, fuel.fuel),
                    color = DashboardTokens.Muted)
                if (weather.stale) Text("Using last available weather.", color = DashboardTokens.Muted)
                if (result.avoidedBtu == null) Text("Waiting for a complete overnight forecast.")
                else if (price == null) Text("Waiting for a fuel price to estimate dollars.")
            }
        }
    }
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.padding(horizontal = 12.dp, vertical = 8.dp).widthIn(max = 920.dp).fillMaxWidth().fillMaxHeight(.95f),
            shape = RoundedCornerShape(20.dp), border = BorderStroke(1.dp, DashboardTokens.PanelEdge),
            color = DashboardTokens.Panel, contentColor = DashboardTokens.White) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    DashboardIconView(DashboardIcon.Droplet, DashboardTokens.Blue, Modifier.size(24.dp), contentDescription = null)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Daytime water storage", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
                        Text(listOfNotNull(house.name, night?.date?.format(DateTimeFormatter.ofPattern("EEE, MMM d"))).joinToString("  ·  "),
                            color = DashboardTokens.Muted)
                    }
                    TextButton(onClick = close) { Text("Close") }
                }
                HorizontalDivider(color = DashboardTokens.Divider)
                BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                    val wide = maxWidth >= 515.dp * LocalDensity.current.fontScale
                    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (wide) {
                            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                                Column(Modifier.weight(.9f), verticalArrangement = Arrangement.spacedBy(8.dp)) { setup() }
                                Column(Modifier.weight(1.3f), verticalArrangement = Arrangement.spacedBy(6.dp)) { estimates() }
                            }
                        } else {
                            estimates()
                            setup()
                        }
                        TextButton(onClick = { assumptions = !assumptions }) { Text(if (assumptions) "Hide assumptions" else "How this works") }
                        if (assumptions) Text("This scenario starts the water at the learned overnight temperature at sunrise. Daylight readings estimate extra heat escaping through the greenhouse cover and air leakage above that temperature. The barrel uses the air temperature near your selected sensor, or the greenhouse average. Missing hours add no heat and the water gradually cools toward the target.\n\nThe model assumes similar exposed barrels with a 12-hour thermal response and no direct solar gain into the water. Size, shade and airflow change actual results. Savings count only heat released when heating is needed, using the selected fuel price. These are whole-night estimates. Air sensors cannot measure actual water temperature or verify savings.",
                            color = DashboardTokens.Muted)
                    }
                }
            }
        }
    }
}

@Composable
private fun HeatRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, Modifier.weight(1f))
        Text(value, fontWeight = FontWeight.SemiBold)
    }
}
