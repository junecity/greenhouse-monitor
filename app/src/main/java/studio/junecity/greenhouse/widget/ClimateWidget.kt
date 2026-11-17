package studio.junecity.greenhouse.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.RemoteViews
import studio.junecity.greenhouse.MainActivity
import studio.junecity.greenhouse.cloud.CloudDeviceRole
import studio.junecity.greenhouse.settings.*
import java.util.concurrent.Executors
import android.util.SizeF
import android.os.Build
import kotlin.math.roundToInt

private fun CloudEnrollment.widgetOwner() = "$tenantId|$deviceId|${role.name}"
internal fun CloudEnrollment.widgetScope() = "${widgetOwner()}|$greenhouseId|${isStandaloneClient()}"
// Existing widget configuration includes its original data source as the last two fields.
internal fun CloudEnrollment.ownsWidget(savedScope: String?): Boolean {
    return savedScope == widgetScope() || (tenantId.isNotBlank() && savedScope?.startsWith("${widgetOwner()}|") == true)
}

internal enum class WidgetMetric(val title: String) { Both("Temperature and humidity"), Temperature("Temperature"), Humidity("Humidity") }
class ClimateWidget : AppWidgetProvider() {
    private fun deliver(context: Context, ids: IntArray, coalesce: Boolean = false) {
        val pendingResult = goAsync()
        val draw = Runnable {
            if (coalesce) synchronized(Companion) { pending = false }
            try { ids.forEach { render(context, it) } }
            finally { pendingResult?.finish() }
        }
        if (coalesce) handler.postDelayed({ worker.execute(draw) }, 250) else worker.execute(draw)
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        deliver(context, ids)
        WidgetSyncWorker.enqueue(context)
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) =
        deliver(context, intArrayOf(id))

