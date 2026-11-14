package studio.junecity.greenhouse.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import studio.junecity.greenhouse.data.*
import studio.junecity.greenhouse.model.*
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal fun money(value: Double?) = value?.let { NumberFormat.getCurrencyInstance(Locale.US).format(it) } ?: "—"
private fun priceLabel(value: Double) = NumberFormat.getCurrencyInstance(Locale.US).apply {
    maximumFractionDigits = 6
}.format(value)
private fun number(value: Double) = String.format(Locale.US, "%.1f", value).removeSuffix(".0")
private fun clock(ms: Long, zone: ZoneId) = Instant.ofEpochMilli(ms).atZone(zone).format(DateTimeFormatter.ofPattern("h:mm a"))

@Composable
internal fun heatingPoints(state: GreenhouseUiState, store: HeatingStore): List<ThermalPoint> {
    val house = state.preferences.activeGreenhouse
    val zip = state.preferences.zipCode
    val weather = state.weather
    val validWeather = weather.zipCode == zip && weather.updatedAtEpochMs > 0
    val now = System.currentTimeMillis()
    val ids = remember(state.sensors, state.preferences) {
        state.sensors.filter { state.preferences.sensorHouse(it) == house.id }.map { it.id }.toSet()
    }
    var points by remember(store, zip, ids) { mutableStateOf(emptyList<ThermalPoint>()) }
    LaunchedEffect(store, weather, state.history, ids, zip) {
        points = withContext(Dispatchers.Default) {
            val cached = withContext(Dispatchers.IO) { store.history(zip) }.filter {
                it.time >= now - 30L * 24 * HEATING_HOUR && it.sensors.all(ids::contains)
            }
            val observed = if (validWeather) NightHeating.observations(weather, state.history, ids, now) else emptyList()
            (cached + observed).associateBy { it.time }.values.sortedBy { it.time }
        }
    }
    return points
}

@Composable
internal fun energyPrice(state: GreenhouseUiState, unit: EnergyUnit): FuelPrice? {
    val context = androidx.compose.ui.platform.LocalContext.current
    val provider = remember(context) { FuelPrices(context) }
    val area = state.weather.takeIf { it.zipCode == state.preferences.zipCode }?.stateCode
    var quote by remember(provider, unit, area) { mutableStateOf(provider.cached(unit, area)) }
    LaunchedEffect(provider, unit, area, state.weather.updatedAtEpochMs) {
        quote = provider.refresh(unit, area)
    }
    return quote
}

