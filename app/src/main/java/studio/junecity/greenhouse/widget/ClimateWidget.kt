package studio.junecity.greenhouse.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.content.res.Configuration
import android.os.Handler
import android.os.Looper
import android.widget.RemoteViews
import studio.junecity.greenhouse.MainActivity
import studio.junecity.greenhouse.R
import studio.junecity.greenhouse.cloud.CloudDeviceRole
import studio.junecity.greenhouse.model.*
import studio.junecity.greenhouse.settings.*
import studio.junecity.greenhouse.data.*
import java.util.concurrent.Executors
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt

private fun CloudEnrollment.widgetOwner() = "$tenantId|$deviceId|${role.name}"
internal fun CloudEnrollment.widgetScope() = "${widgetOwner()}|$greenhouseId|${isStandaloneClient()}"
// Existing widget configuration includes its original data source as the last two fields.
internal fun CloudEnrollment.ownsWidget(savedScope: String?): Boolean {
    return savedScope == widgetScope() || (tenantId.isNotBlank() && savedScope?.startsWith("${widgetOwner()}|") == true)
}

internal enum class WidgetMetric(val title: String) { Both("Temperature and humidity"), Temperature("Temperature"), Humidity("Humidity") }
internal data class WidgetReading(val temperature: String, val humidity: String, val at: Long?, val stale: Boolean)

private fun displayDegrees(fahrenheit: Double, celsius: Boolean): String =
    if (celsius) "${((fahrenheit - 32) / 1.8).roundToInt()}°C" else "${fahrenheit.roundToInt()}°F"

internal fun widgetReading(rows: List<GoveeSensor>, celsius: Boolean, now: Long): WidgetReading {
    val fresh = rows.filter { it.updatedAtEpochMs in 1..now && it.isFresh(now) }
    if (fresh.isEmpty()) {
        val last = rows.map { it.updatedAtEpochMs }.filter { it in 1..now }.maxOrNull()
        return WidgetReading("—", "—", last, last != null)
    }
    val temp = displayDegrees(fresh.mapNotNull { it.temperatureF }.average(), celsius)
    val rh = "${fresh.mapNotNull { it.humidityPercent }.average().roundToInt()}%"
    return WidgetReading(temp, rh, fresh.minOf { it.updatedAtEpochMs }, false)
}

class ClimateWidget : AppWidgetProvider() {
    private fun deliver(context: Context, ids: IntArray) {
        val pendingResult = goAsync()
        worker.execute {
            try { ids.forEach { render(context, it) } }
            finally { pendingResult?.finish() }
        }
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        deliver(context, ids)
        WidgetSyncWorker.enqueue(context)
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) =
        deliver(context, intArrayOf(id))

