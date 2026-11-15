package studio.junecity.greenhouse.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.RemoteViews
import studio.junecity.greenhouse.MainActivity
import studio.junecity.greenhouse.R
import studio.junecity.greenhouse.cloud.CloudDeviceRole
import studio.junecity.greenhouse.model.*
import studio.junecity.greenhouse.settings.*
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
internal data class WidgetReading(val text: String, val at: Long?, val stale: Boolean)

internal fun widgetReading(rows: List<GoveeSensor>, metric: WidgetMetric, celsius: Boolean, now: Long): WidgetReading {
    val fresh = rows.filter { it.updatedAtEpochMs in 1..now && it.isFresh(now) }
    if (fresh.isEmpty()) {
        val last = rows.map { it.updatedAtEpochMs }.filter { it in 1..now }.maxOrNull()
        return WidgetReading("—", last, last != null)
    }
    val fahrenheit = fresh.mapNotNull { it.temperatureF }.average()
    val temp = if (celsius) "${((fahrenheit - 32) / 1.8).roundToInt()}°C" else "${fahrenheit.roundToInt()}°F"
    val rh = "${fresh.mapNotNull { it.humidityPercent }.average().roundToInt()}% RH"
    return WidgetReading(when (metric) { WidgetMetric.Both -> "$temp  $rh"; WidgetMetric.Temperature -> temp; WidgetMetric.Humidity -> rh },
        fresh.minOf { it.updatedAtEpochMs }, false)
}

class ClimateWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = ids.forEach { render(context, it) }
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
        render(context, id)
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
            context.getSharedPreferences("widget_sync", 0).edit().putString("scope", owner.widgetScope()).apply()
            changed(context)
        }

        private val handler by lazy { Handler(Looper.getMainLooper()) }
        private var pending = false
        @Synchronized internal fun changed(context: Context) {
            if (pending) return
            pending = true
            val app = context.applicationContext
            handler.postDelayed({
                synchronized(this) { pending = false }
                val manager = AppWidgetManager.getInstance(app)
                manager.getAppWidgetIds(ComponentName(app, ClimateWidget::class.java)).forEach { render(app, it) }
            }, 500)
        }

        internal fun render(context: Context, id: Int) {
            val options = settings(context, id)
            val views = RemoteViews(context.packageName, R.layout.greenhouse_widget)
            val owner = SecureSettingsStore(context).cloudEnrollment()
            var title = "Greenhouse Studio"
            var reading = "—"
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
                        val metric = WidgetMetric.entries.find { it.name == options.getString("metric", "") } ?: WidgetMetric.Both
                        val value = widgetReading(rows, metric, options.getBoolean("celsius", false), System.currentTimeMillis())
                        reading = value.text
                        value.at?.let { at ->
                            val date = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(at))
                            status = if (value.stale) "Last reading · $date" else "As of $date"
                        }
                    } else status = "Choose a greenhouse in widget settings"
                } else status = "Waiting for server readings"
            }
            views.setTextViewText(R.id.widget_title, title)
            views.setTextViewText(R.id.widget_reading, reading)
            views.setTextViewText(R.id.widget_time, status)
            val open = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            views.setOnClickPendingIntent(R.id.widget_body, PendingIntent.getActivity(context, id, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            AppWidgetManager.getInstance(context).updateAppWidget(id, views)
        }
    }
}