@Composable
internal fun NightHeatingContent(state: GreenhouseUiState, store: HeatingStore, refresh: () -> Unit, points: List<ThermalPoint> = heatingPoints(state, store)) {
    val house = state.preferences.activeGreenhouse
    val zip = state.preferences.zipCode
    val weather = state.weather
    var settings by remember(store) { mutableStateOf(store.settings()) }
    var editing by remember(store) { mutableStateOf(false) }
    var detail by remember { mutableStateOf(false) }
    val now = System.currentTimeMillis()
    val learned = remember(points) { learnedTemperature(points) }
    val nights = remember(weather, learned, now / 60_000L) { learned?.let { NightHeating.forecast(weather, it.fahrenheit, now) }.orEmpty() }
    var index by remember(store, nights.firstOrNull()?.date) { mutableIntStateOf(0) }
    val night = nights.getOrNull(index)
    val coefficient = remember(house, settings.unit) { heatingCoefficient(house, settings.unit) }
    val quote = energyPrice(state, settings.unit)
    val rate = quote?.dollars
    val costPerDegree = rate?.times(coefficient.nominal)
    val validWeather = weather.zipCode == zip && weather.updatedAtEpochMs > 0
    val behavior = remember(points, learned) { learned?.let { nightBehavior(points, it.fahrenheit) } }
    val analog = remember(points, learned, night?.outdoorMean) {
        night?.outdoorMean?.let { nightBehavior(points, requireNotNull(learned).fahrenheit, it) }
    }
    MaterialTheme(colorScheme = darkColorScheme(primary = DashboardTokens.Amber, onPrimary = DashboardTokens.Background,
        surface = DashboardTokens.Panel, onSurface = DashboardTokens.White, onSurfaceVariant = DashboardTokens.Muted,
        secondaryContainer = DashboardTokens.Amber, onSecondaryContainer = DashboardTokens.Background)) {
        Surface(modifier = Modifier.fillMaxSize(),
            shape = RoundedCornerShape(18.dp), border = BorderStroke(1.dp, DashboardTokens.PanelEdge),
            color = DashboardTokens.Panel, contentColor = DashboardTokens.White) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Nighttime Heating Forecast", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        Text("${house.name}  ·  ${if (validWeather) weather.location else "ZIP $zip"}",
                            color = DashboardTokens.Muted, fontSize = 14.sp)
                    }
                }
                HorizontalDivider(color = DashboardTokens.Divider)
                Column(Modifier.verticalScroll(rememberScrollState()).fillMaxWidth().padding(vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (editing) {
                        Column {
                            Text("Heating fuel", fontWeight = FontWeight.Bold)
                            for (pair in EnergyUnit.entries.chunked(2)) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    pair.forEach { unit -> FilterChip(selected = unit == settings.unit, onClick = {
                                        settings = HeatingSettings(unit)
                                        store.save(settings)
                                        editing = false
                                    }, label = { Text(unit.fuel) }) }
                                }
                            }
                        }
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(learned?.let { "${number(it.fahrenheit)}°F learned from ${it.nights} " + if (it.nights == 1) "night" else "nights" }
                                    ?: "Learning overnight temperature", fontWeight = FontWeight.SemiBold)
                                Text(quote?.let { "${settings.unit.fuel}  ·  ${priceLabel(it.dollars)} / ${settings.unit.label}  ·  ${if (it.area == "US") "U.S. average" else it.area + " average"}  ·  EIA ${it.period}" }
                                    ?: "Published price unavailable", color = DashboardTokens.Muted, fontSize = 14.sp)
                            }
                            TextButton(onClick = { editing = true }) { Text("Change fuel", color = DashboardTokens.Amber) }
                        }
                        if (validWeather && weather.solarDays.isNotEmpty() && night == null) {
                            Text("The first estimate appears after an hour of steady indoor readings on a cooler night.")
                        } else if (validWeather && weather.solarDays.isNotEmpty() && night != null) {
                            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                nights.forEachIndexed { i, n ->
                                    FilterChip(selected = index == i, onClick = { index = i }, label = {
                                        Column(Modifier.padding(vertical = 3.dp)) {
                                            Text(when (i) { 0 -> "Tonight"; 1 -> if (n.date == java.time.LocalDate.now(weather.zone).plusDays(1)) "Tomorrow" else "Next night"; else -> n.date.format(DateTimeFormatter.ofPattern("EEE d")) })
                                            Text(money(n.degreeHours?.let { d -> costPerDegree?.times(d) }), fontWeight = FontWeight.Bold)
                                        }
                                    })
                                }
                            }
                            val degrees = night.degreeHours
                            val timeLabel = night.hours.firstOrNull()?.let {
                                "${clock(it.start, weather.zone)} – ${clock(night.hours.last().end, weather.zone)}"
                            }.orEmpty()
                            Text("${night.date.format(DateTimeFormatter.ofPattern("EEE, MMM d"))}  ·  $timeLabel",
                                color = DashboardTokens.Muted)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                HeatingStat("Full night · est.", money(degrees?.let { costPerDegree?.times(it) }), Modifier.weight(1f))
                                HeatingStat("First 8 hours · est.", money(night.eightDegreeHours?.let { costPerDegree?.times(it) }), Modifier.weight(1f))
                                HeatingStat("Outdoor mean", night.outdoorMean?.let { "${number(it)}°F" } ?: "—", Modifier.weight(1f))
                            }
                            val measuredHours = night.hours.filter { it.observed }.sumOf { it.hours }
                            if (measuredHours > 0) Text("${number(measuredHours)} hours updated with station weather · ${weather.stationId.orEmpty()}",
                                color = DashboardTokens.Blue)
                            if (degrees != null) {
                                Text("${number(degrees)} °F·h" + (rate?.let {
                                    "   ·   Estimated range ${money(degrees * it * coefficient.low)} – ${money(degrees * it * coefficient.high)}"
                                } ?: ""), color = DashboardTokens.Muted)
                                if (night.eightDegreeHours == null) Text("Less than eight hours of darkness.", color = DashboardTokens.Muted)
                            } else Text("Incomplete hourly or solar data for this night.", color = DashboardTokens.Muted)
                            if (night.hours.isNotEmpty()) HeatingChart(night, costPerDegree, weather.zone)
                            if (analog != null && analog.nights >= 2 && night.outdoorMean != null) {
                                Text("Similar nights averaged ${number(night.outdoorMean!! + analog.indoorDelta)}°F indoors at this forecast outdoor temperature. Based on ${analog.nights} nights within 5°F.",
                                    color = DashboardTokens.Blue)
                            }
                            Text("Weather ${if (weather.stale) "last available" else "updated"} ${Instant.ofEpochMilli(weather.updatedAtEpochMs).atZone(weather.zone).format(DateTimeFormatter.ofPattern("MMM d, h:mm a"))}",
                                color = DashboardTokens.Muted, fontSize = 13.sp)
                        } else {
                            Text("Hourly weather and local sunset times are needed for this forecast.")
                            TextButton(onClick = refresh) { Text("Refresh weather", color = DashboardTokens.Amber) }
                        }
                        HorizontalDivider(color = DashboardTokens.Divider)
                        if (validWeather && learned != null && nights.isNotEmpty()) {
                            val previous = NightHeating.night(weather, learned.fahrenheit, nights.first().date.minusDays(1), now)
                            val cost = previous.degreeHours?.let { costPerDegree?.times(it) }
                            if (cost != null) {
                                val observed = previous.hours.filter { it.observed }.sumOf { it.hours }
                                Text("Last completed night · ${money(cost)} estimated", fontWeight = FontWeight.Bold)
                                Text("${previous.date} · ${number(observed)} of ${number(previous.hours.sumOf { it.hours })} hours from station observations; remaining hours use local weather-model data.",
                                    color = DashboardTokens.Muted)
                            }
                        }
                        Text("Nighttime behavior", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        if (behavior == null) Text("Waiting for an hour of paired indoor and outdoor readings after sunset.",
                            color = DashboardTokens.Muted)
                        else {
                            Text("${behavior.nights} sampled ${if (behavior.nights == 1) "night" else "nights"}  ·  ${number(behavior.hours)} hours observed  ·  ${number(behavior.nearTarget*100)}% within 2°F of target")
                            Text("Cooling ${behavior.cooling?.let(::number) ?: "—"} °F/h   ·   Recovery ${behavior.recovery?.let(::number) ?: "—"} °F/h   ·   Average nightly swing ${number(behavior.swing)}°F", color = DashboardTokens.Muted)
                        }
                        TextButton(onClick = { detail = !detail }) { Text(if (detail) "Hide assumptions" else "How the estimate works", color = DashboardTokens.Amber) }
                        if (detail) {
                            Text("Each forecast hour adds heat demand only when outdoors is below the learned overnight temperature. The eight-hour window starts at sunset. The full-night estimate includes the entire night, even if it is already underway.")
                            Text("Prices are published residential averages. Wood is priced by heat content because cords vary by species and moisture.")
                            Text("The model uses your floor plan and ${house.heightFeet?.let { "${number(it)} ft height" } ?: "an assumed 8 ft height"}. It approximates wall height at 80% of peak, roof area at 110% of floor area, plastic cover loss at 0.7–1.2 Btu/h·ft²·°F, and 0.5–2 air changes/hour.")
                            Text("Energy coefficient: ${number(coefficient.nominal * 1000)} ${settings.unit.label} per 1,000 °F·h. Assumes " +
                                (if (settings.unit == EnergyUnit.Kwh) "resistance-equivalent electricity."
                                else "${number(settings.unit.delivery * 100)}% useful heat from ${settings.unit.fuel.lowercase()} (range ${number(settings.unit.lowEfficiency * 100)}–${number(settings.unit.highEfficiency * 100)}%).") +
                                " The range illustrates these assumptions. Wind, construction and heating efficiency can change actual bills. Sensor temperatures describe retention and cannot measure energy use.")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HeatingStat(label: String, value: String, modifier: Modifier) {
    Column(modifier.background(DashboardTokens.PanelRaised, RoundedCornerShape(12.dp)).padding(10.dp)) {
        Text(label, color = DashboardTokens.Muted, fontSize = 13.sp)
        Text(value, color = DashboardTokens.Amber, fontSize = 22.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun HeatingChart(night: HeatingNight, costPerDegree: Double?, zone: ZoneId) {
    val hours = night.hours
    val peak = hours.mapNotNull { it.degreeHours?.let { d -> costPerDegree?.times(d) } }.maxOrNull() ?: 0.0
    val maxDelta = maxOf(1.0, hours.mapNotNull { it.delta }.maxOrNull() ?: 0.0)
    val minDelta = minOf(0.0, hours.mapNotNull { it.delta }.minOrNull() ?: 0.0)
    Text("Hourly outlook", fontSize = 18.sp, fontWeight = FontWeight.Bold)
    if (costPerDegree != null) Text("Cost per interval  ·  $0 – ${money(peak)}", color = DashboardTokens.Amber)
    Text("Target minus outdoor  ·  ${number(minDelta)} to ${number(maxDelta)}°F", color = DashboardTokens.Blue)
    val description = hours.joinToString(". ") { h ->
        "${clock(h.start, zone)}, outdoor ${h.outdoor?.let(::number) ?: "unavailable"} degrees, estimated cost ${money(h.degreeHours?.let { costPerDegree?.times(it) })}"
    }
    Canvas(Modifier.fillMaxWidth().height(80.dp).background(DashboardTokens.PanelRaised, RoundedCornerShape(8.dp))
        .semantics { contentDescription = description }) {
        val start = hours.first().start
        val duration = (hours.last().end-start).toDouble()
        fun x(t: Long) = ((t-start)/duration*size.width).toFloat()
        var prev: Offset? = null
        for (h in hours) {
            val left = x(h.start)
            val right = x(h.end)
            val cost = h.degreeHours?.let { costPerDegree?.times(it) }
            if (cost != null && peak > 0) {
                val height = (cost/peak * (size.height-6.dp.toPx())).toFloat()
                drawRect(DashboardTokens.Amber.copy(alpha = .64f), Offset(left+1, size.height-height),
                    Size((right-left-2).coerceAtLeast(0f), height))
            }
            val next = h.delta?.let { Offset((left+right)/2,
                3.dp.toPx() + ((maxDelta-it)/(maxDelta-minDelta)*(size.height-6.dp.toPx())).toFloat()) }
            if (next != null) {
                prev?.let { drawLine(DashboardTokens.Blue, it, next, 2.dp.toPx()) }
                drawCircle(DashboardTokens.Blue, 2.dp.toPx(), next)
            }
            prev = next
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        listOf(hours.first().start, hours[hours.size/2].start, hours.last().end).forEach {
            Text(clock(it, zone), color = DashboardTokens.Muted, fontSize = 13.sp)
        }
    }
}
