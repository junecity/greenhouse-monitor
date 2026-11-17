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
            studio.junecity.greenhouse.seedMonitoredPlan(context,owner)
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
        val sample = GoveeSensor("temp-probe", "H5179", "Bench", 61.1, 84.0, true,
            System.currentTimeMillis(), prefs.activeGreenhouse.id)
        val now = sample.updatedAtEpochMs
        val host = AppWidgetHost(context, 4273)
        val id = host.allocateAppWidgetId()
        val manager = AppWidgetManager.getInstance(context)
        val suffix = studio.junecity.greenhouse.data.viewerHistorySuffix(owner)
        val db = studio.junecity.greenhouse.data.HistoryStore(context, suffix)
        val training = studio.junecity.greenhouse.data.HeatingStore(context, owner, prefs.activeGreenhouse.id)
        val weatherStore = context.getSharedPreferences("climate_card_sources", 0)
        val weatherPrefix = "weather.${prefs.zipCode}."
        val originalWeather = weatherStore.all.filterKeys { it.startsWith(weatherPrefix) }
        try {
            secure.saveCloudEnrollment(owner)
            sensorStore.save(listOf(sample))
            val trainingDate = java.time.LocalDate.now().minusDays(1)
            training.update(prefs.zipCode, (0..4).map {
                studio.junecity.greenhouse.data.ThermalPoint(trainingDate, now - 90_000_000L + it * 900_000L,
                    60.0, 30.0, listOf(sample.id))
            }, now)
            weatherStore.edit().apply { originalWeather.keys.forEach { remove(it) } }.commit()
            ClimateCardRepository.weatherResult(context, prefs.zipCode,
                studio.junecity.greenhouse.model.WeatherReading(prefs.zipCode, "Weather fixture", 28.0, "Clear",
                    currentTimeMs = now - 35 * 60_000))
            val rows = (0..71).map {
                val t = it / 71.0
                studio.junecity.greenhouse.model.HistoryReading(sample.id, sample.name,
                    69 - 7.9 * t + 0.24 * kotlin.math.sin(t * 2 * Math.PI) + 4 * t * (1 - t) * (1 - t),
                    80 + 4 * t, now - (71 - it) * 300_000L)
            }
            val withGap = rows.filterIndexed { index, _ -> index !in 31..36 }
            db.importReadings(withGap + rows.map { it.copy(sensorId = "another-house", temperatureF = 150.0) })
            val points = db.widgetTrend(setOf(sample.id), now)
            assertEquals(withGap.size, points.size)
            assertTrue(points.maxOf { it.temperatureF } < 75)
            assertTrue(points.zipWithNext().any { (a,b) -> b.at - a.at > 600_000 })
            db.importReadings(rows.slice(31..36), merge = true)
            instrumentation.uiAutomation.adoptShellPermissionIdentity(android.Manifest.permission.BIND_APPWIDGET)
            try { assertTrue(manager.bindAppWidgetIdIfAllowed(id, ComponentName(context, ClimateWidget::class.java))) }
            finally { instrumentation.uiAutomation.dropShellPermissionIdentity() }
            ClimateWidget.settings(context,id).edit().putString("scope",owner.widgetScope()).commit()
            val backup = org.json.JSONObject().put("schema", 2)
                .put("preferences", AppPreferencesStore(context).exportJson())
                .put("sensors", CurrentSensorCodec.encode(listOf(sample)))
            assertNotNull(syncViewerReadings(context, owner) { backup })
            assertNull(syncViewerReadings(context, owner, skipRecent = true) { fail("Duplicate fetch"); null })
            fun capture(w: Int, h: Int, hintHeight: Int = h): android.graphics.Bitmap {
                val options = android.os.Bundle().apply {
                    putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, w)
                    putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, w)
                    putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, hintHeight)
                    putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, hintHeight)
                }
                manager.updateAppWidgetOptions(id, options)
                ClimateWidget.render(context,id)
                val expectedLayout = launcherCard(w, hintHeight, context.resources.configuration.fontScale)
                // The host can briefly retain the previous size while Android delivers an options change.
                compose.waitUntil(5_000) {
                    var applied = false
                    instrumentation.runOnMainSync {
                        val view = host.createView(context, id, manager.getAppWidgetInfo(id))
                        val hasChart = view.findViewById<android.view.View>(R.id.widget_temperature_plot) != null
                        val hasStrip = view.findViewById<android.view.View>(R.id.widget_strip_details) != null
                        applied = hasChart == (expectedLayout != LauncherCard.Compact) && hasStrip == (expectedLayout == LauncherCard.Wide)
                    }
                    applied
                }
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
                    val image = body.findViewById<android.widget.ImageView>(R.id.widget_temperature_plot)
                    if (expectedLayout == LauncherCard.Compact) {
                        assertNull("Compact widgets omit the full-size chart", image)
                    } else {
                        assertNotNull(image.drawable)
                        val matrix = FloatArray(9)
                        image.imageMatrix.getValues(matrix)
                        assertEquals("Preserve chart proportions", matrix[0], matrix[4], 0.001f)
                        assertTrue("Plot fits in ${w}x${h}", image.height >= 24 * density)
                        assertTrue(body.findViewById<android.widget.TextView>(R.id.widget_chart_range).text.contains("→"))
                        assertTrue(body.findViewById<android.widget.TextView>(R.id.widget_temperature_range).text.startsWith("↓"))
                        assertTrue(body.findViewById<android.widget.TextView>(R.id.widget_humidity_range).text.startsWith("80–84%"))
                        if (w == 390 && h == 158) {
                            for (fieldId in listOf(R.id.widget_temperature_label, R.id.widget_temperature_range, R.id.widget_humidity_range)) {
                                val field = body.findViewById<android.widget.TextView>(fieldId)
                                val line = field.layout
                                assertTrue("Full-width phone widget must show the whole caption '${field.text}'",
                                    line.getEllipsisCount(0) == 0 &&
                                        line.getLineBottom(0) <= field.height - field.compoundPaddingTop - field.compoundPaddingBottom + 1)
                            }
                        }
                        val outside = body.findViewById<android.widget.TextView>(R.id.widget_outside)
                        assertEquals("28°F", outside.text)
                        assertEquals("60°F", body.findViewById<android.widget.TextView>(R.id.widget_target).text)
                        assertEquals("DEW POINT", body.findViewById<android.widget.TextView>(R.id.widget_dew_point)
                            .let { (it.parent as android.widget.LinearLayout).getChildAt(0) as android.widget.TextView }
                            .text.toString().uppercase())
                    }
                    val status = body.findViewById<android.widget.TextView>(R.id.widget_status)
                    assertTrue("Real recent sensor data is marked live", status.text.contains("LIVE"))
                    val rect = android.graphics.Rect()
                    for (textId in listOf(R.id.widget_title, R.id.widget_temperature, R.id.widget_humidity, R.id.widget_time)) {
                        val field = body.findViewById<android.widget.TextView>(textId)
                        assertTrue(field.text.isNotBlank())
                        field.getDrawingRect(rect)
                        (body as android.view.ViewGroup).offsetDescendantRectToMyCoords(field, rect)
                        assertTrue("Widget text fits at ${w}x${h}: ${field.text} $rect", rect.top >= 0 && rect.bottom <= height && rect.right <= width)
                    }
                    if (w in 340..400 && h in 130..175 && context.resources.configuration.fontScale <= 1.05f) {
                        fun bounds(id: Int): android.graphics.Rect {
                            val field = body.findViewById<android.view.View>(id)
                            assertNotNull("Full-width phone widget should include view $id", field)
                            assertEquals(android.view.View.VISIBLE, field.visibility)
                            return android.graphics.Rect().also {
                                field.getDrawingRect(it)
                                (body as android.view.ViewGroup).offsetDescendantRectToMyCoords(field, it)
                                assertTrue("${w}x${h}: $id must be fully inside the widget: $it",
                                    it.width() > 0 && it.height() > 0 && it.left >= 0 && it.top >= 0 && it.right <= width && it.bottom <= height)
                            }
                        }
                        val header = bounds(R.id.widget_title)
                        val temp = bounds(R.id.widget_temperature)
                        val humidity = bounds(R.id.widget_humidity)
                        val plot = bounds(R.id.widget_temperature_plot)
                        assertTrue("Readings and graph share the row below the header",
                            header.bottom <= temp.top && temp.right <= humidity.left && humidity.right <= plot.left &&
                                temp.centerY() in humidity.top..humidity.bottom &&
                                plot.top < temp.bottom && plot.bottom > temp.top)
                        val footer = listOf(R.id.widget_health, R.id.widget_outside, R.id.widget_target,
                            R.id.widget_dew_point, R.id.widget_time).map(::bounds)
                        assertTrue("Footer columns must share a row below the graph",
                            footer.all { it.top >= plot.bottom } &&
                                footer.zipWithNext().all { (a, b) -> a.right <= b.left && a.top < b.bottom && b.top < a.bottom })
                        for (fieldId in listOf(R.id.widget_temperature, R.id.widget_humidity, R.id.widget_outside,
                            R.id.widget_target, R.id.widget_dew_point)) {
                            val field = body.findViewById<android.widget.TextView>(fieldId)
                            assertTrue("Measurement must be readable: ${field.text}",
                                field.layout.lineCount > 0 && field.layout.getEllipsisCount(0) == 0)
                        }
                    }
                    bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
                    body.draw(android.graphics.Canvas(bitmap))
                }
                return bitmap
            }
            capture(680, 280)
            instrumentation.runOnMainSync {
                val view = host.createView(context, id, manager.getAppWidgetInfo(id))
                assertTrue("Weather retains its own observation age",
                    view.findViewById<android.widget.TextView>(R.id.widget_outside).contentDescription.contains("delayed", true))
            }
            ClimateCardRepository.weatherResult(context, prefs.zipCode,
                studio.junecity.greenhouse.model.WeatherReading(prefs.zipCode, "Weather fixture", 28.0, "Clear", currentTimeMs = now))
            for ((w,h) in listOf(160 to 96, 284 to 195, 340 to 138, 360 to 150, 386 to 156, 390 to 158, 390 to 360, 680 to 280)) {
                val image = capture(w,h)
                java.io.File(context.cacheDir, "widget-${w}x${h}.png").outputStream().use {
                    image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
            }
            val mismatchedHost = capture(276, 198, hintHeight = 100)
            java.io.File(context.cacheDir, "widget-mismatched-host.png").outputStream().use {
                mismatchedHost.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
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
            capture(390, if (context.resources.configuration.fontScale > 1.05f) 360 else 158)
            val before = temperaturePlot()
            val newReading = sample.copy(temperatureF = 80.0, updatedAtEpochMs = System.currentTimeMillis())
            db.insert(newReading)
            sensorStore.save(listOf(newReading))
            compose.waitUntil(5_000) { widgetText(4273,id,R.id.widget_temperature) == "80°F" }
            assertFalse(before.sameAs(temperaturePlot()))
            val stale = newReading.copy(updatedAtEpochMs = now - 2 * 3_600_000L)
            sensorStore.save(listOf(stale))
            compose.waitUntil(5_000) { widgetText(4273,id,R.id.widget_status).contains("OFFLINE") }
            assertEquals("80°F", widgetText(4273, id, R.id.widget_temperature))
            assertTrue(widgetText(4273, id, R.id.widget_time).contains(widgetDate(stale.updatedAtEpochMs, now)))
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                // Modern launchers can report a small global minimum and a full-width actual slot.
                manager.updateAppWidgetOptions(id, android.os.Bundle().apply {
                    putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 160)
                    putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 96)
                    putParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES,
                        arrayListOf(android.util.SizeF(160f, 96f), android.util.SizeF(300f, 120f),
                            android.util.SizeF(340f, 280f), android.util.SizeF(386f, 156f), android.util.SizeF(680f, 280f)))
                })
                ClimateWidget.render(context, id)
                instrumentation.runOnMainSync {
                    val launcher = host.createView(context, id, manager.getAppWidgetInfo(id))
                    val density = context.resources.displayMetrics.density
                    val width = (680 * density).toInt()
                    val height = (280 * density).toInt()
                    launcher.measure(android.view.View.MeasureSpec.makeMeasureSpec(width, android.view.View.MeasureSpec.EXACTLY),
                        android.view.View.MeasureSpec.makeMeasureSpec(height, android.view.View.MeasureSpec.EXACTLY))
                    launcher.layout(0, 0, width, height)
                    assertNotNull("Launcher uses its actual size list for the full-width graph",
                        launcher.findViewById<android.widget.ImageView>(R.id.widget_temperature_plot)?.drawable)
                }
                manager.updateAppWidgetOptions(id, android.os.Bundle().apply {
                    putParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES, arrayListOf<android.util.SizeF>())
                    putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 680)
                    putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 680)
                    putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 280)
                    putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 280)
                })
            }
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
            context.getSharedPreferences("night_heating", 0).edit().remove("${training.namespace}.${prefs.zipCode}.points").commit()
            weatherStore.edit().apply {
                weatherStore.all.keys.filter { it.startsWith(weatherPrefix) }.forEach { remove(it) }
                originalWeather.forEach { (key, value) ->
                    when (value) {
                        is String -> putString(key, value)
                        is Boolean -> putBoolean(key, value)
                        is Long -> putLong(key, value)
                    }
                }
            }.commit()
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
        fun assertWidget(field: Int, vararg expected: String) {
            var actual = ""
            try {
                compose.waitUntil(5_000) {
                    actual = widgetText(hostId, id, field)
                    actual in expected
                }
            } catch (timeout: androidx.compose.ui.test.ComposeTimeoutException) {
                throw AssertionError("Expected ${expected.toList()}, received '$actual'", timeout)
            }
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
            assertWidget(R.id.widget_time, "Waiting for server readings", "Server readings could not refresh")
            assertEquals(owner.widgetScope(), ClimateWidget.settings(context, id).getString("scope", null))
            val intent = Intent(context, WidgetSettingsActivity::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            ActivityScenario.launch<WidgetSettingsActivity>(intent).use {
                compose.onNodeWithText("Current greenhouse").assertExists()
                compose.onNodeWithText("Save widget").performClick()
                instrumentation.waitForIdleSync()
            }
            assertWidget(R.id.widget_time, "Waiting for server readings", "Server readings could not refresh")
            assertWidget(R.id.widget_temperature, "—")
            paired.save(listOf(sample("", 72.0)))
            ClimateWidget.viewerSynced(context, owner)
            assertWidget(R.id.widget_temperature, "72°F")
            standalone.save(emptyList())
            secure.saveCloudEnrollment(local)
            studio.junecity.greenhouse.seedMonitoredPlan(context,local)
            assertWidget(R.id.widget_time, "No sensors selected")
            standalone.save(listOf(sample("_standalone", 65.0)))
            assertWidget(R.id.widget_temperature, "65°F")
            secure.saveCloudEnrollment(owner)
            assertWidget(R.id.widget_time, "Waiting for server readings", "Server readings could not refresh")
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
