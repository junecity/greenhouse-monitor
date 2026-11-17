package studio.junecity.greenhouse.widget

import android.app.PendingIntent
import android.content.Context
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import studio.junecity.greenhouse.R
import kotlin.math.roundToInt

internal enum class LauncherCard { Compact, Standard, Wide }

internal fun launcherCard(width: Int, height: Int, font: Float): LauncherCard {
    val scale = font.coerceAtLeast(1f)
    // A tall or large-type pin can give each section a full row. A two-row phone
    // widget has room for the reference's three adjacent cards at normal type.
    return when {
        width >= 270 && height >= 240 * scale && height > width * .75f -> LauncherCard.Standard
        width >= 300 * scale && height >= 120 * scale -> LauncherCard.Wide
        width >= 300 && height >= 215 * scale -> LauncherCard.Standard
        else -> LauncherCard.Compact
    }
}

internal fun climateRemoteViews(
    context: Context, width: Int, height: Int, data: GreenhouseWidgetUiState, metric: WidgetMetric, open: PendingIntent
): RemoteViews {
    val font = context.resources.configuration.fontScale
    val shape = launcherCard(width, height, font)
    val compact = shape == LauncherCard.Compact
    val strip = shape == LauncherCard.Wide
    val stripScale = minOf(width / 386f, height / 156f).coerceIn(.78f, 1.55f)
    val tightLargeType = strip && font > 1.1f && height < 145 * font
    val short = compact && height < 132 * font
    val tiny = compact && (height < 112 * font || width < 205 * font)
    val layout = when (shape) {
        LauncherCard.Compact -> R.layout.greenhouse_widget
        LauncherCard.Standard -> R.layout.greenhouse_widget_standard
        LauncherCard.Wide -> R.layout.greenhouse_widget_wide
    }
    val views = RemoteViews(context.packageName, layout)
    val density = context.resources.displayMetrics.density
    fun dp(value: Int) = (value * density).roundToInt()
    fun text(id: Int, value: String) = views.setTextViewText(id, value)
    fun visible(id: Int, show: Boolean) = views.setViewVisibility(id, if (show) View.VISIBLE else View.GONE)
    fun size(id: Int, value: Float) = views.setTextViewTextSize(id, TypedValue.COMPLEX_UNIT_SP, value)

    // A launcher can reapply these actions to the same views when an alarm clears.
    fun surface(id: Int, normal: Int, alarm: Int) =
        views.setInt(id, "setBackgroundResource", if (data.alarmActive) alarm else normal)
    surface(R.id.widget_body, R.drawable.widget_surface, R.drawable.widget_alarm_surface)
    surface(R.id.widget_temperature_column, R.drawable.widget_metric_tile, R.drawable.widget_alarm_tile)
    surface(R.id.widget_humidity_column, R.drawable.widget_humidity_tile, R.drawable.widget_alarm_tile)
    if (!compact) surface(R.id.widget_chart_panel, R.drawable.widget_chart_tile, R.drawable.widget_alarm_tile)

    val padding = when {
        strip -> (12 * stripScale).roundToInt()
        tiny -> 6
        short -> 8
        compact -> 12
        else -> 16
    }
    val verticalPadding = when {
        tightLargeType -> 3
        strip -> (7 * stripScale).roundToInt()
        else -> padding
    }
    views.setViewPadding(R.id.widget_body, dp(padding), dp(verticalPadding), dp(padding), dp(verticalPadding))
    text(R.id.widget_title, data.title)
    val statusText = when {
        !data.alarmActive -> "●  ${data.status.label}"
        tiny || data.status == WidgetFeedStatus.Live -> context.getString(R.string.widget_alarm_label)
        else -> context.getString(R.string.widget_alarm_feed_status, data.status.label)
    }
    text(R.id.widget_status, statusText)
    val statusColor = if (data.alarmActive) R.color.climate_primary else when (data.status) {
        WidgetFeedStatus.Live -> R.color.widget_live
        WidgetFeedStatus.Delayed -> R.color.climate_warning
        WidgetFeedStatus.Error, WidgetFeedStatus.Permission -> R.color.climate_critical
        else -> R.color.climate_secondary
    }
    views.setTextColor(R.id.widget_status, context.getColor(statusColor))
    views.setImageViewResource(R.id.widget_house_icon, if (data.alarmActive) R.drawable.ic_climate_alert_triangle else R.drawable.ic_widget_house)
    visible(R.id.widget_house_icon, !tiny)
    size(R.id.widget_title, when { strip -> 14f * stripScale; tiny -> 12f; short -> 14f; compact -> 16f; else -> 20f })
    size(R.id.widget_status, if (strip) 10f * stripScale else if (tiny) 9f else 11f)

    visible(R.id.widget_temperature_column, metric != WidgetMetric.Humidity)
    visible(R.id.widget_humidity_column, metric != WidgetMetric.Temperature)
    visible(R.id.widget_divider, metric == WidgetMetric.Both)
    val valueSize = when {
        tightLargeType -> 22f * stripScale
        strip -> 24f * stripScale
        tiny -> 21f
        short || (compact && width < 240 * font) -> 27f
        compact -> 34f
        shape == LauncherCard.Standard && height < 340 * font -> 32f
        else -> 40f
    }
    val tilePadding = if (strip) (7 * stripScale).roundToInt() else if (tiny) 5 else if (short) 7 else if (compact) 9 else 12
    val verticalPad = when {
        tightLargeType -> 0
        strip -> (3 * stripScale).roundToInt()
        tiny -> 1
        short -> 3
        else -> 6
    }
    for (id in listOf(R.id.widget_temperature_column, R.id.widget_humidity_column)) {
        views.setViewPadding(id, dp(tilePadding), dp(verticalPad), dp(tilePadding), dp(verticalPad))
    }
    size(R.id.widget_temperature, valueSize)
    size(R.id.widget_humidity, valueSize)
    val labelSize = if (strip) (9.8f * stripScale).coerceAtLeast(8.4f) else if (tiny) 10f else 12f
    size(R.id.widget_temperature_label, labelSize)
    size(R.id.widget_humidity_label, labelSize)
    val abbreviateTemp = tiny || (strip && metric == WidgetMetric.Both && font > 1.1f && width < 345 * font)
    text(R.id.widget_temperature_label, if (abbreviateTemp) "Temp." else "Temperature")
    views.setContentDescription(R.id.widget_temperature_label, "Temperature")
    text(R.id.widget_temperature, data.temperatureText)
    text(R.id.widget_humidity, data.humidityText)
    val shortRate = if (strip && data.slopeFPerHour == null) "No trend" else data.rateText
    val shortRange = when {
        strip && data.trend.size < 2 -> "No history"
        strip && width < 352 * font && metric == WidgetMetric.Both -> data.humidityRangeText.removeSuffix(" range")
        else -> data.humidityRangeText
    }
    text(R.id.widget_temperature_range, shortRate)
    text(R.id.widget_humidity_range, shortRange)
    views.setContentDescription(R.id.widget_temperature_range, data.rateText)
    views.setContentDescription(R.id.widget_humidity_range, data.humidityRangeText)
    visible(R.id.widget_temperature_range, !short)
    visible(R.id.widget_humidity_range, !short)

    val health = if (data.warning) data.targetStatus else if (compact && data.targetF != null) "Target ${data.targetText}" else data.targetStatus
    text(R.id.widget_health, health.orEmpty())
    val showHealth = health != null && data.measuredAt != null && (!tiny || data.warning)
    visible(R.id.widget_health, showHealth)
    visible(R.id.widget_health_icon, showHealth && !compact && data.targetStatus != null)
    if (showHealth) {
        views.setImageViewResource(R.id.widget_health_icon, if (data.warning) R.drawable.ic_climate_alert_triangle else R.drawable.ic_climate_check_circle)
        views.setInt(R.id.widget_health_icon, "setColorFilter", context.getColor(if (data.warning) R.color.climate_critical else R.color.widget_live))
    }
    if (strip) {
        visible(R.id.widget_health_group, showHealth)
        visible(R.id.widget_health_separator, showHealth)
        size(R.id.widget_temperature_range, 9f * stripScale)
        size(R.id.widget_humidity_range, 9f * stripScale)
        size(R.id.widget_health, 9f * stripScale)
        size(R.id.widget_time, 8f * stripScale)
        for (id in listOf(R.id.widget_outside_label, R.id.widget_target_label, R.id.widget_dew_point_label)) {
            size(id, 7.8f * stripScale)
        }
        for (id in listOf(R.id.widget_outside, R.id.widget_target, R.id.widget_dew_point)) {
            size(id, 15f * stripScale)
        }
    }
    val message = data.statusMessage
    text(R.id.widget_time, if (data.measuredAt == null && message != null) message else data.updatedText)
    val alarmDescription = if (data.alarmActive) context.getString(R.string.widget_alarm_description) else null
    views.setContentDescription(R.id.widget_status, listOfNotNull(alarmDescription, data.status.label, message).joinToString(". "))
    if (!compact) {
        val outsideLabel = if (data.outdoorFreshness == OutdoorStatus.Delayed) {
            if ((width - padding * 2) / ((if (strip) 5.2f else 3f) * font) >= 115) "Outside · delayed" else "Outside · old"
        } else "Outside"
        text(R.id.widget_outside_label, outsideLabel)
        text(R.id.widget_outside, data.degrees(data.outdoorF))
        views.setContentDescription(R.id.widget_outside, "Outside: ${data.degrees(data.outdoorF)}. ${data.outdoorStatus}")
        text(R.id.widget_target, data.targetText)
        text(R.id.widget_dew_point, data.degrees(data.dewPointF))
        val chartPadding = if (strip) (7 * stripScale).roundToInt() else 8
        views.setViewPadding(R.id.widget_chart_panel, dp(chartPadding), dp(chartPadding), dp(chartPadding), dp(chartPadding))
        if (strip) {
            size(R.id.widget_period, labelSize)
            size(R.id.widget_chart_range, labelSize)
            size(R.id.widget_chart_empty, labelSize)
        }
        val humidity = metric == WidgetMetric.Humidity
        val chartTitle = if (humidity) "Humidity" else "Temperature"
        val color = context.getColor(if (humidity) R.color.widget_cyan else R.color.widget_amber)
        fun value(point: studio.junecity.greenhouse.data.ClimateBucket) = if (humidity) point.humidity else point.temperatureF
        fun label(v: Double) = if (humidity) "${v.roundToInt()}%" else data.degrees(v)
        val trend = data.trend
        val endpoints = data.trendEndpoints { label(value(it)) }
        val plot = endpoints != null
        text(R.id.widget_period, "$chartTitle · 6h")
        visible(R.id.widget_chart_empty, !plot)
        visible(R.id.widget_temperature_plot, plot)
        if (endpoints != null) {
            val displayedEndpoints = if (strip && !humidity) endpoints.replace("°F", "°").replace("°C", "°") else endpoints
            text(R.id.widget_chart_range, displayedEndpoints)
            views.setTextColor(R.id.widget_chart_range, color)
            views.setContentDescription(R.id.widget_temperature_plot, "$chartTitle over six hours. $endpoints. Range ${label(trend.minOf(::value))} to ${label(trend.maxOf(::value))}.")
            val innerWidth = width - 2 * padding
            val chartWidth = (if (strip) (innerWidth - 6) * .53f else innerWidth.toFloat()) - 2 * chartPadding
            val chartHeight = if (strip) {
                val header = maxOf(19f, 17f * stripScale * font)
                val footer = 7f + 28f * stripScale * font
                height - 2 * verticalPadding - header - footer - 14f - 2 * chartPadding - labelSize * 1.2f * font - 4f
            } else {
                val otherHeight = 2 * padding + 43f + 63f * font
                (height - otherHeight) * .85f / 1.85f - 18f * font - 2 * chartPadding
            }.coerceAtLeast(18f)
            // fitCenter accommodates launchers whose allocated grid differs from their hints.
            // The bounded pixel budget also applies on high-density tablets.
            val scale = minOf(density, 720f / chartWidth, 270f / chartHeight)
            views.setImageViewBitmap(R.id.widget_temperature_plot, widgetSparkline(
                trend, data.now, (chartWidth * scale).roundToInt().coerceAtLeast(8),
                (chartHeight * scale).roundToInt().coerceAtLeast(8), color, ::value
            ))
        } else {
            text(R.id.widget_chart_range, "")
            views.setImageViewResource(R.id.widget_temperature_plot, 0)
            views.setContentDescription(R.id.widget_temperature_plot, null)
        }
    }
    views.setOnClickPendingIntent(R.id.widget_body, open)
    return views
}
