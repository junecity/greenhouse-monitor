package studio.junecity.greenhouse.ui

import android.app.Application
import android.graphics.Rect
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.cancel
import org.junit.Assume.assumeTrue
import studio.junecity.greenhouse.GreenhouseViewModel
import studio.junecity.greenhouse.sensors.*
import android.os.SystemClock
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.Modifier
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import studio.junecity.greenhouse.MainActivity
import studio.junecity.greenhouse.cloud.CloudDeviceRole
import studio.junecity.greenhouse.model.*
import studio.junecity.greenhouse.settings.*
import java.util.concurrent.atomic.AtomicReference

class StandaloneEditorTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val ui get()=instrumentation.uiAutomation

    @Test fun forecastThresholdNotificationAndDailyReminder() {
        val context = instrumentation.targetContext
        assumeTrue("Use isolated emulator", android.os.Build.MODEL.contains("sdk", ignoreCase = true) &&
            SecureSettingsStore(context).cloudEnrollment() == null)
        val alerts = studio.junecity.greenhouse.data.ForecastAlerts(context)
        val rules = alerts.load()
        val manager = context.getSystemService(android.app.NotificationManager::class.java)
        val store = context.getSharedPreferences("forecast_alerts_local", 0)
        val today = java.time.LocalDate.now()
        val day = ForecastDay(today, "Clear sky", 30.0, 62.0, 10)
        val weather = WeatherReading(alerts.currentZip(), "Fixture", 40.0, "Clear sky",
            System.currentTimeMillis(), days = listOf(day, day.copy(date = today.plusDays(2))))
        val tag = "$today|${alerts.currentZip()}|"
        try {
            store.edit().remove("delivered").commit()
            alerts.save(rules.copy(cold = true, heat = false, lowF = 32, reminder = true))
            alerts.evaluate(weather.copy(stale = true))
            assertFalse(manager.activeNotifications.any { it.tag == tag })
            alerts.evaluate(weather)
            fun awaitDelivery(key: String) = (1..60).firstOrNull {
                SystemClock.sleep(100)
                manager.activeNotifications.any { it.tag == key }
            } != null
            assertTrue("Android posted the forecast alert", awaitDelivery(tag))
            manager.cancel(tag, 7311)
            SystemClock.sleep(300)
            alerts.evaluate(weather)
            assertFalse(manager.activeNotifications.any { it.tag == tag })
            alerts.remind("local")
            assertTrue("Android posted the daily reminder", awaitDelivery("daily"))
            val daily = manager.activeNotifications.single { it.tag == "daily" }
            assertTrue(daily.notification.extras.getCharSequence(android.app.Notification.EXTRA_TEXT).toString().contains("30°F"))
        } finally {
            manager.cancel(tag, 7311)
            manager.cancel("daily", 7311)
            store.edit().remove("delivered").apply()
            alerts.save(rules)
        }
    }

    @Test fun deleteGreenhouseFromSettingsRequiresConfirmationAndPersists() {
        val context = instrumentation.targetContext
        val secure = SecureSettingsStore(context)
        assumeTrue("Requires isolated local monitoring", secure.cloudEnrollment() == null && secure.goveeApiKey() == null)
        val snapshot = studio.junecity.greenhouse.cloud.GreenhouseBackupStore(context)
        val original = snapshot.snapshot()
        val prefs = AppPreferencesStore(context)
        val sensors = CurrentSensorStore(context)
        val second = GreenhouseConfig(id="qa-second",name="Greenhouse 2")
        val bench = GoveeSensor("qa-delete-bench","H5179","Bench",69.0,80.0,true,System.currentTimeMillis(),second.id,"Zone 2")
        val history = studio.junecity.greenhouse.data.HistoryStore(context)
        var model: GreenhouseViewModel? = null
        try {
            prefs.save(AppPreferences(greenhouses=listOf(GreenhouseConfig(),second),activeGreenhouseId=second.id))
            sensors.save(listOf(bench))
            history.insert(bench)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val vm = GreenhouseViewModel(activity.application)
                    model = vm
                    activity.requestedOrientation=android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    activity.setContent {
                        StageScreen(vm.state.collectAsState().value.copy(screen=AppScreen.Settings), DeviceStatus(80,true,true),
                            java.time.LocalDateTime.now(),{},{},{},{},{_,_,_->},{_,_->},{},{},{_,_,_->},vm::updatePreferences,
                            Modifier.fillMaxSize())
                    }
                }
                // Settings changes orientation on entry. Wait until the portrait tree is stable.
                click("General")
                find("Display")
                click("General")
                click("Manage greenhouses")
                click("Delete Greenhouse 2")
                find("Delete Greenhouse 2?")
                click("Cancel")
                assertEquals(2,prefs.load().greenhouses.size)
                click("Delete Greenhouse 2")
                click("Delete greenhouse")
                find("Main Greenhouse")
                assertEquals(listOf("main"),prefs.load().greenhouses.map { it.id })
                assertEquals(bench.copy(greenhouseId=""),sensors.load().single())
                assertTrue(history.recent().any { it.sensorId == bench.id })
                snapshot.restore(snapshot.snapshot(),CameraMode.ViewerClient)
                assertEquals("main",prefs.load().activeGreenhouseId)
                assertEquals("",sensors.load().single().greenhouseId)
                assertTrue(ui.rootInActiveWindow.findAccessibilityNodeInfosByText("Delete").isEmpty())
            }
        } finally {
            instrumentation.runOnMainSync { model?.viewModelScope?.cancel() }
            history.writableDatabase.delete("readings","sensor_id=?",arrayOf(bench.id))
            history.close()
            snapshot.restore(original)
        }
    }

    @Test fun independentCredentialAndLayoutStorage() {
        val context=instrumentation.targetContext
        val secure=SecureSettingsStore(context)
        val single=CloudEnrollment("test-client","",CloudDeviceRole.ViewerClient)
        val paired=single.copy(greenhouseId="paired-house")
        val old=secure.sensorKey(single)
        try {
            secure.saveSensorKey(single,"test-fixture")
            assertEquals("test-fixture",secure.sensorKey(single))
            assertNull(secure.sensorKey(paired))
            val optedIn=paired.copy(localSensors=true)
            assertTrue(CloudEnrollment.parse(optedIn.toJson()).isStandaloneClient())
            assertEquals("test-fixture",secure.sensorKey(optedIn))
            assertFalse(CloudEnrollment.parse(paired.toJson()).isStandaloneClient())
            secure.saveSensorKey(paired,"should-be-ignored")
            assertEquals("test-fixture",secure.sensorKey(single))
            val store=AppPreferencesStore(context,"_editor_fixture")
            val shape=DrawnPlan.fromStroke(listOf(NormalizedPosition(0.0,0.0),NormalizedPosition(8.0,0.0),NormalizedPosition(0.0,16.0)),GreenhouseConfig(),.2)!!
            store.save(AppPreferences(greenhouses=listOf(shape)))
            assertEquals(shape.vertices,store.load().activeGreenhouse.vertices)
            assertEquals(64.0,store.load().activeGreenhouse.areaSquareFeet,.001)
            store.clear()
        } finally { secure.saveSensorKey(single,old.orEmpty()) }
    }

    @Test fun serverDashboardShowsCollectedReadingsAfterResumeAcrossFullDisplay() {
        val context=instrumentation.targetContext
        val store=CurrentSensorStore(context)
        val secure=SecureSettingsStore(context)
        assumeTrue("Requires an isolated test installation",secure.cloudEnrollment()==null && secure.goveeApiKey()==null && store.load().isEmpty())
        val house=GreenhouseConfig(id="second",name="Greenhouse 2")
        val device=SensorDevice("govee","qa-server-bench","H5179","North bench")
        val provider=object:SensorProvider{
            override val providerId="govee"
            override suspend fun discover(credential:SensorProviderCredential)=listOf(device)
            override suspend fun read(credential:SensorProviderCredential,device:SensorDevice)=
                SensorMeasurement(device,68.0,82.0,connected=true,recordedAtEpochMs=System.currentTimeMillis())
        }
        val prefs=AppPreferencesStore(context)
        val saved=prefs.load()
        var vm:GreenhouseViewModel?=null
        try {
            prefs.save(AppPreferences(greenhouses=listOf(house),activeGreenhouseId=house.id))
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val model=GreenhouseViewModel(activity.application,provider)
                    vm=model
                    activity.requestedOrientation=android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                    secure.saveGoveeApiKey("fixture")
                    model.viewModelScope.launch { model.refreshWhileVisible() }
                    activity.setContent {
                        DashboardScreen(model.state.collectAsState().value,DeviceStatus(80,true,true),
                            java.time.LocalDateTime.now(),{},Modifier.fillMaxSize())
                    }
                }
                find("North bench")
                find("68°F")
                find("82%")
                find("Greenhouse 2")
                scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
                scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
                find("North bench")
                scenario.onActivity { activity ->
                    val view=activity.findViewById<android.view.View>(android.R.id.content)
                    val real=android.graphics.Point()
                    @Suppress("DEPRECATION")
                    activity.windowManager.defaultDisplay.getRealSize(real)
                    assertEquals(real.x,view.width)
                    assertEquals(real.y,view.height)
                    assertNavigationHidden(view)
                }
                screenshot("server-dashboard")
            }
        } finally {
            instrumentation.runOnMainSync { vm?.viewModelScope?.cancel() }
            secure.clearGoveeApiKey()
            prefs.save(saved)
            store.clear()
            studio.junecity.greenhouse.data.HistoryStore(context).use {
                it.writableDatabase.delete("readings","sensor_id=?",arrayOf(device.deviceId))
            }
        }
    }

    @Test fun serverCanLeaveLiveCameraForZonesWithLegacyFloorPlan() {
        val context = instrumentation.targetContext
        // Isolated preference namespace; never touch an enrolled device's measurements.
        val storage = AppPreferencesStore(context, "_zones_regression")
        val raw = context.getSharedPreferences("greenhouse_preferences_v2_zones_regression", 0)
        raw.edit().putString("state", """{"greenhouses":[{"id":"main","length":0,"width":8,"positions":{"bench":{"x":3,"y":2},"other":{"y":4}}}]}""").commit()
        try {
            val preferences = storage.load()
            assertEquals(16.0, preferences.activeGreenhouse.lengthFeet, 0.0)
            assertEquals(mapOf("bench" to SensorPosition(3.0,2.0)), preferences.activeGreenhouse.sensorPositions)
            storage.importJson(storage.exportJson())
            assertEquals(preferences, storage.load())
            val sensor = GoveeSensor("bench","H5179","Bench sensor",68.0,82.0,true,System.currentTimeMillis())
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.requestedOrientation=android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                    activity.setContent {
                        val screen = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(AppScreen.Dashboard) }
                        val state = GreenhouseUiState(screen=screen.value, sensors=listOf(sensor), preferences=preferences)
                        val navigate: (AppScreen)->Unit = { screen.value=it }
                        val status = DeviceStatus(80,true,true)
                        val now = java.time.LocalDateTime.now()
                        if(screen.value==AppScreen.Dashboard)
                            DashboardScreen(state,status,now,navigate,Modifier.fillMaxSize())
                        else StageScreen(state,status,now,navigate,{},{},{},{_,_,_->},{_,_->},{},{},{_,_,_->},{},Modifier.fillMaxSize())
                    }
                }
                repeat(2) {
                    find("Live front camera")
                    click("Zones")
                    find("Zone Heat Map")
                    find("Bench sensor")
                    click("Dashboard")
                }
            }
        } finally { storage.clear() }
    }

    @Test fun oversizedBackupRetriesWithRecentHistoryAndKeepsCurrentSensorsAndLocalHistory() = kotlinx.coroutines.runBlocking {
        val original = org.json.JSONObject().put("sensors", org.json.JSONArray().put("bench"))
            .put("history", org.json.JSONArray(listOf(1,2,3,4,5)))
        var attempts=0
        studio.junecity.greenhouse.cloud.uploadSensorSnapshot(original) {
            attempts++
            if (it.getJSONArray("history").length()>2) throw studio.junecity.greenhouse.cloud.CloudApiException(413,"backup_too_large")
            assertEquals("[4,5]", it.getJSONArray("history").toString())
            assertEquals("bench", it.getJSONArray("sensors").getString(0))
        }
        assertEquals(2,attempts)
        assertEquals(5,original.getJSONArray("history").length())
    }

    @Test fun assignedReadingsSurviveServerBackupAndRenderOnViewerWithSystemNavigationHidden() {
        val context = instrumentation.targetContext
        val backup = studio.junecity.greenhouse.cloud.GreenhouseBackupStore(context)
        val saved = backup.snapshot()
        val prefs = AppPreferencesStore(context)
        val store = CurrentSensorStore(context)
        val second = GreenhouseConfig(id="second",name="Greenhouse 2")
        val at = System.currentTimeMillis()
        val live = GoveeSensor("bench","H5179","Bench",72.0,61.0,true,at,second.id,"Zone 1")
        val old = live.copy(id="door",name="Door",online=false,updatedAtEpochMs=at-900_000)
        val other = live.copy(id="outside",name="Other house",temperatureF=99.0,greenhouseId="main")
        try {
            prefs.save(AppPreferences(greenhouses=listOf(GreenhouseConfig(),second),activeGreenhouseId=second.id))
            store.save(listOf(live,old,other))
            val payload = backup.snapshot()
            store.clear()
            prefs.clear()
            backup.restore(payload,CameraMode.ViewerClient)
            assertEquals(listOf(live,old,other),store.load())
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.requestedOrientation=android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                    activity.setContent {
                        DashboardScreen(GreenhouseUiState(sensors=store.load(), preferences=prefs.load()),DeviceStatus(80,true,true),
                            java.time.LocalDateTime.now(),{},Modifier.fillMaxSize())
                    }
                }
                find("Greenhouse 2")
                find("Sensors (2)")
                find("1 live")
                find("72°F")
                find("Door · Last read 15m ago")
                assertTrue(ui.rootInActiveWindow.findAccessibilityNodeInfosByText("99°F").isEmpty())
                scenario.onActivity { activity ->
                    val view=activity.window.decorView
                    assertNavigationHidden(view)
                    assertEquals(view.width, activity.findViewById<android.view.View>(android.R.id.content).width)
                }
                screenshot("server-snapshot-dashboard")
            }
        } finally { backup.restore(saved) }
    }

    @Test fun standaloneSetupDiscoversReadingsAndRestoresThemOnReopen() {
        val context=instrumentation.targetContext
        val secure=SecureSettingsStore(context)
        val enrollment=CloudEnrollment("fixture-client","",CloudDeviceRole.ViewerClient)
        val store=CurrentSensorStore(context,"_standalone")
        assumeTrue("Requires a clean test installation", secure.cloudEnrollment()==null && secure.sensorKey(enrollment)==null && store.load().isEmpty())
        val device=SensorDevice("fixture","fixture-bench","H5179","Bench")
        val provider=object : SensorProvider {
            override val providerId="fixture"
            override suspend fun discover(credential:SensorProviderCredential):List<SensorDevice>{
                assertEquals("example-test-key",(credential as SensorProviderCredential.ApiKey).value)
                return listOf(device)
            }
            override suspend fun read(credential:SensorProviderCredential,device:SensorDevice)=
                SensorMeasurement(device,72.0,61.0,connected=true,recordedAtEpochMs=System.currentTimeMillis())
        }
        lateinit var first:GreenhouseViewModel
        lateinit var reopened:GreenhouseViewModel
        val prefs=AppPreferencesStore(context,"_standalone")
        val originalPrefs=prefs.load()
        try {
            secure.saveCloudEnrollment(enrollment)
            instrumentation.runOnMainSync {
                first=GreenhouseViewModel(context.applicationContext as Application,provider)
                assertTrue(first.state.value.standalone)
                assertEquals(CameraMode.ViewerClient,first.state.value.preferences.cameraMode)
                assertTrue(first.state.value.sensors.isEmpty())
                first.saveApiKey("example-test-key")
            }
            repeat(50){if(first.state.value.sensors.isEmpty())SystemClock.sleep(80)}
            assertEquals(72.0,first.state.value.sensors.single().temperatureF!!,0.0)
            assertEquals(61.0,first.state.value.sensors.single().humidityPercent!!,0.0)
            assertTrue(secure.cloudEnrollment()!!.localSensors)
            instrumentation.runOnMainSync {
                first.viewModelScope.cancel()
                reopened=GreenhouseViewModel(context.applicationContext as Application,provider)
                assertEquals("fixture-bench",reopened.state.value.sensors.single().id)
                assertTrue(reopened.state.value.preferences.activeGreenhouse.sensorPositions.containsKey("fixture-bench"))
                reopened.viewModelScope.cancel()
            }
        } finally {
            secure.clearCloudEnrollment()
            secure.clearStandaloneKey()
            store.clear()
            prefs.save(originalPrefs)
            studio.junecity.greenhouse.data.HistoryStore(context,"_standalone").use {
                it.writableDatabase.delete("readings","sensor_id=?",arrayOf(device.deviceId))
            }
        }
    }

    @Test fun standaloneSettingsOpenSensorSetupAndKeepCameraOutOfTheMenu() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.requestedOrientation=android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                activity.setContent {
                    StageScreen(GreenhouseUiState(screen=AppScreen.Settings,standalone=true,
                        preferences=AppPreferences(cameraMode=CameraMode.ViewerClient)),
                        DeviceStatus(80,true,true),java.time.LocalDateTime.now(),{},{},{},{},{_,_,_->},{_,_->},
                        {},{},{_,_,_->},{},Modifier.fillMaxSize())
                }
            }
            find("Govee API key")
            click("Devices")
            find("Connectivity")
            val menu=ui.rootInActiveWindow
            assertTrue(menu.findAccessibilityNodeInfosByText("Camera").isEmpty())
            click("General")
            click("Edit layout")
            find("Greenhouse floor plan")
        }
    }

    @Test fun repeatedZoneDragsPersistAfterReopen() {
        val context = instrumentation.targetContext
        assumeTrue("Requires isolated emulator", android.os.Build.MODEL.contains("sdk", true) &&
            SecureSettingsStore(context).cloudEnrollment() == null)
        val store = AppPreferencesStore(context)
        val original = store.load()
        val sensor = GoveeSensor("placement", "H5179", "Bench", 65.0, 70.0, true,
            System.currentTimeMillis(), zone = "Zone 1")
        val initial = SensorPosition(3.0, 2.0)
        val house = GreenhouseConfig(sensorPositions = mapOf(sensor.id to initial))
        val app = context.applicationContext as Application
        fun position() = store.load().activeGreenhouse.sensorPositions.getValue(sensor.id)
        fun marker() = Rect().also { find("S1").getBoundsInScreen(it) }
        try {
            store.save(AppPreferences(greenhouses = listOf(house)))
            var lastBounds = Rect()
            repeat(2) { launch ->
                val vm = GreenhouseViewModel(app)
                try {
                    ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                        scenario.onActivity { activity ->
                            activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                            activity.setContent {
                                val g = vm.state.collectAsState().value.preferences.activeGreenhouse
                                HeatMap(emptyList(), Metric.Temperature, g, listOf(sensor),
                                    { (it * .5f).dp }, { it.sp }, vm::moveSensor, Modifier.fillMaxSize())
                            }
                        }
                        if (launch == 0) {
                            var previous = initial
                            repeat(2) {
                                val start = marker()
                                val distance = 3 * start.width()
                                val path = Rect(start.centerX(), start.centerY(), start.centerX() + distance, start.centerY() + 1)
                                gesture(path, listOf(0f to 0f, 1f to 0f))
                                assertTrue("Each drag advances the stored position", position().xFeet > previous.xFeet + .1)
                                previous = position()
                                val deadline = SystemClock.elapsedRealtime() + 3000
                                do {
                                    SystemClock.sleep(100)
                                    lastBounds = marker()
                                } while (lastBounds.centerX() <= start.centerX() && SystemClock.elapsedRealtime() < deadline)
                                assertTrue("Marker moves from ${start.centerX()} to ${lastBounds.centerX()}", lastBounds.centerX() > start.centerX())
                            }
                        } else {
                            assertEquals(lastBounds.centerX(), marker().centerX())
                            assertEquals(position(), vm.state.value.preferences.activeGreenhouse.sensorPositions[sensor.id])
                        }
                    }
                } finally { vm.viewModelScope.cancel() }
            }
        } finally { store.save(original) }
    }

    @Test fun traceTrianglePlaceSensorAndSave() {
        val saved=AtomicReference<GreenhouseConfig>()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.requestedOrientation=android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                activity.setContent {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        FloorPlanEditor(GreenhouseConfig(),listOf(GoveeSensor("sensor1","H5179","Bench sensor")),saved::set)
                    }
                }
            }
            click("Draw outline")
            val bounds=Rect().also { find("Greenhouse floor plan").getBoundsInScreen(it) }
            gesture(bounds,listOf(.2f to .7f,.5f to .2f,.8f to .7f,.2f to .7f))
            click("Bench sensor")
            gesture(bounds,listOf(.5f to .6f))
            gesture(bounds,listOf(.5f to .6f,.50f to .47f))
            click("Save layout")
            instrumentation.waitForIdleSync()
            val result=requireNotNull(saved.get())
            assertEquals(GreenhouseShape.Drawn,result.shape)
            assertEquals(3,result.vertices.size)
            val position=requireNotNull(result.sensorPositions["sensor1"])
            assertTrue(result.containsPoint(position.xFeet,position.yFeet))
            assertTrue("Dragging must move the sensor above the footprint center", position.yFeet < result.widthFeet / 2)

        }
    }

    @Test fun recoveredOfflineNotificationsAreRemovedFromAndroid() {
        val context=instrumentation.targetContext
        val manager=context.getSystemService(android.app.NotificationManager::class.java)
        val channel="greenhouse_cloud_critical"
        manager.createNotificationChannel(android.app.NotificationChannel(channel,"Critical greenhouse alerts",4))
        val old="fixture-old".hashCode()
        val current="fixture-active".hashCode()
        val heat="fixture-hot".hashCode()
        fun post(id:Int,title:String) = manager.notify(id, android.app.Notification.Builder(context,channel)
            .setSmallIcon(android.R.drawable.ic_dialog_alert).setContentTitle(title).build())
        fun awaitIds(expected:Set<Int>) {
            repeat(30) {
                if(manager.activeNotifications.map { it.id }.toSet().intersect(setOf(old,current,heat))==expected) return
                SystemClock.sleep(100)
            }
            fail("Expected posted notification IDs: $expected")
        }
        try {
            post(old,"Greenhouse server offline")
            post(current,"Greenhouse server offline")
            post(heat,"Critical greenhouse temperature")
            awaitIds(setOf(old,current,heat))
            val active=studio.junecity.greenhouse.cloud.MonitoringAlert("fixture-active","house",
                studio.junecity.greenhouse.cloud.MonitoringAlertType.ServerOffline,
                studio.junecity.greenhouse.cloud.MonitoringAlertPriority.High,"offline",123,true)
            studio.junecity.greenhouse.cloud.clearRecoveredOfflineAlerts(manager,listOf(active))
            awaitIds(setOf(current,heat))
            studio.junecity.greenhouse.cloud.clearRecoveredOfflineAlerts(manager,emptyList())
            awaitIds(setOf(heat))
        } finally { listOf(old,current,heat).forEach(manager::cancel) }
    }

    @Test fun shapePresetsSaveAndRoundTripThroughTheZoneFootprint() {
        val store = AppPreferencesStore(instrumentation.targetContext,"_preset_fixture")
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.requestedOrientation=android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    activity.setContent {
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                            FloorPlanEditor(GreenhouseConfig(),emptyList()) { store.save(AppPreferences(greenhouses=listOf(it))) }
                        }
                    }
                }
                for ((label, shape) in listOf("L-shaped" to GreenhouseShape.LShape,"T-shaped" to GreenhouseShape.TShape,
                        "Circle" to GreenhouseShape.Circle)) {
                    click("Shape presets")
                    click(label)
                    click("Save layout")
                    instrumentation.waitForIdleSync()
                    val expected = GreenhouseConfig().withPreset(shape)
                    assertEquals(expected,store.load().activeGreenhouse)
                    val backup = store.exportJson()
                    store.clear()
                    store.importJson(backup)
                    assertEquals(expected.outline(),store.load().activeGreenhouse.outline())
                }
            }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.requestedOrientation=android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                    activity.setContent {
                        StageScreen(GreenhouseUiState(screen=AppScreen.Zones,preferences=store.load()),
                            DeviceStatus(80,true,true),java.time.LocalDateTime.now(),{},{},{},{},{_,_,_->},{_,_->},
                            {},{},{_,_,_->},{},Modifier.fillMaxSize())
                    }
                }
                find("Ø 16 ft")
                find("Main Greenhouse · 201.06 sq ft")
            }
        } finally { store.clear() }
    }

    @Test fun dragCornersThenReopenSavedFootprintInZones() {
        val store=AppPreferencesStore(instrumentation.targetContext,"_corner_fixture")
        store.clear()
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.requestedOrientation=android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    activity.setContent {
                        val prefs=androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(store.load()) }
                        StageScreen(GreenhouseUiState(screen=AppScreen.Settings,standalone=true,preferences=prefs.value),
                            DeviceStatus(80,true,true),java.time.LocalDateTime.now(),{},{},{},{},{_,_,_->},{_,_->},
                            {},{},{_,_,_->},{ update ->
                                prefs.value=update(prefs.value)
                                store.save(prefs.value)
                            },Modifier.fillMaxSize())
                    }
                }
                find("Govee API key")
                click("Devices")
                find("Connectivity")
                click("General")
                click("Edit layout")
                find("128 sq ft")
                val bounds=Rect().also { find("Greenhouse floor plan").getBoundsInScreen(it) }
                val scale=minOf(bounds.width()/(16f*1.35f),bounds.height()/(8f*1.35f))
                fun at(x:Float,y:Float)=(.5f+(x-8)*scale/bounds.width()) to (.5f+(y-4)*scale/bounds.height())
                gesture(bounds,listOf(at(16f,0f),at(12.1f,2f)))
                find("96 sq ft")
                click("Save layout")
                val snapped=store.load().activeGreenhouse
                assertEquals(96.0,snapped.areaSquareFeet,.001)
                click("Edit layout")
                click("Snap on")
                gesture(bounds,listOf(at(6f,1f),at(6.35f,3.2f)))
                recordScreen("layout-editor")
                click("Save layout")
                val free=store.load().activeGreenhouse
                assertEquals(5,free.vertices.size)
                assertTrue(free.areaSquareFeet in 80.0..88.0)
                assertTrue(kotlin.math.abs(free.areaSquareFeet-kotlin.math.round(free.areaSquareFeet))>.01)
            }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val recovered=store.load().activeGreenhouse
                scenario.onActivity { activity ->
                    activity.requestedOrientation=android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                    activity.setContent {
                        StageScreen(GreenhouseUiState(screen=AppScreen.Zones,preferences=store.load()),
                            DeviceStatus(80,true,true),java.time.LocalDateTime.now(),{},{},{},{},{_,_,_->},{_,_->},
                            {},{},{_,_,_->},{},Modifier.fillMaxSize())
                    }
                }
                find("${recovered.name} · ${recovered.areaLabel()}")
                find(recovered.dimensionsLabel())
                recordScreen("layout-zones")
            }
        } finally { store.clear() }
    }

    private fun recordScreen(name:String) {
        if (InstrumentationRegistry.getArguments().getString("captureLayout")!="true") return
        val bitmap=requireNotNull(ui.takeScreenshot())
        try {
            instrumentation.targetContext.openFileOutput("$name.png",0).use { stream ->
                check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,stream))
            }
        } finally { bitmap.recycle() }
    }

    @Test fun dashboardClearsHolePunchInBothLandscapeRotations() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val sides = mutableSetOf<String>()
            val original = mutableMapOf<String, Rect>()
            for (orientation in listOf(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
                                       android.content.pm.ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE)) {
                scenario.onActivity { activity ->
                    activity.requestedOrientation = orientation
                    activity.setContent {
                        val g = GreenhouseConfig(sensorPositions = mapOf("bench" to SensorPosition(8.0,4.0)))
                        val sensor = GoveeSensor("bench", "H5179", "Bench",69.0,91.0,true,System.currentTimeMillis(),zone="Zone 1")
                        DashboardScreen(GreenhouseUiState(standalone=true,sensors=listOf(sensor), preferences=AppPreferences(greenhouses=listOf(g))), DeviceStatus(80,true,true),
                            java.time.LocalDateTime.now(), {}, Modifier.fillMaxSize())
                    }
                }
                SystemClock.sleep(1200)
                val temp = Rect().also { find("Avg Temp").getBoundsInScreen(it) }
                screenshot("dashboard-cutout-$orientation")
                val reading = Rect().also { find("69°F").getBoundsInScreen(it) }
                val dew = Rect().also { find("Dew Point").getBoundsInScreen(it) }
                val labels = listOf("Outdoor", "VPD", "Avg RH", "Dashboard", "Main Greenhouse")
                val landmarks = labels.associateWith { label -> Rect().also { find(label).getBoundsInScreen(it) } }
                var nav = find("Dashboard")
                while (!nav.isClickable && nav.parent != null) nav = nav.parent
                val firstTab = Rect().also { nav.getBoundsInScreen(it) }
                scenario.onActivity { activity ->
                    val view = activity.window.decorView
                    val cutout = view.rootWindowInsets.displayCutout
                    assumeTrue("Exercise with a landscape display cutout", cutout != null &&
                        (cutout.safeInsetLeft > 0 || cutout.safeInsetRight > 0))
                    val left = cutout!!.safeInsetLeft
                    val holes = cutout.boundingRects
                    for (rect in listOf(temp,dew,reading)) {
                        assertFalse("A reading intersects the camera: $rect", holes.any { Rect.intersects(it,rect) })
                    }
                    assertTrue("Dashboard nav should begin at the original edge: $firstTab", firstTab.left < view.width / 30)
                    // A centered lens touches only the Avg Temp card in normal landscape.
                    // These original anchors must be pixel-identical with the lens on the opposite side.
                    val centered = holes.any { it.centerY() in view.height/3..(2*view.height/3) }
                    if (centered) landmarks.forEach { (label, bounds) ->
                        original[label]?.let { assertEquals("The cutout moved $label", it, bounds) }
                        original[label] = bounds
                    }
                    sides += if (left > 0) "left" else "right"
                }
            }
            assertEquals(setOf("left","right"), sides)
        }
    }

    @Test fun historyWeekIncludesOlderReadingsBeyondDashboardLimit() {
        val context = instrumentation.targetContext
        val suffix = "_week_chart_fixture"
        val now = System.currentTimeMillis()
        val rows = (1..2200).map { HistoryReading("fixture","Bench",65.0,80.0,now-it) }
        val old = rows.first().copy(timestampEpochMs=now-6*86_400_000L)
        val expired = rows.first().copy(timestampEpochMs=now-8*86_400_000L)
        try {
            studio.junecity.greenhouse.data.HistoryStore(context,suffix).use { db ->
                db.importReadings(rows+old+expired)
                assertEquals(2201,db.weekReadings().size)
                assertEquals(old,db.weekReadings().last())
                assertFalse(old in db.dashboardReadings())
            }
        } finally { context.deleteDatabase("greenhouse_history$suffix.db") }
    }

    @Test fun viewerWithoutMountingHeightsRendersBothHeatMaps() {
        val plan = GreenhouseConfig(shape = GreenhouseShape.TShape, sensorPositions = mapOf(
            "north" to SensorPosition(2.0, 3.0), "east" to SensorPosition(13.0, 2.0),
            "south" to SensorPosition(7.0, 21.0)))
        val sensor = GoveeSensor("north", "H5179", "North", 70.0, 84.0, true, System.currentTimeMillis())
        val readings = listOf(sensor, sensor.copy(id = "east", name = "East", temperatureF = 59.0),
            sensor.copy(id = "south", name = "South", temperatureF = 60.0))
        val prefs = AppPreferences(cameraMode = CameraMode.ViewerClient, greenhouses = listOf(plan))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                activity.setContent {
                    ZoneVolumeScreen(GreenhouseUiState(sensors = readings, preferences = prefs),
                        { error("The viewer must not write mounting heights") }, {})
                }
            }
            find("Sensors with unknown heights are shown on the floor plan.")
            fun coloredPixels(humidity: Boolean): Int {
                SystemClock.sleep(650)
                val bounds = Rect().also { find("3D greenhouse. Drag to rotate; pinch to zoom and pan.").getBoundsInScreen(it) }
                val image = requireNotNull(ui.takeScreenshot())
                try {
                    var count = 0
                    for (y in bounds.top until bounds.bottom step 3) for (x in bounds.left until bounds.right step 3) {
                        val pixel = image.getPixel(x, y)
                        val blue = android.graphics.Color.blue(pixel)
                        val green = android.graphics.Color.green(pixel)
                        if (if (humidity) blue > green + 20 && blue > 110 else green > blue + 20 && green > 110) count++
                    }
                    return count
                } finally { image.recycle() }
            }
            assertTrue("Temperature must visibly color the footprint", coloredPixels(false) > 300)
            screenshot("height-unset-temperature")
            click("Humidity")
            find("North ▾  ·  84%  ·  Height unset")
            assertTrue("Humidity must visibly recolor the footprint", coloredPixels(true) > 300)
            screenshot("height-unset-humidity")
        }
    }

    @Test fun volumeGesturesHumidityMountingAndLegacyBackup() {
        val store = AppPreferencesStore(instrumentation.targetContext,"_volume_fixture")
        try {
            val plan = GreenhouseConfig(heightFeet=9.0,shape=GreenhouseShape.TShape,sensorPositions=mapOf(
                "floor" to SensorPosition(4.0,2.0,0.0), "roof" to SensorPosition(4.0,2.0,7.0)))
            val prefs=AppPreferences(greenhouses=listOf(plan))
            store.save(prefs)
            assertEquals(plan,store.load().activeGreenhouse)
            val legacy=store.exportJson()
            legacy.getJSONArray("greenhouses").getJSONObject(0).getJSONObject("positions").getJSONObject("floor").remove("floorMount")
            assertNull(store.decode(legacy).activeGreenhouse.sensorPositions["floor"]!!.heightFeet)
            val now=System.currentTimeMillis()
            val low=GoveeSensor("floor","H5179","Floor",64.0,90.0,true,now)
            val high=low.copy(id="roof",name="Rafter",temperatureF=76.0,humidityPercent=69.0)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { a ->
                    a.requestedOrientation=android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                    a.setContent {
                        val settings = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(prefs) }
                        ZoneVolumeScreen(GreenhouseUiState(standalone=true,sensors=listOf(low,high),preferences=settings.value),
                            { change -> settings.value=change(settings.value);store.save(settings.value) },
                            { error("Rendering the greenhouse must not dismiss it") })
                    }
                }
                find("Floor ▾  ·  64°F  ·  0 ft")
                val canvas=Rect().also { find("3D greenhouse. Drag to rotate; pinch to zoom and pan.").getBoundsInScreen(it) }
                gesture(canvas,listOf(.25f to .6f,.5f to .4f))
                val view = "3D greenhouse. Drag to rotate; pinch to zoom and pan."
                pinchAndPan(canvas)
                if (android.os.Build.VERSION.SDK_INT >= 30) {
                    assertNotEquals("Zoom 100%", find(view).stateDescription)
                }
                click("Fit")
                if (android.os.Build.VERSION.SDK_INT >= 30) {
                    val deadline = SystemClock.uptimeMillis() + 3000
                    while (find(view).stateDescription?.toString() != "Zoom 100%" && SystemClock.uptimeMillis() < deadline) {
                        SystemClock.sleep(50)
                    }
                    assertEquals("Zoom 100%", find(view).stateDescription?.toString())
                }
                click("Humidity")
                find("Floor ▾  ·  90%  ·  0 ft")
                click("Floor ▾  ·  90%  ·  0 ft")
                click("Rafter")
                find("Rafter ▾  ·  69%  ·  7 ft")
                click("Set height")
                val input = find("7")
                check(input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, android.os.Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "6.5")
                }))
                find("6.5")
                instrumentation.waitForIdleSync()
                click("Save")
                find("Rafter ▾  ·  69%  ·  6.5 ft")
                assertEquals(6.5,store.load().activeGreenhouse.sensorPositions["roof"]!!.heightFeet!!,.001)
                click("Temperature")
                find("Rafter ▾  ·  76°F  ·  6.5 ft")
                SystemClock.sleep(550)
                screenshot("volume")
                scenario.onActivity{it.requestedOrientation=android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT}
                find("Rafter ▾  ·  76°F  ·  6.5 ft")
                screenshot("volume-portrait")
            }
        } finally { store.clear() }
    }

    @Test fun openComparisonReplayAndOvernightReport() {
        val now = System.currentTimeMillis()
        val night = studio.junecity.greenhouse.data.NightWindow.at(now)
        val sensors = listOf(GoveeSensor("a","H5179","North",63.0,80.0,true,now,zone="Zone 1"),
            GoveeSensor("b","H5179","South",67.0,70.0,true,now,zone="Zone 2"))
        val history = sensors.flatMap { sensor -> listOf(night.start, night.end).map { at ->
            HistoryReading(sensor.id,sensor.name,sensor.temperatureF!!,sensor.humidityPercent!!,at) } }
        val g = GreenhouseConfig(sensorPositions=mapOf("a" to SensorPosition(3.0,2.0),"b" to SensorPosition(10.0,5.0)))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            fun show(screen:AppScreen) {
                scenario.onActivity { activity ->
                    activity.requestedOrientation=android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                    activity.setContent {
                        StageScreen(GreenhouseUiState(screen=screen,standalone=true,sensors=sensors,history=history,
                            preferences=AppPreferences(greenhouses=listOf(g))),DeviceStatus(80,true,true),
                            java.time.LocalDateTime.now(),{},{},{},{},{_,_,_->},{_,_->},{},{},{_,_,_->},{},Modifier.fillMaxSize())
                    }
                }
            }
            show(AppScreen.Zones)
            click("Compare")
            find("Current difference · 4°F")
            click("Humidity")
            find("Current difference · 10 percentage points")
            fun scrub(label: String, fraction: Float) {
                val bounds = Rect().also { find(label).getBoundsInScreen(it) }
                gesture(bounds, listOf(.1f to .5f, fraction to .5f))
            }
            scrub("Humidity trend", .5f)
            find("Difference · —")
            click("Latest")
            find("Current difference · 10 percentage points")
            screenshot("compare")
            click("Close")
            click("Replay")
            find("Overnight replay")
            find("S1")
            click("Play")
            find("Pause")
            scrub("Temperature trend", .5f)
            find("Play")
            find("No mapped readings")
            scrub("Temperature trend", 0f)
            find("S1")
            screenshot("replay")
            click("Close")
            show(AppScreen.History)
            click("7d")
            scrub("Temperature trend", .5f)
            find("—")
            click("6h")
            find("Latest readings")
            screenshot("history")
            click("Overnight report")
            find("Overnight report")
            screenshot("overnight")
        }
    }

    @Test fun alertIntentCarriesTheIncidentAndLocalNotifierTargetsTheZone() {
        val context = instrumentation.targetContext
        val manager = context.getSystemService(android.app.NotificationManager::class.java)
        val id = "qa-alert-reading"
        val suffix = "_qa_alert"
        val state = context.getSharedPreferences("alert_state$suffix", android.content.Context.MODE_PRIVATE)
        state.edit().clear().commit()
        try {
            val at = System.currentTimeMillis()
            studio.junecity.greenhouse.data.AlertNotifier(context,suffix).evaluate(
                GoveeSensor(id, "H5179", "Bench",25.0,70.0,true,at,zone="Zone 3"))
            val deadline = SystemClock.elapsedRealtime() + 3000
            while (manager.activeNotifications.none { it.id == id.hashCode() } && SystemClock.elapsedRealtime() < deadline) {
                SystemClock.sleep(50)
            }
            val notice = manager.activeNotifications.single { it.id == id.hashCode() }.notification
            assertTrue(notice.extras.getString(android.app.Notification.EXTRA_TITLE)!!.contains("Zone 3"))
            assertNotNull(notice.contentIntent)
            val intent = android.content.Intent(studio.junecity.greenhouse.data.AlertDestination.ACTION)
                .putExtra("greenhouse","main").putExtra("sensor",id).putExtra("time",at)
            val route = studio.junecity.greenhouse.data.AlertDestination.from(intent)!!
            assertEquals(at, route.time)
            assertEquals(id, route.sensor)
            assertEquals("main",route.greenhouse)
        } finally {
            manager.cancel(id.hashCode())
            state.edit().clear().commit()
        }
    }

    @Test fun launcherRendersTheSuppliedArtworkUnderTheDeviceMask() {
        val context = instrumentation.targetContext
        val icon = context.packageManager.getApplicationIcon(context.packageName)
        assertTrue(icon is android.graphics.drawable.AdaptiveIconDrawable)
        val bitmap = android.graphics.Bitmap.createBitmap(192,192,android.graphics.Bitmap.Config.ARGB_8888)
        icon.setBounds(0,0,192,192)
        icon.draw(android.graphics.Canvas(bitmap))
        var amber = 0
        var blue = 0
        for(y in 0 until bitmap.height) for(x in 0 until bitmap.width) {
            val pixel=bitmap.getPixel(x,y)
            if(android.graphics.Color.alpha(pixel)==0) continue
            val red=android.graphics.Color.red(pixel)
            val green=android.graphics.Color.green(pixel)
            val b=android.graphics.Color.blue(pixel)
            if(y < 80 && red > 200 && green > 80 && b < 85) amber++
            if(y > 80 && b > 200 && green > 165 && red < 160) blue++
        }
        assertTrue("Expected the amber Wi-Fi symbol",amber > 80)
        assertTrue("Expected the blue humidity symbol",blue > 30)
        java.io.File(context.cacheDir,"qa-launcher.png").outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)
        }
        bitmap.recycle()
    }

    @Suppress("DEPRECATION")
    private fun assertNavigationHidden(view: android.view.View) {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            val insets = requireNotNull(androidx.core.view.ViewCompat.getRootWindowInsets(view))
            assertFalse(insets.isVisible(androidx.core.view.WindowInsetsCompat.Type.navigationBars()))
        } else {
            // Android 10 estimates visibility from insets even when LAYOUT_STABLE keeps them reserved.
            val actual = view.windowSystemUiVisibility
            val required = android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            assertEquals("Window has the immersive and hidden navigation flags", required, actual and required)
        }
    }

    private fun screenshot(name:String) {
        val file = java.io.File(instrumentation.targetContext.cacheDir, "qa-$name.png")
        val bitmap = ui.takeScreenshot() ?: error("Screenshot unavailable")
        try { file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) } }
        finally { bitmap.recycle() }
    }

    private fun find(label:String):AccessibilityNodeInfo {
        fun match(n:AccessibilityNodeInfo):AccessibilityNodeInfo? {
            if(n.text?.toString()==label || n.contentDescription?.toString()==label) return n
            for(i in 0 until n.childCount) n.getChild(i)?.let{match(it)?.let{return it}}
            return null
        }
        repeat(60){ ui.rootInActiveWindow?.let{match(it)?.let{return it}};SystemClock.sleep(80) }
        val visible=mutableListOf<String>()
        fun collect(node:AccessibilityNodeInfo) {
            node.text?.let { visible.add(it.toString()) }
            for(i in 0 until node.childCount) node.getChild(i)?.let(::collect)
        }
        ui.rootInActiveWindow?.let(::collect)
        error("Missing $label. Visible: ${visible.joinToString()}")

    }
    private fun click(label:String){
        var node:AccessibilityNodeInfo?=find(label)
        while(node!=null && !node.isClickable)node=node.parent
        check(node?.performAction(AccessibilityNodeInfo.ACTION_CLICK)==true)
        instrumentation.waitForIdleSync()
    }
    private fun pinchAndPan(bounds: Rect) {
        val start = SystemClock.uptimeMillis()
        val fingers = Array(2) { i -> MotionEvent.PointerProperties().apply {
            id = i
            toolType = MotionEvent.TOOL_TYPE_FINGER
        } }
        fun send(action: Int, count: Int, fraction: Float) {
            val coords = Array(count) { i -> MotionEvent.PointerCoords().apply {
                x = bounds.centerX() + bounds.width() * ((if (i == 0) -1 else 1) * (.08f + .10f * fraction) + .06f * fraction)
                y = bounds.centerY() + bounds.height() * .12f * fraction
                pressure = 1f
                size = .1f
            } }
            val e = MotionEvent.obtain(start, SystemClock.uptimeMillis(), action, count, fingers, coords,
                0, 0, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, 0)
            try { check(ui.injectInputEvent(e,true)) } finally { e.recycle() }
        }
        send(MotionEvent.ACTION_DOWN, 1, 0f)
        send(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2, 0f)
        for (i in 1..8) send(MotionEvent.ACTION_MOVE, 2, i / 8f)
        send(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2, 1f)
        send(MotionEvent.ACTION_UP, 1, 1f)
        instrumentation.waitForIdleSync()
    }

    private fun gesture(bounds:Rect,points:List<Pair<Float,Float>>) {
        val start=SystemClock.uptimeMillis()
        fun send(action:Int,p:Pair<Float,Float>){
            val event=MotionEvent.obtain(start,SystemClock.uptimeMillis(),action,bounds.left+bounds.width()*p.first,bounds.top+bounds.height()*p.second,0)
            event.source=android.view.InputDevice.SOURCE_TOUCHSCREEN
            ui.injectInputEvent(event,true)
            event.recycle()
        }
        send(MotionEvent.ACTION_DOWN,points.first())
        for((a,b) in points.zipWithNext()) for(i in 1..10){
            val f=i/10f
            send(MotionEvent.ACTION_MOVE,(a.first+(b.first-a.first)*f) to (a.second+(b.second-a.second)*f))
        }
        send(MotionEvent.ACTION_UP,points.last())
        instrumentation.waitForIdleSync()
    }
}
