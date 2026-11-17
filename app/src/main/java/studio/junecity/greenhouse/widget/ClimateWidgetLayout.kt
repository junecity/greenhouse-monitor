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
    // Reserve room for the two text rows and the footer before allowing a chart.
    val textScale = font.coerceAtLeast(1f)
    return when {
        width >= 620 && height >= 86 + 110 * textScale -> LauncherCard.Wide
        width >= 340 && height >= 98 + 152 * textScale -> LauncherCard.Standard
        else -> LauncherCard.Compact
    }
}

internal fun climateRemoteViews(
    context: Context, width: Int, height: Int, data: GreenhouseWidgetUiState, metric: WidgetMetric, open: PendingIntent
): RemoteViews {
    val font = context.resources.configuration.fontScale
    val shape = launcherCard(width, height, font)
    val compact = shape == LauncherCard.Compact
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

    val padding = when { tiny -> 6; short -> 8; compact -> 12; else -> 16 }
    views.setViewPadding(R.id.widget_body, dp(padding), dp(padding), dp(padding), dp(padding))
    text(R.id.widget_title, data.title)
    text(R.id.widget_status, "●  ${data.status.label}")
    val statusColor = when (data.status) {
        WidgetFeedStatus.Live -> R.color.climate_healthy
        WidgetFeedStatus.Delayed -> R.color.climate_warning
        WidgetFeedStatus.Error, WidgetFeedStatus.Permission -> R.color.climate_critical
        else -> R.color.climate_secondary
    }
    views.setTextColor(R.id.widget_status, context.getColor(statusColor))
    visible(R.id.widget_house_icon, !tiny)
    size(R.id.widget_title, when { tiny -> 12f; short -> 14f; compact -> 16f; else -> 20f })
    size(R.id.widget_status, if (tiny) 9f else 11f)

    visible(R.id.widget_temperature_column, metric != WidgetMetric.Humidity)
    visible(R.id.widget_humidity_column, metric != WidgetMetric.Temperature)
    visible(R.id.widget_divider, metric == WidgetMetric.Both)
    val valueSize = when {
        tiny -> 21f
        short || (compact && width < 240 * font) -> 27f
        compact -> 34f
        shape == LauncherCard.Standard && height < 340 * font -> 32f
        shape == LauncherCard.Wide && height < 238 * font -> 32f
        else -> 40f
    }
    val tilePadding = if (tiny) 5 else if (short) 7 else if (compact) 9 else 12
    val verticalPad = if (tiny) 1 else if (short) 3 else 6
    for (id in listOf(R.id.widget_temperature_column, R.id.widget_humidity_column)) {
        views.setViewPadding(id, dp(tilePadding), dp(verticalPad), dp(tilePadding), dp(verticalPad))
    }
    size(R.id.widget_temperature, valueSize)
    size(R.id.widget_humidity, valueSize)
    size(R.id.widget_temperature_label, if (tiny) 10f else 12f)
    size(R.id.widget_humidity_label, if (tiny) 10f else 12f)
    text(R.id.widget_temperature_label, if (tiny) "Temp." else "Temperature")
    text(R.id.widget_temperature, data.temperatureText)
    text(R.id.widget_humidity, data.humidityText)
    text(R.id.widget_temperature_range, data.rateText)
    text(R.id.widget_humidity_range, data.humidityRangeText)
    visible(R.id.widget_temperature_range, !short)
    visible(R.id.widget_humidity_range, !short)

    val health = if (data.warning) data.targetStatus else if (compact && data.targetF != null) "Target ${data.targetText}" else data.targetStatus
    text(R.id.widget_health, health.orEmpty())
    val showHealth = health != null && data.measuredAt != null && (!tiny || data.warning)
    visible(R.id.widget_health, showHealth)
    visible(R.id.widget_health_icon, showHealth && !compact && data.targetStatus != null)
    if (showHealth) {
        views.setImageViewResource(R.id.widget_health_icon, if (data.warning) R.drawable.ic_climate_alert_triangle else R.drawable.ic_climate_check_circle)
        views.setInt(R.id.widget_health_icon, "setColorFilter", context.getColor(if (data.warning) R.color.climate_critical else R.color.climate_healthy))
    }
    val message = data.statusMessage
    text(R.id.widget_time, if (data.measuredAt == null && message != null) message else data.updatedText)
    views.setContentDescription(R.id.widget_status, listOfNotNull(data.status.label, message).joinToString(". "))
    if (!compact) {
        val outsideLabel = if (data.outdoorFreshness == OutdoorStatus.Delayed) {
            if ((width - padding * 2) / (3 * font) >= 115) "Outside · delayed" else "Outside · old"
        } else "Outside"
        text(R.id.widget_outside_label, outsideLabel)
        text(R.id.widget_outside, data.degrees(data.outdoorF))
        views.setContentDescription(R.id.widget_outside, "Outside: ${data.degrees(data.outdoorF)}. ${data.outdoorStatus}")
        text(R.id.widget_target, data.targetText)
        text(R.id.widget_dew_point, data.degrees(data.dewPointF))
        val humidity = metric == WidgetMetric.Humidity
        val chartTitle = if (humidity) "Humidity" else "Temperature"
        val color = context.getColor(if (humidity) R.color.climate_humidity else R.color.climate_temperature)
        fun value(point: studio.junecity.greenhouse.data.ClimateBucket) = if (humidity) point.humidity else point.temperatureF
        fun label(v: Double) = if (humidity) "${v.roundToInt()}%" else data.degrees(v)
        val trend = data.trend
        val plot = trend.size >= 2
        text(R.id.widget_period, "$chartTitle · 6h")
        visible(R.id.widget_chart_empty, !plot)
        visible(R.id.widget_temperature_plot, plot)
        if (plot) {
            val endpoints = "${label(value(trend.first()))} → ${label(value(trend.last()))}"
            text(R.id.widget_chart_range, endpoints)
            views.setTextColor(R.id.widget_chart_range, color)
            views.setContentDescription(R.id.widget_temperature_plot, "$chartTitle over six hours. $endpoints. Range ${label(trend.minOf(::value))} to ${label(trend.maxOf(::value))}.")
            val wide = shape == LauncherCard.Wide
            val innerWidth = width - 2 * padding
            val chartWidth = if (wide) (.9f / 1.9f * (innerWidth - 16)) else innerWidth.toFloat()
            val otherHeight = 2 * padding + 43f + 63f * font
            val chartHeight = ((height - otherHeight) * (if (wide) 1f else .85f / 1.85f) - 18f * font).coerceAtLeast(18f)
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