    override fun onDeleted(context: Context, ids: IntArray) {
        ids.forEach { context.deleteSharedPreferences("climate_widget_$it") }
    }
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != PINNED) {
            super.onReceive(context, intent)
            return
        }
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        val owner = SecureSettingsStore(context).cloudEnrollment() ?: return
        if (!owner.ownsWidget(intent.data?.getQueryParameter("scope"))) return
        val manager = AppWidgetManager.getInstance(context)
        if (manager.getAppWidgetInfo(id)?.provider != ComponentName(context, ClimateWidget::class.java)) return
        val options = settings(context, id)
        if (!options.contains("scope")) options.edit().putString("scope", owner.widgetScope()).apply()
        deliver(context, intArrayOf(id))
    }

    companion object {
        private const val PINNED = "studio.junecity.greenhouse.widget.PINNED"

        internal fun requestPin(context: Context): Boolean {
            val owner = SecureSettingsStore(context).cloudEnrollment() ?: return false
            val manager = AppWidgetManager.getInstance(context)
            if (!manager.isRequestPinAppWidgetSupported) return false
            val component = ComponentName(context, ClimateWidget::class.java)
            val data = android.net.Uri.Builder().scheme("greenhouse-widget").authority("pin")
                .appendPath(java.util.UUID.randomUUID().toString()).appendQueryParameter("scope", owner.widgetScope()).build()
            val intent = Intent(PINNED, data).setComponent(component)
            // The launcher supplies the allocated widget ID in the success callback.
            val mutable = if (android.os.Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            val success = PendingIntent.getBroadcast(context, 0, intent, mutable or PendingIntent.FLAG_ONE_SHOT)
            return manager.requestPinAppWidget(component, null, success)
        }

        internal fun settings(context: Context, id: Int) = context.getSharedPreferences("climate_widget_$id", Context.MODE_PRIVATE)
        internal fun viewerReady(context: Context, owner: CloudEnrollment): Boolean =
            owner.role != CloudDeviceRole.ViewerClient || owner.isStandaloneClient() ||
                context.getSharedPreferences("widget_sync", 0).getString("scope", "") == owner.widgetScope()

        internal fun viewerSynced(context: Context, owner: CloudEnrollment) {
            context.getSharedPreferences("widget_sync", 0).edit().putString("scope", owner.widgetScope())
                .putLong("updatedAt", System.currentTimeMillis()).apply()
            changed(context)
        }

        private val handler by lazy { Handler(Looper.getMainLooper()) }
        private val worker = Executors.newSingleThreadExecutor()
        internal fun refresh(context: Context, id: Int) {
            val app = context.applicationContext
            worker.execute { render(app, id) }
        }

        private var pending = false
        @Synchronized internal fun changed(context: Context) {
            if (pending) return
            pending = true
            val app = context.applicationContext
            handler.postDelayed({
                synchronized(this) { pending = false }
                val manager = AppWidgetManager.getInstance(app)
                manager.getAppWidgetIds(ComponentName(app, ClimateWidget::class.java)).forEach { refresh(app, it) }
            }, 500)
        }

        internal fun render(context: Context, id: Int) {
            val options = settings(context, id)
            val views = RemoteViews(context.packageName, R.layout.greenhouse_widget)
            val owner = SecureSettingsStore(context).cloudEnrollment()
            var title = "Greenhouse Studio"
            val now = System.currentTimeMillis()
            val celsius = options.getBoolean("celsius", false)
            val metric = WidgetMetric.entries.find { it.name == options.getString("metric", "") } ?: WidgetMetric.Both
            var reading = WidgetReading("—", "—", null, false)
            var trend = emptyList<ClimateBucket>()
            var status = "Open Greenhouse Studio to set up"
            val savedScope = options.getString("scope", null)
            if (owner != null && owner.ownsWidget(savedScope)) {
                status = "Waiting for greenhouse readings"
                if (viewerReady(context, owner)) {
                    val suffix = if (owner.isStandaloneClient()) "_standalone" else ""
                    val prefs = AppPreferencesStore(context, suffix).load()
                    val sameSource = owner.widgetScope() == savedScope
                    val houseId = if (sameSource) options.getString("house", "") else ""
                    val house = if (houseId.isNullOrEmpty()) prefs.activeGreenhouse else prefs.greenhouses.find { it.id == houseId }
                    if (house != null) {
                        val sensor = if (sameSource) options.getString("sensor", "") else ""
                        val rows = CurrentSensorStore(context, suffix).load().filter { prefs.sensorHouse(it) == house.id && (sensor.isNullOrEmpty() || it.id == sensor) }
                        title = if (sensor.isNullOrEmpty()) house.name else rows.firstOrNull()?.name ?: house.name
                        reading = widgetReading(rows, celsius, now)
                        val paired = owner.role == CloudDeviceRole.ViewerClient && !owner.isStandaloneClient()
                        val archive = if (paired) viewerHistorySuffix(owner) else suffix
                        if (rows.isNotEmpty()) {
                            trend = HistoryStore(context, archive).use { db -> db.widgetTrend(rows.map { it.id }.toSet(), now) }
                        }
                        reading.at?.let { at ->
                            val sameDay = android.text.format.DateUtils.isToday(at)
                            val date = if (sameDay) DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(at))
                                else DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(at))
                            status = if (reading.stale) "Last reading · $date" else "Updated $date"
                        }
                    } else status = "Choose a greenhouse in widget settings"
                } else status = "Waiting for server readings"
            }
            views.setTextViewText(R.id.widget_title, title)
            val manager = AppWidgetManager.getInstance(context)
            val size = manager.getAppWidgetOptions(id)
            val landscape = context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            val widthDp = size.getInt(if (landscape) AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH else AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 260)
            val heightDp = size.getInt(if (landscape) AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT else AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 150)
            val compact = heightDp < 128
            val pad = ((if (compact) 6 else 12) * context.resources.displayMetrics.density).roundToInt()
            val sidePad = ((if (widthDp < 200) 7 else 13) * context.resources.displayMetrics.density).roundToInt()
            views.setViewPadding(R.id.widget_body, sidePad, pad, sidePad, pad)
            views.setTextViewText(R.id.widget_temperature_label, if (widthDp < 210) "Temp." else "Temperature")
            fun visible(view: Int, show: Boolean) = views.setViewVisibility(view, if (show) View.VISIBLE else View.GONE)
            visible(R.id.widget_temperature_column, metric != WidgetMetric.Humidity)
            visible(R.id.widget_humidity_column, metric != WidgetMetric.Temperature)
            visible(R.id.widget_divider, metric == WidgetMetric.Both)
            visible(R.id.widget_period, widthDp >= 200 && trend.isNotEmpty())
            // Draw a high-density, fixed-aspect trace. Launcher size hints can describe a
            // different orientation or grid than the actual host; fitCenter preserves its shape.
            val chartWidth = (widthDp.coerceIn(160, 640) * context.resources.displayMetrics.density * .60f)
                .roundToInt().coerceIn(480, 1080)
            val chartHeight = chartWidth / 4
            fun metricPanel(valueId: Int, chartId: Int, rangeId: Int, text: String, color: Int,
                            format: (Double) -> String, measure: (ClimateBucket) -> Double) {
                views.setTextViewText(valueId, text)
                val hasData = trend.isNotEmpty()
                visible(chartId, hasData)
                visible(rangeId, hasData && heightDp >= 148)
                if (hasData) {
                    val range = "${format(trend.minOf(measure))} – ${format(trend.maxOf(measure))}"
                    views.setTextViewText(rangeId, "Range  $range")
                    views.setContentDescription(chartId, "Past 6 hours, $range")
                    views.setImageViewBitmap(chartId, widgetSparkline(trend, now, chartWidth, chartHeight, color, measure))
                } else {
                    views.setImageViewResource(chartId, 0)
                    views.setTextViewText(rangeId, "")
                    views.setContentDescription(chartId, null)
                }
            }
            if (metric != WidgetMetric.Humidity) metricPanel(R.id.widget_temperature, R.id.widget_temperature_plot,
                R.id.widget_temperature_range, reading.temperature, 0xffffb75e.toInt(), { displayDegrees(it, celsius) }) { it.temperatureF }
            if (metric != WidgetMetric.Temperature) metricPanel(R.id.widget_humidity, R.id.widget_humidity_plot,
                R.id.widget_humidity_range, reading.humidity, 0xff63caff.toInt(), { "${it.roundToInt()}%" }) { it.humidity }
            if (trend.isEmpty() && reading.at != null && !reading.stale) status += " · Gathering history"
            views.setTextViewText(R.id.widget_time, status)
            val open = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            views.setOnClickPendingIntent(R.id.widget_body, PendingIntent.getActivity(context, id, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            AppWidgetManager.getInstance(context).updateAppWidget(id, views)
        }
    }
}
