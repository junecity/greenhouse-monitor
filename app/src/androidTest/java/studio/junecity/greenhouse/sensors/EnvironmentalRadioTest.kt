package studio.junecity.greenhouse.sensors

import android.appwidget.*
import studio.junecity.greenhouse.R
import android.content.ComponentName
import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import studio.junecity.greenhouse.settings.*
import studio.junecity.greenhouse.model.GoveeSensor
import studio.junecity.greenhouse.widget.*
import studio.junecity.greenhouse.cloud.CloudDeviceRole

class EnvironmentalRadioTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun discoverReadAndReconnect() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("essRadio") == "true")
        runBlocking {
            val sensor = withTimeout(25_000) { EnvironmentalBluetooth.discover(context).first { it.name == "Test ESS" } }
            repeat(2) {
                val (temp,rh) = EnvironmentalBluetooth.read(context,sensor.address)
                assertEquals(27.5,temp,.001)
                assertEquals(48.25,rh,.001)
            }
            val owner = SecureSettingsStore(context).cloudEnrollment()
            val suffix = "_ess_collection_test"
            val store = CurrentSensorStore(context,suffix)
            val id = environmentalId(sensor.address)!!
            val history = studio.junecity.greenhouse.data.HistoryStore(context,suffix)
            store.save(listOf(GoveeSensor(id,"BLE ESS","North")))
            val collector = launch(Dispatchers.IO) { collectEnvironmental(context, owner, suffix) }
            try {
                withTimeout(20_000) { while (history.recent(1).none { it.sensorId == id }) delay(100) }
                assertEquals(48.25,store.load().single().humidityPercent!!,.001)
            } finally {
                collector.cancelAndJoin()
                history.clear()
                history.close()
                store.clear()
            }
        }
    }

    @Test fun genericMembershipSurvivesOtherProvidersAndRemoval() {
        val store = CurrentSensorStore(context,"_ess_qa")
        try {
            val sensor = GoveeSensor("ess:AA:BB:CC:DD:EE:FF","BLE ESS","North")
            store.enroll(sensor)
            store.rename(sensor.id,"Bench")
            store.replaceGovee(listOf(GoveeSensor("g1","H5179","South")))
            assertEquals("Bench",store.load().first { it.id==sensor.id }.name)
            store.remove(sensor.id)
            assertFalse(store.load().any { it.id==sensor.id })
            store.addBack(sensor.id)
            assertTrue(store.load().any { it.id==sensor.id })
        } finally { store.clear() }
    }

    @Test fun launcherConfiguresWidgetAndIsolatesEnrollment() {
        val secure = SecureSettingsStore(context)
        assumeTrue("Widget host fixture requires an emulator", android.os.Build.MODEL.contains("sdk", ignoreCase = true))
        val previous = secure.cloudEnrollment()
        val owner = CloudEnrollment("widget-test","",CloudDeviceRole.ViewerClient,localSensors=true,tenantId="widget-test")
        val sensors = CurrentSensorStore(context,"_standalone")
        val house = AppPreferencesStore(context,"_standalone").load().activeGreenhouse.id
        val sample = GoveeSensor("widget:display-fixture","BLE ESS","Widget north bench",68.0,51.0,true,System.currentTimeMillis(),house)
        val host = AppWidgetHost(context, 4271)
        val id = host.allocateAppWidgetId()
        val manager = AppWidgetManager.getInstance(context)
        val component = ComponentName(context, ClimateWidget::class.java)
        try {
            secure.saveCloudEnrollment(owner)
            sensors.enroll(sample)
            instrumentation.uiAutomation.adoptShellPermissionIdentity(android.Manifest.permission.BIND_APPWIDGET)
            try { assertTrue(manager.bindAppWidgetIdIfAllowed(id,component)) }
            finally { instrumentation.uiAutomation.dropShellPermissionIdentity() }
            val launch = Intent(context,WidgetSettingsActivity::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,id)
            ActivityScenario.launch<WidgetSettingsActivity>(launch).use {
                compose.onNodeWithText("Greenhouse average").performClick()
                compose.onNodeWithText(sample.name).performClick()
                compose.onNodeWithText("Fahrenheit").performClick()
                compose.onNodeWithText("Celsius").performClick()
                compose.onNodeWithText("Save widget").performClick()
                instrumentation.waitForIdleSync()
            }
            assertEquals(owner.widgetScope(),ClimateWidget.settings(context,id).getString("scope",""))
            compose.waitUntil(5_000) { widgetText(4271, id, R.id.widget_temperature) == "20°C" }
            assertEquals("51%", widgetText(4271, id, R.id.widget_humidity))
            secure.clearCloudEnrollment()
            ClimateWidget.render(context,id)
            assertEquals("Open Greenhouse Studio to set up", widgetText(4271, id, R.id.widget_time))
        } finally {
            sensors.forget(sample)
            host.deleteHost()
            if (previous != null) secure.saveCloudEnrollment(previous) else secure.clearCloudEnrollment()
        }
    }

    @Test fun widgetChartsTrackPairedReadingsAndFitLauncherSizes() = runBlocking {
        assumeTrue(android.os.Build.MODEL.contains("sdk", ignoreCase = true))
        val secure = SecureSettingsStore(context)
        val original = secure.cloudEnrollment()
        val owner = CloudEnrollment("trend-widget", "widget-server", CloudDeviceRole.ViewerClient, tenantId = "trend-test")
        val prefs = AppPreferencesStore(context).load()
        val sensorStore = CurrentSensorStore(context)
        val saved = sensorStore.load()
        val sample = GoveeSensor("temp-probe", "H5179", "Bench", 63.0, 79.0, true,
            System.currentTimeMillis(), prefs.activeGreenhouse.id)
        val now = sample.updatedAtEpochMs
        val host = AppWidgetHost(context, 4273)
        val id = host.allocateAppWidgetId()
        val manager = AppWidgetManager.getInstance(context)
        val suffix = studio.junecity.greenhouse.data.viewerHistorySuffix(owner)
        val db = studio.junecity.greenhouse.data.HistoryStore(context, suffix)
        try {
            secure.saveCloudEnrollment(owner)
            sensorStore.save(listOf(sample))
            val rows = (0..71).filter { it !in 31..36 }.map {
                val t = it / 71.0
                studio.junecity.greenhouse.model.HistoryReading(sample.id, sample.name,
                    63.0 + 3.5 * kotlin.math.sin(t * 12) + 4 * (1 - t),
                    79.0 - 6 * kotlin.math.sin(t * 9) - 7 * (1 - t), now - (71 - it) * 300_000L)
            }
            db.importReadings(rows + rows.map { it.copy(sensorId = "another-house", temperatureF = 150.0) })
            val points = db.widgetTrend(setOf(sample.id), now)
            assertEquals(rows.size, points.size)
            assertTrue(points.maxOf { it.temperatureF } < 75)
            assertTrue(points.zipWithNext().any { (a,b) -> b.at - a.at > 600_000 })
            instrumentation.uiAutomation.adoptShellPermissionIdentity(android.Manifest.permission.BIND_APPWIDGET)
            try { assertTrue(manager.bindAppWidgetIdIfAllowed(id, ComponentName(context, ClimateWidget::class.java))) }
            finally { instrumentation.uiAutomation.dropShellPermissionIdentity() }
            ClimateWidget.settings(context,id).edit().putString("scope",owner.widgetScope()).commit()
            val backup = org.json.JSONObject().put("schema", 2)
            assertNotNull(syncViewerReadings(context, owner) { backup })
            assertNull(syncViewerReadings(context, owner, skipRecent = true) { fail("Duplicate fetch"); null })
            fun capture(w: Int, h: Int): android.graphics.Bitmap {
                val options = android.os.Bundle().apply {
                    putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, w)
                    putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, w)
                    putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, h)
                    putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, h)
                }
                manager.updateAppWidgetOptions(id, options)
                ClimateWidget.render(context,id)
                lateinit var bitmap: android.graphics.Bitmap
                instrumentation.runOnMainSync {
                    val view = host.createView(context,id, manager.getAppWidgetInfo(id))
                    val body = view.findViewById<android.view.View>(R.id.widget_body)
                    val density = context.resources.displayMetrics.density
                    val width = (w * density).toInt()
                    val height = (h * density).toInt()
                    body.measure(android.view.View.MeasureSpec.makeMeasureSpec(width, 1073741824),
                        android.view.View.MeasureSpec.makeMeasureSpec(height, 1073741824))
                    body.layout(0, 0, width, height)
                    for (plot in listOf(R.id.widget_temperature_plot, R.id.widget_humidity_plot)) {
                        val image = body.findViewById<android.widget.ImageView>(plot)
                        assertNotNull(image.drawable)
                        assertTrue("Plot fits in ${w}x${h}", image.height >= 7 * density)
                    }
                    bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
                    body.draw(android.graphics.Canvas(bitmap))
                }
                return bitmap
            }
            for ((w,h) in listOf(160 to 96, 284 to 195)) {
                val image = capture(w,h)
                java.io.File(context.cacheDir, "widget-${w}x${h}.png").outputStream().use {
                    image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
            }
            fun temperaturePlot(): android.graphics.Bitmap {
                lateinit var image: android.graphics.Bitmap
                instrumentation.runOnMainSync {
                    val view = host.createView(context, id, manager.getAppWidgetInfo(id))
                    val plot = view.findViewById<android.widget.ImageView>(R.id.widget_temperature_plot)
                    image = (plot.drawable as android.graphics.drawable.BitmapDrawable).bitmap
                }
                return image
            }
            val before = temperaturePlot()
            val newReading = sample.copy(temperatureF = 80.0, updatedAtEpochMs = System.currentTimeMillis())
            db.insert(newReading)
            sensorStore.save(listOf(newReading))
            compose.waitUntil(5_000) { widgetText(4273,id,R.id.widget_temperature) == "80°F" }
            assertFalse(before.sameAs(temperaturePlot()))
            secure.clearCloudEnrollment()
            ClimateWidget.render(context,id)
            instrumentation.runOnMainSync {
                val view = host.createView(context,id, manager.getAppWidgetInfo(id))
                val plot = view.findViewById<android.widget.ImageView>(R.id.widget_temperature_plot)
                assertEquals(android.view.View.GONE, plot.visibility)
                assertNull(plot.drawable)
            }
        } finally {
            host.deleteHost()
            db.close()
            context.deleteDatabase("greenhouse_history$suffix.db")
            sensorStore.save(saved)
            if (original == null) secure.clearCloudEnrollment() else secure.saveCloudEnrollment(original)
        }
    }

    private fun widgetText(hostId: Int, id: Int, field: Int): String {
        var result = ""
        instrumentation.runOnMainSync {
            val host = AppWidgetHost(context, hostId)
            val info = AppWidgetManager.getInstance(context).getAppWidgetInfo(id)
            val view = host.createView(context, id, info)
            result = view.findViewById<android.widget.TextView>(field).text.toString()
        }
        return result
    }

    @Test fun clientAddsWidgetBeforeServerSyncAndKeepsItBetweenModes() {
        assumeTrue(android.os.Build.MODEL.contains("sdk", ignoreCase = true))
        val secure = SecureSettingsStore(context)
        val previous = secure.cloudEnrollment()
        val owner = CloudEnrollment("widget-client", "test-house", CloudDeviceRole.ViewerClient, tenantId = "widget-account")
        val local = owner.copy(greenhouseId = "", localSensors = true)
        val paired = CurrentSensorStore(context)
        val standalone = CurrentSensorStore(context, "_standalone")
        val previousPaired = paired.load()
        val previousLocal = standalone.load()
        val hostId = 4272
        val host = AppWidgetHost(context, hostId)
        val manager = AppWidgetManager.getInstance(context)
        val id = host.allocateAppWidgetId()
        val now = System.currentTimeMillis()
        fun sample(suffix: String, temp: Double) = GoveeSensor("widget-fixture", "H5179", "Bench", temp, 50.0,
            true, now, AppPreferencesStore(context, suffix).load().activeGreenhouse.id)
        fun assertWidget(field: Int, expected: String) {
            compose.waitUntil(5_000) { widgetText(hostId, id, field) == expected }
        }
        try {
            secure.clearCloudEnrollment()
            secure.saveCloudEnrollment(owner)
            paired.save(listOf(sample("", 90.0))) // An unverified cache must stay hidden.
            instrumentation.uiAutomation.adoptShellPermissionIdentity(android.Manifest.permission.BIND_APPWIDGET)
            try { assertTrue(manager.bindAppWidgetIdIfAllowed(id, ComponentName(context, ClimateWidget::class.java))) }
            finally { instrumentation.uiAutomation.dropShellPermissionIdentity() }
            // Launchers can pin without opening the configuration Activity.
            val callback = Intent("studio.junecity.greenhouse.widget.PINNED")
                .setData(android.net.Uri.Builder().scheme("greenhouse-widget").authority("pin")
                    .appendQueryParameter("scope", owner.widgetScope()).build())
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            ClimateWidget().onReceive(context, callback)
            assertWidget(R.id.widget_time, "Waiting for server readings")
            assertEquals(owner.widgetScope(), ClimateWidget.settings(context, id).getString("scope", null))
            val intent = Intent(context, WidgetSettingsActivity::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            ActivityScenario.launch<WidgetSettingsActivity>(intent).use {
                compose.onNodeWithText("Current greenhouse").assertExists()
                compose.onNodeWithText("Save widget").performClick()
                instrumentation.waitForIdleSync()
            }
            assertWidget(R.id.widget_time, "Waiting for server readings")
            assertWidget(R.id.widget_temperature, "—")
            paired.save(listOf(sample("", 72.0)))
            ClimateWidget.viewerSynced(context, owner)
            assertWidget(R.id.widget_temperature, "72°F")
            standalone.save(emptyList())
            secure.saveCloudEnrollment(local)
            assertWidget(R.id.widget_time, "Waiting for greenhouse readings")
            standalone.save(listOf(sample("_standalone", 65.0)))
            assertWidget(R.id.widget_temperature, "65°F")
            secure.saveCloudEnrollment(owner)
            assertWidget(R.id.widget_time, "Waiting for server readings")
            ClimateWidget.viewerSynced(context, owner)
            assertWidget(R.id.widget_temperature, "72°F")
            secure.saveCloudEnrollment(owner.copy(tenantId = "another-account"))
            assertWidget(R.id.widget_time, "Open Greenhouse Studio to set up")
        } finally {
            host.deleteHost()
            paired.save(previousPaired)
            standalone.save(previousLocal)
            if (previous == null) secure.clearCloudEnrollment() else secure.saveCloudEnrollment(previous)
        }
    }
}
