package studio.junecity.greenhouse.widget

import studio.junecity.greenhouse.data.ClimateBucket
import studio.junecity.greenhouse.data.SpatialInterpolator
import studio.junecity.greenhouse.data.WIDGET_WINDOW_MS
import studio.junecity.greenhouse.model.CURRENT_READING_MAX_AGE_MS
import studio.junecity.greenhouse.model.GoveeSensor
import studio.junecity.greenhouse.model.GreenhouseConfig
import studio.junecity.greenhouse.model.Metric
import studio.junecity.greenhouse.model.NotificationPreferences
import studio.junecity.greenhouse.sensors.isLocalRadio
import studio.junecity.greenhouse.sensors.isShelly
import java.text.DateFormat
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs

internal const val WIDGET_CHART_GAP_MS = 10 * 60_000L
private const val HOUR_MS = 3_600_000L

internal enum class WidgetFeedStatus(val label: String) {
    Live("LIVE"), Delayed("DELAYED"), Offline("OFFLINE"), Loading("LOADING"),
    Empty("NO DATA"), Permission("PERMISSION"), Error("ERROR")
}

internal enum class OutdoorStatus(val description: String) {
    Current("Current"), Delayed("Weather delayed"), Unavailable("Weather unavailable")
}

internal data class GreenhouseWidgetUiState(
    val title: String = "Greenhouse Studio",
    val status: WidgetFeedStatus = WidgetFeedStatus.Loading,
    val temperatureF: Double? = null,
    val humidity: Double? = null,
    val measuredAt: Long? = null,
    val trend: List<ClimateBucket> = emptyList(),
    val slopeFPerHour: Double? = null,
    val outdoorF: Double? = null,
    val outdoorAt: Long? = null,
    val targetF: Double? = null,
    val dewPointF: Double? = null,
    val celsius: Boolean = false,
    val now: Long = 0,
    val statusMessage: String? = null,
    val targetStatus: String? = null,
    val warning: Boolean = false,
    val outdoorFreshness: OutdoorStatus = OutdoorStatus.Unavailable,
    val sensorId: String? = null,
    val nextStatusAt: Long? = null
) {
    fun degrees(f: Double?) = f?.let {
        whole(if (celsius) (it - 32) / 1.8 else it) + if (celsius) "°C" else "°F"
    } ?: "—"
    val temperatureText get() = degrees(temperatureF)
    val targetText get() = degrees(targetF)
    val humidityText get() = humidity?.let { "${whole(it)}%" } ?: "—"
    val outdoorStatus get() = outdoorFreshness.description
    val rateText: String get() {
        val slope = slopeFPerHour ?: return "Trend unavailable"
        if (abs(slope) < 0.3) return "Steady"
        val digits = NumberFormat.getNumberInstance().apply { minimumFractionDigits = 1; maximumFractionDigits = 1 }
        val amount = digits.format(abs(slope) / if (celsius) 1.8 else 1.0)
        return "${if (slope < 0) "↓" else "↑"} $amount${if (celsius) "°C" else "°F"}/hr"
    }
    val humidityRangeText: String get() = if (trend.size < 2) "Not enough history" else
        "${whole(trend.minOf { it.humidity })}–${whole(trend.maxOf { it.humidity })}% range"
    val updatedText get() = measuredAt?.let {
        "${if (status == WidgetFeedStatus.Live) "Updated" else "Last updated"} ${widgetDate(it, now)}"
    } ?: "No reading yet"
}

private fun whole(value: Double) = NumberFormat.getIntegerInstance().format(Math.round(value))

internal fun widgetDate(at: Long, now: Long, zone: ZoneId = ZoneId.systemDefault(),
                        locale: Locale = Locale.getDefault()): String {
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val sameDay = Instant.ofEpochMilli(at).atZone(zone).toLocalDate() == today
    val format = if (sameDay) DateFormat.getTimeInstance(DateFormat.SHORT, locale)
        else DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, locale)
    format.timeZone = TimeZone.getTimeZone(zone)
    return format.format(Date(at))
}

internal data class WidgetOutdoor(val fahrenheit: Double?, val measuredAt: Long?, val failed: Boolean = false)

