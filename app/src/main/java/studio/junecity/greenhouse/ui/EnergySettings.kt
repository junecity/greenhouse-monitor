package studio.junecity.greenhouse.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import studio.junecity.greenhouse.data.*
import studio.junecity.greenhouse.model.GreenhouseUiState
import studio.junecity.greenhouse.settings.SecureSettingsStore
import java.time.YearMonth
import java.time.format.DateTimeFormatter

@Composable
internal fun EnergySettings(state: GreenhouseUiState) {
    val context = LocalContext.current
    val house = state.preferences.activeGreenhouse
    val zip = state.preferences.zipCode
    val secure = remember(context) { SecureSettingsStore(context) }
    val owner = secure.cloudEnrollment()
    val store = remember(owner, house.id) { HeatingStore(context, owner, house.id) }
    val training = heatingPoints(state, store)
    val learned = remember(training) { learnedTemperature(training) }
    val nights = savedEnergyNights(store, zip)
    val currentMonth = YearMonth.now(state.weather.zone)
    val months = (nights.map { YearMonth.from(it.date) } + currentMonth).distinct().sortedDescending()
    var selected by rememberSaveable(store.namespace, zip) { mutableStateOf(currentMonth.toString()) }
    val month = YearMonth.parse(selected)
    val index = months.indexOf(month)
    val rows = nights.filter { YearMonth.from(it.date) == month }
    val now = System.currentTimeMillis()
    val completed = rows.filter { it.finished }
    val upcoming = rows.filter { !it.finished && it.sunrise > now }
    val unresolved = rows.size - completed.size - upcoming.size
    var details by rememberSaveable(selected, store.namespace, zip) { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(house.name, color = DashboardTokens.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        EnergySection("Monthly costs") {
            Text(month.format(DateTimeFormatter.ofPattern("MMMM yyyy")), color = DashboardTokens.White,
                fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            if (months.size > 1) Row {
                TextButton(enabled = index in 0 until months.lastIndex, onClick = { selected = months[index + 1].toString() }) { Text("Earlier") }
                TextButton(enabled = index > 0, onClick = { selected = months[index - 1].toString() }) { Text("Later") }
            }
            if (rows.isEmpty()) EnergyNote("No saved nights this month. Estimates begin after the first steady hour of indoor readings on a cooler night.")
            if (completed.isNotEmpty()) {
                Text(money(completed.sumOf { it.estimate }), color = DashboardTokens.Amber, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                EnergyNote("Estimated heating for ${completed.size} completed ${if (completed.size == 1) "night" else "nights"}")
            }
            if (upcoming.isNotEmpty()) EnergyNote("Upcoming forecast · ${money(upcoming.sumOf { it.estimate })} across ${upcoming.size} nights")
            if (unresolved > 0) EnergyNote("$unresolved past nights have a saved forecast only.")
            if (rows.isNotEmpty()) {
                TextButton(onClick = { details = !details }) { Text(if (details) "Hide nights" else "View nightly costs", color = DashboardTokens.Amber) }
                if (details) rows.forEach { row ->
                    HorizontalDivider(color = DashboardTokens.Divider)
                    Text(row.date.format(DateTimeFormatter.ofPattern("EEE, MMM d")), color = DashboardTokens.White, fontWeight = FontWeight.Bold)
                    row.first?.let { EnergyNote("First forecast · ${money(it)} · ${row.firstFuel}") }
                    val label = if (row.finished) "Historical estimate" else "Latest forecast"
                    EnergyNote("$label · ${money(row.estimate)} · ${row.fuel}")
                    if (row.finished) EnergyNote("${"%.1f".format(row.stationHours)} hours of station observations")
                }
            }
        }
        EnergySection("Training data") {
            EnergyNote(learned?.let { "Learned overnight temperature · ${"%.1f".format(it.fahrenheit)}°F from ${it.nights} qualifying nights" }
                ?: "Learning your greenhouse’s overnight temperature")
            EnergyNote("${training.size} quarter-hour samples across ${training.distinctBy { it.date }.size} nights")
            if (training.isNotEmpty()) EnergyNote("Sample dates · ${training.first().date} – ${training.last().date}")
        }
        EnergySection("How it works") {
            EnergyNote("After sunset, Greenhouse Studio pairs indoor sensor readings with outdoor weather. It looks for at least an hour of steady indoor temperatures while it is cooler outside.")
            EnergyNote("Each qualifying night has one vote. Up to 30 recent qualifying nights help establish a typical temperature. The phone keeps 90 days of training samples.")
            EnergyNote("Hourly outdoor forecasts, greenhouse dimensions and the regional fuel price produce a heating estimate. As station observations arrive, the saved forecast can be compared with the revised cost.")
            EnergyNote("Each night counts toward the month it starts. Missing nights are excluded. Costs remain estimates until compared with measured fuel use.")
            EnergyNote("Training and cost records are saved on this phone. Cost records stay available after the raw readings expire.")
        }
    }
}

@Composable internal fun savedEnergyNights(store: HeatingStore, zip: String): List<SavedEnergyNight> {
    val context = LocalContext.current
    val revision by EnergyArchive.revision.collectAsState()
    return key(store.namespace, zip) {
        produceState(emptyList<SavedEnergyNight>(), revision) {
            value = withContext(Dispatchers.IO) { EnergyArchive(context).use { it.nights(store.namespace, zip) } }
        }.value
    }
}

@Composable private fun EnergySection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().background(DashboardTokens.PanelRaised, RoundedCornerShape(14.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, color = DashboardTokens.White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
        content()
    }
}

@Composable private fun EnergyNote(text: String) {
    Text(text, color = DashboardTokens.Muted, fontSize = 15.sp, lineHeight = 20.sp)
}