    override fun onDeleted(context: Context, ids: IntArray) {
        ids.forEach {
            scheduleFreshnessCheck(context, it, null)
            context.deleteSharedPreferences("climate_widget_$it")
        }
    }
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            val manager = AppWidgetManager.getInstance(context)
            val pins = manager.getAppWidgetIds(ComponentName(context, ClimateWidget::class.java))
            if (pins.isNotEmpty()) onUpdate(context, manager, pins)
            return
        }
        if (intent.action == READINGS_CHANGED) {
            val ids = AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, ClimateWidget::class.java))
            deliver(context, ids, coalesce = true)
            return
        }
        if (intent.action == CHECK_AGE) {
            val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            if (AppWidgetManager.getInstance(context).getAppWidgetInfo(id)?.provider == ComponentName(context, ClimateWidget::class.java))
                deliver(context, intArrayOf(id))
            return
        }
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
        WidgetSyncWorker.enqueue(context)
    }

    companion object {
        private const val READINGS_CHANGED = "studio.junecity.greenhouse.widget.READINGS_CHANGED"
        private const val CHECK_AGE = "studio.junecity.greenhouse.widget.CHECK_AGE"

        // A sleeping phone need not wake to recolor the launcher. Android may batch this
        // local redraw. Keep the real reading time visible even while delivery is deferred.
        private fun scheduleFreshnessCheck(context: Context, id: Int, next: Long?) {
            val manager = context.getSystemService(AlarmManager::class.java)
            val intent = Intent(context, ClimateWidget::class.java).setAction(CHECK_AGE)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            val job = PendingIntent.getBroadcast(context, id, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            if (next == null) manager.cancel(job) else manager.set(AlarmManager.RTC, next, job)
        }

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
        internal fun canDisplay(owner: CloudEnrollment, options: SharedPreferences): Boolean {
            val scope = options.getString("scope", null)
            return owner.ownsWidget(scope) && (scope == owner.widgetScope() ||
                options.getString("house", "").isNullOrBlank() && options.getString("sensor", "").isNullOrBlank())
        }

        internal fun viewerReady(context: Context, owner: CloudEnrollment): Boolean =
            owner.role != CloudDeviceRole.ViewerClient || owner.isStandaloneClient() ||
                context.getSharedPreferences("widget_sync", 0).getString("scope", "") == owner.widgetScope()

        internal fun viewerSynced(context: Context, owner: CloudEnrollment, upload: String? = null) {
            val sync = context.getSharedPreferences("widget_sync", 0).edit()
                .putString("scope", owner.widgetScope()).putLong("updatedAt", System.currentTimeMillis())
            if (upload != null) sync.putString("upload", upload)
            sync.apply()
            changed(context)
            WidgetWeatherRefresh.enqueue(context, owner)
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
            val component = ComponentName(context, ClimateWidget::class.java)
            if (AppWidgetManager.getInstance(context).getAppWidgetIds(component).isEmpty()) return
            // Hand the update to Android before a short-lived worker finishes. The receiver
            // keeps its broadcast alive while coalescing writes and publishing the RemoteViews.
            // This private, user-visible redraw should not sit behind the background broadcast queue.
            context.sendBroadcast(Intent(READINGS_CHANGED).setComponent(component).addFlags(Intent.FLAG_RECEIVER_FOREGROUND))
            pending = true
        }

        internal fun render(context: Context, id: Int) {
            val options = settings(context, id)
            val owner = SecureSettingsStore(context).cloudEnrollment()
            val savedScope = options.getString("scope", null)
            val data = if (owner != null && canDisplay(owner, options)) {
                val sameSource = savedScope == owner.widgetScope()
                ClimateCardRepository(context).load(
                    owner = owner,
                    houseId = options.getString("house", null)?.takeIf { sameSource && it.isNotBlank() },
                    sensorId = options.getString("sensor", null)?.takeIf { sameSource && it.isNotBlank() },
                    celsius = options.takeIf { it.contains("celsius") }?.getBoolean("celsius", false)
                )
            } else GreenhouseWidgetUiState(title = "Greenhouse Studio", status = WidgetFeedStatus.Empty,
                statusMessage = if (owner?.ownsWidget(savedScope) == true) "Switch to the configured greenhouse" else "Open Greenhouse Studio to set up")
            scheduleFreshnessCheck(context, id, data.nextStatusAt)
            val metric = WidgetMetric.entries.find { it.name == options.getString("metric", "") } ?: WidgetMetric.Both
            val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            val open = PendingIntent.getActivity(context, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val manager = AppWidgetManager.getInstance(context)
            val bounds = manager.getAppWidgetOptions(id)
            fun view(width: Int, height: Int) = climateRemoteViews(context, width.coerceIn(100, 2000), height.coerceIn(76, 1200), data, metric, open)
            if (Build.VERSION.SDK_INT >= 31) {
                @Suppress("DEPRECATION")
                val sizes = bounds.getParcelableArrayList<SizeF>(AppWidgetManager.OPTION_APPWIDGET_SIZES)
                    .orEmpty().filter { it.width.isFinite() && it.height.isFinite() && it.width > 0 && it.height > 0 }.distinct()
                if (sizes.isNotEmpty()) {
                    // Keep chart bitmap memory bounded on launchers that advertise many sizes.
                    // Responsive candidates still use the actual slot at display time.
                    val font = context.resources.configuration.fontScale.coerceAtLeast(1f)
                    val candidates = if (sizes.size <= 4) sizes else listOf(
                        SizeF(160f, 96f), SizeF(300f * font, 120f * font),
                        SizeF(386f * font, 156f * font), SizeF(340f, 280f * font), SizeF(620f, 220f * font)
                    )
                    val layouts = candidates.associateWith { view(it.width.roundToInt(), it.height.roundToInt()) }
                    manager.updateAppWidget(id, if (layouts.size == 1) layouts.values.single() else RemoteViews(layouts))
                    return
                }
            }
            val minWidth = bounds.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 360)
            val maxHeight = bounds.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 150)
            val maxWidth = bounds.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, minWidth)
            val minHeight = bounds.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, maxHeight)
            val portrait = view(minWidth, maxHeight)
            val views = if (minWidth == maxWidth && minHeight == maxHeight) portrait
                else RemoteViews(view(maxWidth, minHeight), portrait)
            manager.updateAppWidget(id, views)
        }
    }
}