internal data class CardSourceStatus(
    val blocked: Map<String, WidgetFeedStatus> = emptyMap(),
    val issue: WidgetFeedStatus? = null
)

internal fun cardSources(rows: List<GoveeSensor>, paired: Boolean, internet: Boolean, bluetoothAllowed: Boolean,
                         fetchFailed: Boolean): CardSourceStatus {
    val blocked = buildMap {
        for (row in rows) {
            val problem = if (!paired && row.isLocalRadio()) {
                WidgetFeedStatus.Permission.takeUnless { bluetoothAllowed }
            } else when {
                !internet -> WidgetFeedStatus.Offline
                fetchFailed -> WidgetFeedStatus.Error
                else -> null
            }
            if (problem != null) put(row.id, problem)
        }
    }
    return CardSourceStatus(blocked, WidgetFeedStatus.Error.takeIf { rows.isEmpty() && fetchFailed })
}

/** All ages are relative to actual persisted sample times. A refresh attempt is never a sample. */
internal fun climateCardState(
    title: String, rows: List<GoveeSensor>, history: List<ClimateBucket>, now: Long,
    cadenceMinutes: Int, celsius: Boolean, target: Double?, outdoor: WidgetOutdoor?,
    limits: NotificationPreferences, sources: CardSourceStatus = CardSourceStatus(), sensorId: String? = null,
    house: GreenhouseConfig = GreenhouseConfig()
): GreenhouseWidgetUiState {
    // BLU beacons can advertise observations several minutes apart. A slow radio in a
    // mixed collection must not make unrelated temperature polls look newer.
    fun freshFor(sensor: GoveeSensor) = 60_000L * (maxOf(if (sensor.isShelly()) 4 else 1, cadenceMinutes.coerceIn(1, 15)) + 1)
    fun offlineAfter(sensor: GoveeSensor) = maxOf(10 * 60_000L, 3 * freshFor(sensor))
    val observations = rows.filter { it.greenhouseId == house.id && it.updatedAtEpochMs in 1..now &&
        (it.temperatureF?.isFinite() == true || it.humidityPercent?.let { r -> r in 0.0..100.0 } == true) }
    val current = SpatialInterpolator.currentContributors(observations, house, now)
    val delayed = observations.filter { it.online && now - it.updatedAtEpochMs <= offlineAfter(it) }
    val source = current.ifEmpty { delayed.ifEmpty { observations } }
    // A missed poll changes the feed badge. The same recorded observations still form
    // the Dashboard average until each expires or its collector explicitly marks it offline.
    val timely = current.filter { now - it.updatedAtEpochMs <= freshFor(it) }
    val relevant = source.ifEmpty { rows }
    val blockedCount = relevant.count { it.id in sources.blocked }
    val status = sources.issue ?: when {
        rows.isEmpty() -> WidgetFeedStatus.Empty
        blockedCount == relevant.size -> sources.blocked.getValue(relevant.first().id)
        timely.isNotEmpty() && blockedCount == 0 -> WidgetFeedStatus.Live
        delayed.isNotEmpty() -> WidgetFeedStatus.Delayed
        observations.isEmpty() && rows.any { it.online } -> WidgetFeedStatus.Loading
        else -> WidgetFeedStatus.Offline
    }
    val usable = source.map { sensor -> sensor.copy(temperatureF = sensor.temperatureF?.takeIf(Double::isFinite),
        humidityPercent = sensor.humidityPercent?.takeIf { it in 0.0..100.0 }) }
    val temperature = SpatialInterpolator.averageOfReadings(usable, house, Metric.Temperature)
    val humidity = SpatialInterpolator.averageOfReadings(usable, house, Metric.Humidity)
    val dew = if (status == WidgetFeedStatus.Live && temperature != null && humidity != null && humidity > 0.0 &&
        source.all { it.temperatureF?.isFinite() == true && it.humidityPercent?.let { r -> r in 0.0..100.0 } == true }) {
        // Same Magnus equation used by the sensor detail and spatial heat maps.
        SpatialInterpolator.averageOfReadings(usable, house, Metric.DewPoint)?.takeIf(Double::isFinite)
    } else null
    val points = history.filter { it.at in (now - WIDGET_WINDOW_MS)..now && it.temperatureF.isFinite() &&
        it.humidity in 0.0..100.0 }.distinctBy { it.at }.sortedBy { it.at }
    val weatherTime = outdoor?.measuredAt?.takeIf { it in 1..now }
    val outdoorFreshness = when {
        outdoor?.fahrenheit?.isFinite() != true || weatherTime == null || now - weatherTime > HOUR_MS ->
            OutdoorStatus.Unavailable
        outdoor.failed || now - weatherTime > 30 * 60_000L -> OutdoorStatus.Delayed
        else -> OutdoorStatus.Current
    }
    val validTarget = target?.takeIf(Double::isFinite)
    val limit = if (status == WidgetFeedStatus.Live && limits.masterEnabled) when {
        limits.temperatureAlerts && temperature != null && temperature <= limits.lowTemperatureF -> "Low temperature"
        limits.temperatureAlerts && temperature != null && temperature >= limits.highTemperatureF -> "High temperature"
        limits.humidityAlerts && humidity != null && humidity <= limits.lowHumidity -> "Low humidity"
        limits.humidityAlerts && humidity != null && humidity >= limits.highHumidity -> "High humidity"
        else -> null
    } else null
    val targetStatus = limit ?: if (status == WidgetFeedStatus.Live && temperature != null && validTarget != null) when {
        abs(temperature - validTarget) <= 1.0 -> "At target"
        temperature > validTarget -> "Above target"
        else -> "Below target"
    } else null
    val message = when (status) {
        WidgetFeedStatus.Empty -> "No sensors selected"
        WidgetFeedStatus.Permission -> "Allow Bluetooth access"
        WidgetFeedStatus.Error -> "Readings could not refresh"
        WidgetFeedStatus.Loading -> "Waiting for readings"
        WidgetFeedStatus.Offline -> "Sensor feed offline"
        WidgetFeedStatus.Delayed -> "Readings delayed"
        WidgetFeedStatus.Live -> null
    }
    val nextStatusAt = buildList {
        observations.filter { it.online }.forEach {
            add(it.updatedAtEpochMs + CURRENT_READING_MAX_AGE_MS + 1)
            add(it.updatedAtEpochMs + freshFor(it) + 1)
            add(it.updatedAtEpochMs + offlineAfter(it) + 1)
        }
        weatherTime?.let { add(it + 30 * 60_000L + 1); add(it + HOUR_MS + 1) }
    }.filter { it > now }.minOrNull()
    return GreenhouseWidgetUiState(title, status, temperature, humidity, source.minOfOrNull { it.updatedAtEpochMs },
        points, if (status == WidgetFeedStatus.Live) recentRate(points, now, timely.maxOf { freshFor(it) }) else null,
        outdoor?.fahrenheit?.takeIf { outdoorFreshness != OutdoorStatus.Unavailable }, weatherTime,
        validTarget, dew, celsius, now, message, targetStatus, limit != null, outdoorFreshness, sensorId, nextStatusAt)
}

/** Least-squares fit of the last continuous half-hour to hour. Never bridge missing observations. */
private fun recentRate(history: List<ClimateBucket>, now: Long, freshFor: Long): Double? {
    if (history.isEmpty() || now - history.last().at > maxOf(freshFor, 5 * 60_000L)) return null
    val recent = history.takeLastWhile { it.at >= now - HOUR_MS }
    val lastGap = (1 until recent.size).lastOrNull { recent[it].at - recent[it - 1].at > WIDGET_CHART_GAP_MS } ?: 0
    val points = recent.drop(lastGap)
    if (points.size < 4 || points.last().at - points.first().at < HOUR_MS / 2) return null
    val times = points.map { (it.at - points.first().at).toDouble() / HOUR_MS }
    val center = times.average()
    val temp = points.map { it.temperatureF }.average()
    val variance = times.sumOf { (it - center) * (it - center) }
    return points.indices.sumOf { (times[it] - center) * (points[it].temperatureF - temp) } / variance
}
