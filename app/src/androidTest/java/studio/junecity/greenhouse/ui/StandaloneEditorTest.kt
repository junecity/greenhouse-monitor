package studio.junecity.greenhouse.ui

import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.luminance
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
import androidx.compose.foundation.layout.width
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
import studio.junecity.greenhouse.cloud.serverHealth
import studio.junecity.greenhouse.cloud.toHealthJson
import studio.junecity.greenhouse.cloud.CloudDeviceRole
import studio.junecity.greenhouse.model.*
import studio.junecity.greenhouse.settings.*
import java.util.concurrent.atomic.AtomicReference

class StandaloneEditorTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val ui get()=instrumentation.uiAutomation

    @Test fun dashboardFanSpeedAndDirectionSurviveSaveAndRestore() {
        val store = AppPreferencesStore(instrumentation.targetContext, "_fan_fixture")
        store.clear()
        val houses = listOf(GreenhouseConfig(), GreenhouseConfig("second", "Second house"))
        store.save(AppPreferences(greenhouses = houses))
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                    activity.setContent {
                        DashboardScreen(GreenhouseUiState(standalone = true, preferences = store.load()),
                            DeviceStatus(80, true, true), java.time.LocalDateTime.now(), {}, Modifier.fillMaxSize(),
                            onSaveFans = { id, fans -> store.save(AppPreferences(greenhouses = houses.map {
                                if (it.id == id) it.copy(circulationFans = fans) else it
                            })) })
                    }
                }
                click("FAN")
                find("192 CFM")
                click("Fan 1 High")
                find("224 CFM")
                click("Edit height & direction")
                val bounds = Rect().also { find("Fan direction floor plan").getBoundsInScreen(it) }
                gesture(bounds, listOf(.50f to .50f))
                find("Tap where the fan blows or enter a direction.")
                gesture(bounds, listOf(.65f to .50f))
                click("Apply")
                find("5 ft high · 90°")
                click("Edit height & direction")
                enterFanField("Fan height in feet", "99")
                find("Use 0–8 ft for this section.")
                enterFanField("Fan height in feet", "6.5")
                enterFanField("Fan direction in degrees", "999")
                find("Enter 0–360°.")
                enterFanField("Fan direction in degrees", "225")
                click("Apply")
                find("6.5 ft high · 225°")
                click("Save setup")
                find("FAN")
            }
            val json = store.exportJson()
            store.clear()
            store.importJson(json)
            val restored = store.load().greenhouses
            assertEquals(FanSpeed.High, restored.first().circulationFans[0].speed)
            assertEquals(225.0, restored.first().circulationFans[0].aim!!.degrees, 0.0)
            assertEquals(6.5, restored.first().airCirculation().placed.first().heightFeet, 0.0)
            assertEquals(CirculationFan(), restored.first().circulationFans[1])
            assertEquals(houses[1], restored[1])
            json.getJSONArray("greenhouses").getJSONObject(0).getJSONArray("circulationFans").getJSONObject(0).remove("heightFeet")
            assertEquals(5.0, store.decode(json).activeGreenhouse.circulationFans[0].heightFeet, 0.0)
            json.getJSONArray("greenhouses").getJSONObject(0).remove("circulationFans")
            store.importJson(json)
            assertEquals(houses, store.load().greenhouses)
        } finally { store.clear() }
    }

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
            assertEquals(studio.junecity.greenhouse.R.drawable.ic_notification_greenhouse,
                manager.activeNotifications.single { it.tag == tag }.notification.smallIcon.resId)
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
        val live = GoveeSensor("bench","H5179","Bench",72.0,61.0,true,at,second.id,"Zone 1", batteryPercent=79, batteryObservedAt=at)
        val old = live.copy(id="shelly:door",sku="SBHT-103C",name="Door",batteryPercent=10,batteryObservedAt=null,online=false,updatedAtEpochMs=at-900_000)
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
                find("Battery · 79%")
                find("Battery · 10% · Low")
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
                first.applyQuickStart(studio.junecity.greenhouse.cloud.QuickStartData("06382", 18.0, 9.0, 7.5,
                    studio.junecity.greenhouse.data.EnergyUnit.Kwh, "example-test-key"))
            }
            repeat(50){if(first.state.value.sensors.isEmpty())SystemClock.sleep(80)}
            assertEquals(72.0,first.state.value.sensors.single().temperatureF!!,0.0)
            assertEquals(61.0,first.state.value.sensors.single().humidityPercent!!,0.0)
            assertTrue(secure.cloudEnrollment()!!.localSensors)
            instrumentation.runOnMainSync {
                first.viewModelScope.cancel()
                reopened=GreenhouseViewModel(context.applicationContext as Application,provider)
                assertEquals(18.0,reopened.state.value.preferences.activeGreenhouse.lengthFeet,0.0)
                assertEquals(7.5,reopened.state.value.preferences.activeGreenhouse.heightFeet!!,0.0)
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

    @Test fun addShellyDisplayFromDevicesAndRetainTheLightReading() {
        val context = instrumentation.targetContext
        val secure = SecureSettingsStore(context)
        assumeTrue("Isolated emulator only", android.os.Build.MODEL.contains("sdk", true) && secure.cloudEnrollment() == null)
        val store = CurrentSensorStore(context, "_standalone")
        val previous = store.load()
        val id = "shelly:F0:00:00:00:00:01"
        try {
            secure.saveCloudEnrollment(CloudEnrollment("test-shelly", "", CloudDeviceRole.ViewerClient))
            store.clear()
            val changed = java.util.concurrent.atomic.AtomicBoolean()
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.setContent {
                        Column {
                            ShellyConnections("bench", "West house", { changed.set(true) }) {
                                kotlinx.coroutines.flow.flowOf(ShellyAdvertisement(id, ShellyModel.Display,
                                    ShellyBeacon(65.0, 82.0, "Bright", null, false), System.currentTimeMillis()))
                            }
                        }
                    }
                }
                click("Add Shelly Bluetooth sensors")
                find("Add to West house")
                click("Add sensor")
                find("Added")
                click("Enter address")
                var field: AccessibilityNodeInfo? = find("Bluetooth address")
                while (field != null && !field.isEditable) field = field.parent
                check(field?.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, android.os.Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "f00000000002")
                }) == true)
                find("f00000000002")
                instrumentation.waitForIdleSync()
                click("Save sensor")
                find("Sensor saved. Waiting for its first reading.")
                click("Done")
                assertTrue(changed.get())
                assertTrue(secure.cloudEnrollment()!!.localSensors)
                val sensor = store.load().first { it.id == id }
                assertEquals("bench", sensor.greenhouseId)
                assertEquals("Bright", sensor.lightLevel)
                val pending = store.load().single { it.id != id }
                assertEquals("shelly:F0:00:00:00:00:02", pending.id)
                assertEquals(ShellyModel.Display.sku, pending.sku)
                assertFalse(pending.isFresh())
                val packet = ShellyAdvertisement(pending.id, ShellyModel.Display,
                    ShellyBeacon(66.0, 78.0, "Twilight", null, false), System.currentTimeMillis())
                store.applyShellyPackets(mapOf(pending.id to packet)) {}
                assertEquals("Twilight", store.load().single { it.id == pending.id }.lightLevel)
            }
        } finally {
            secure.clearCloudEnrollment()
            store.save(previous)
        }
    }

    @Test fun editRemoveAndAddBackFromDevices() {
        val context = instrumentation.targetContext
        val secure = SecureSettingsStore(context)
        assumeTrue("Isolated emulator only", android.os.Build.MODEL.contains("sdk", true) && secure.cloudEnrollment() == null)
        val owner = CloudEnrollment("fixture-edit", "", CloudDeviceRole.ViewerClient, localSensors = true)
        val store = CurrentSensorStore(context, "_standalone")
        val original = org.json.JSONObject().also { store.writeBackup(it) }
        val sensor = GoveeSensor("shelly:test", ShellyModel.Display.sku, "Shelly display", greenhouseId = "main")
        try {
            secure.saveCloudEnrollment(owner)
            store.clear()
            store.enroll(sensor)
            val state = androidx.compose.runtime.mutableStateOf(GreenhouseUiState(standalone = true, goveeConnected = true, sensors = store.load()))
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    activity.setContent {
                        DevicesSettings(state.value, { it.dp }, { it.sp }, { _, _ -> }, {}, {},
                            { state.value = state.value.copy(sensors = store.load()) })
                    }
                }
                click("Edit")
                var field: AccessibilityNodeInfo? = find("Sensor label")
                while (field != null && !field.isEditable) field = field.parent
                check(field?.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, android.os.Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "Lower bench")
                }) == true)
                find("Lower bench")
                click("Save label")
                find("Lower bench")
                click("Edit")
                click("Remove sensor")
                find("Remove Lower bench?")
                click("Cancel")
                assertEquals("Lower bench", store.load().single().name)
                click("Edit")
                click("Remove sensor")
                click("Remove")
                click("Add a removed sensor (1)")
                click("Add back")
                find("Edit")
                assertEquals(sensor.copy(name = "Lower bench"), store.load().single())
                assertTrue(store.removed().isEmpty())
                click("Edit")
                click("Permanently remove")
                find("Permanently remove Lower bench?")
                click("Cancel")
                assertEquals("Lower bench", store.load().single().name)
                click("Edit")
                click("Permanently remove")
                find("Permanently remove Lower bench?")
                click("Permanently remove")
                assertTrue(store.load().isEmpty())
                assertTrue(store.removed().isEmpty())
                store.enroll(sensor)
                assertEquals(listOf(sensor), store.load())
            }
        } finally {
            secure.clearCloudEnrollment()
            store.restore(CurrentSensorCodec.decode(original.getJSONArray("sensors")),
                original.getJSONArray("removedSensors"), original.getJSONObject("sensorLabels"), original.optJSONArray("forgottenGovee"))
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
                                var start = marker()
                                val layoutDeadline = SystemClock.elapsedRealtime() + 3000
                                while (SystemClock.elapsedRealtime() < layoutDeadline) {
                                    SystemClock.sleep(120)
                                    val next = marker()
                                    if (next == start && !next.isEmpty && context.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) break
                                    start = next
                                }
                                val distance = 3 * start.width()
                                val path = Rect(start.centerX(), start.centerY(), start.centerX() + distance, start.centerY() + 1)
                                gesture(path, listOf(0f to 0f, 1f to 0f))
                                val writeDeadline = SystemClock.elapsedRealtime() + 3000
                                while (position().xFeet <= previous.xFeet + .1 && SystemClock.elapsedRealtime() < writeDeadline) {
                                    SystemClock.sleep(50)
                                }
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
            click("Lower")
            find("About 2 ft above the floor")
            click("Save position")
            click("Save layout")
            instrumentation.waitForIdleSync()
            val result=requireNotNull(saved.get())
            assertEquals(GreenhouseShape.Drawn,result.shape)
            assertEquals("Traced vertices: ${result.vertices}",3,result.vertices.size)
            val position=requireNotNull(result.sensorPositions["sensor1"])
            assertTrue(result.containsPoint(position.xFeet,position.yFeet))
            assertEquals(MountLevel.Lower, position.level)
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
        val unrelated="fixture-other-notice".hashCode()
        fun post(id:Int,title:String) = manager.notify(id, android.app.Notification.Builder(context,channel)
            .setSmallIcon(android.R.drawable.ic_dialog_alert).setContentTitle(title).build())
        fun awaitIds(expected:Set<Int>) {
            repeat(30) {
                if(manager.activeNotifications.map { it.id }.toSet().intersect(setOf(old,current,heat,unrelated))==expected) return
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
            studio.junecity.greenhouse.cloud.clearRecoveredCloudAlerts(manager, studio.junecity.greenhouse.cloud.AlertInbox(listOf(active), true))
            awaitIds(setOf(current,heat))
            studio.junecity.greenhouse.cloud.clearRecoveredCloudAlerts(manager, studio.junecity.greenhouse.cloud.AlertInbox(emptyList(), true))
            awaitIds(setOf(heat))
            post(unrelated,"Other notification")
            val incomplete = studio.junecity.greenhouse.cloud.AlertInbox(emptyList(), false, criticalIncidentsVerified = true)
            studio.junecity.greenhouse.cloud.clearRecoveredCloudAlerts(manager, incomplete)
            awaitIds(setOf(heat, unrelated))
            studio.junecity.greenhouse.cloud.clearRecoveredCloudAlerts(manager, incomplete.copy(complete = true))
            awaitIds(setOf(unrelated))
        } finally { listOf(old,current,heat,unrelated).forEach(manager::cancel) }
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

    @Test fun monthHistoryMigratesAndSurvivesBoundedViewerSyncAndRestart() {
        val context = instrumentation.targetContext
        val collector = "_history_month_source"
        val viewer = "_history_month_viewer"
        val dbName = "greenhouse_history$collector.db"
        val now = System.currentTimeMillis()
        val oldDay = now / DAY_MS - 145
        val early = HistoryReading("bench", "Bench", 55.0, 81.0, oldDay * DAY_MS + 2000)
        val late = early.copy(temperatureF = 62.0, timestampEpochMs = early.timestampEpochMs + 60_000, lightLevel = "Bright")
        try {
            context.deleteDatabase(dbName)
            context.deleteDatabase("greenhouse_history$viewer.db")
            // Start with the exact old schema and an already-recorded day.
            context.openOrCreateDatabase(dbName, 0, null).use { db ->
                db.execSQL("CREATE TABLE readings(id INTEGER PRIMARY KEY AUTOINCREMENT,sensor_id TEXT NOT NULL,sensor_name TEXT NOT NULL,temperature REAL NOT NULL,humidity REAL NOT NULL,recorded_at INTEGER NOT NULL)")
                db.execSQL("CREATE INDEX readings_time ON readings(recorded_at DESC)")
                db.execSQL("CREATE UNIQUE INDEX readings_sensor_time ON readings(sensor_id,recorded_at)")
                db.execSQL("INSERT INTO readings VALUES(NULL,?,?,?,?,?)", arrayOf<Any>(early.sensorId, early.sensorName, early.temperatureF, early.humidityPercent, early.timestampEpochMs))
                db.version = 2
            }
            studio.junecity.greenhouse.data.HistoryStore(context, collector).use { db ->
                assertEquals(listOf(early), db.dailyReadings())
                val recent = (0..1600).map { early.copy(timestampEpochMs = now - it) }
                db.importReadings(recent + late + early.copy(timestampEpochMs = now - 181 * DAY_MS), merge = true)
                db.insert(GoveeSensor("now","H5179","Current",65.0,80.0,true,now))
                assertEquals(listOf(late,early), db.dayReadings(oldDay))
                assertTrue(db.dayReadings(now / DAY_MS - 181).isEmpty())
                assertFalse(early in db.weekReadings())
                assertTrue(late in db.dailyReadings())
            }
            val payload = studio.junecity.greenhouse.cloud.GreenhouseBackupStore(context, collector).snapshot()
            assertEquals(1500, payload.getJSONArray("history").length())
            val destination = studio.junecity.greenhouse.cloud.GreenhouseBackupStore(context, viewer)
            val historyOnly = org.json.JSONObject().put("schema",2).put("history",payload.getJSONArray("history"))
                .put("dailyLast",payload.getJSONArray("dailyLast"))
            destination.restore(historyOnly)
            destination.restore(historyOnly)
            destination.restore(org.json.JSONObject().put("schema",2).put("history",org.json.JSONArray()))
            studio.junecity.greenhouse.data.HistoryStore(context, viewer).use { db ->
                assertEquals(listOf(late),db.dayReadings(oldDay))
                assertTrue(late in db.dailyReadings())
            }
        } finally {
            context.deleteDatabase(dbName)
            context.deleteDatabase("greenhouse_history$viewer.db")
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

    @Test fun flatMapUsesFixedColorsAndLegendForEachMetric() {
        val plan = GreenhouseConfig(circulationFans = listOf(
            CirculationFan(FanSpeed.High, FanAim(NormalizedPosition(.25, .25), 90.0)),
            CirculationFan(FanSpeed.Low, FanAim(NormalizedPosition(.75, .75), 270.0))))
        val samples = listOf(studio.junecity.greenhouse.data.SpatialSample(8.0, 4.0, 65.0))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val metric = androidx.compose.runtime.mutableStateOf(Metric.Temperature)
            scenario.onActivity { activity ->
                activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                activity.setContent {
                    HeatMap(samples, metric.value, plan, emptyList(), { it.dp }, { it.sp },
                        { _, _, _ -> }, Modifier.fillMaxSize(), showFans = true)
                }
            }
            SystemClock.sleep(750)
            val plot = Rect().also {
                find("Fan 1 High, 90 degrees from plan top; Fan 2 Low, 270 degrees from plan top").getBoundsInScreen(it)
            }
            screenshot("fans-floor-plan")
            val frame = requireNotNull(ui.takeScreenshot())
            try {
                for ((location, color) in listOf(.25 to DashboardTokens.Amber, .75 to DashboardTokens.Blue)) {
                    val x = plot.left + (plot.width() * location).toInt()
                    val y = plot.top + (plot.height() * location).toInt()
                    assertTrue("Fan hub at ($x,$y), bounds $plot, pixel ${Integer.toHexString(frame.getPixel(x,y))}", (-2..2).any { dx ->
                        (-2..2).any { dy -> frame.getPixel(x + dx, y + dy) == color.toArgb() }
                    })
                }
            } finally { frame.recycle() }
            for ((mode, low, high) in listOf(
                Triple(Metric.Temperature, "40°F", "90°F"), Triple(Metric.Humidity, "35%", "100%"),
                Triple(Metric.DewPoint, "40°F", "90°F"), Triple(Metric.Vpd, "0 kPa", "3 kPa")
            )) {
                scenario.onActivity { metric.value = mode }
                find(low)
                find(high)
                find("· Estimated nearby")
            }
        }
        val heat = ZoneHeatScale.Temperature
        assertEquals(androidx.compose.ui.graphics.Color(0xffffb446), heat.color(70.0))
        for (degrees in 41..90) {
            assertTrue("Brightness must increase at ${degrees}°F",
                heat.color(degrees.toDouble()).luminance() > heat.color(degrees - 1.0).luminance())
        }
        assertTrue(heat.color(71.0).luminance() > 2 * heat.color(60.0).luminance())
        assertEquals(heat.color(40.0), heat.color(30.0))
        assertEquals(heat.color(90.0), heat.color(100.0))
        assertNotEquals(heat.color(65.0), ZoneHeatScale.Humidity.color(65.0))
    }

    @Test fun volumeEditorUsesAttachedHeightAndPreservesFanLocation() {
        val position = NormalizedPosition(.5, .8)
        val fan = CirculationFan(FanSpeed.Low, FanAim(position, 20.0))
        val house = GreenhouseConfig(shape = GreenhouseShape.TShape, heightFeet = 7.0, attachedHeightFeet = 12.0,
            circulationFans = listOf(CirculationFan(), fan))
        val prefs = androidx.compose.runtime.mutableStateOf(AppPreferences(greenhouses = listOf(house)))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                activity.setContent {
                    ZoneVolumeScreen(GreenhouseUiState(standalone = true, preferences = prefs.value),
                        { update -> prefs.value = update(prefs.value) }, {})
                }
            }
            click("Fans")
            click("Edit Fan 2")
            enterFanField("Fan height in feet", "13")
            find("Use 0–12 ft for this section.")
            enterFanField("Fan height in feet", "9.5")
            enterFanField("Fan direction in degrees", "360")
            click("Apply")
            click("Fans")
            find("Fan 2 · Low · 0° · 9.5 ft high")
            assertEquals(listOf(CirculationFan(), fan.copy(aim = FanAim(position, 0.0), heightFeet = 9.5)),
                prefs.value.activeGreenhouse.circulationFans)
        }
    }

    @Test fun volumeAnimatesSavedFansAndStopsBothWhenOff() {
        val aim1 = FanAim(NormalizedPosition(.50, .20), 180.0)
        val aim2 = FanAim(NormalizedPosition(.40, .80), 0.0)
        val plan = GreenhouseConfig(shape = GreenhouseShape.TShape, circulationFans =
            listOf(CirculationFan(FanSpeed.High, aim1), CirculationFan(FanSpeed.Low, aim2)))
        val scenarioPlan = androidx.compose.runtime.mutableStateOf(plan)
        val prefs = AppPreferences(cameraMode = CameraMode.ViewerClient)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { a ->
                a.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                a.setContent { ZoneVolumeScreen(GreenhouseUiState(preferences = prefs.copy(greenhouses = listOf(scenarioPlan.value))),
                    { error("Viewer cannot edit") }, {}) }
            }
            val label = "Fan 1 High, 180 degrees from plan top; Fan 2 Low, 0 degrees from plan top"
            val bounds = Rect().also { find(label).getBoundsInScreen(it) }
            fun changedPixels(): Int {
                val first = requireNotNull(ui.takeScreenshot())
                SystemClock.sleep(275)
                val second = requireNotNull(ui.takeScreenshot())
                return try {
                    var changed = 0
                    for (y in bounds.top until bounds.bottom step 2) for (x in bounds.left until bounds.right step 2)
                        if (first.getPixel(x, y) != second.getPixel(x, y)) changed++
                    changed
                } finally { first.recycle(); second.recycle() }
            }
            SystemClock.sleep(1000)
            val motionEnabled = android.provider.Settings.Global.getFloat(instrumentation.targetContext.contentResolver,
                android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
            if (motionEnabled) assertTrue("Moving blades and traveling arrows", changedPixels() > 12)
            screenshot("3d-circulation")
            click("Fans")
            find("Fan 1 · High · 180° · 5 ft high")
            click("Hide fans")
            click("Fans")
            click("Show fans")
            find(label)
            scenario.onActivity { scenarioPlan.value = plan.copy(circulationFans =
                plan.circulationFans.map { it.copy(speed = FanSpeed.Off) }) }
            find("Fan 1 Off, 180 degrees from plan top; Fan 2 Off, 0 degrees from plan top")
            SystemClock.sleep(700)
            assertEquals("Off fans stay still", 0, changedPixels())
        }
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

    @Test fun placeEnrolledSensorOn3dFloorAndPreserveMount() {
        val store = AppPreferencesStore(instrumentation.targetContext, "_3d_placement_fixture")
        val other = SensorPosition(3.0, 2.0, 1.0)
        val house = GreenhouseConfig(shape = GreenhouseShape.TShape, heightFeet = 8.0, attachedHeightFeet = 12.0,
            sensorPositions = mapOf("other" to other))
        val sensor = GoveeSensor("new", "Shelly", "Stem probe")
        try {
            store.save(AppPreferences(greenhouses = listOf(house)))
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    activity.setContent {
                        val prefs = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(store.load()) }
                        ZoneVolumeScreen(GreenhouseUiState(standalone = true, sensors = listOf(sensor), preferences = prefs.value),
                            { save -> prefs.value = save(prefs.value).also(store::save) }, {})
                    }
                }
                click("Place sensor")
                val bounds = Rect().also { find("3D sensor placement floor").getBoundsInScreen(it) }
                val density = instrumentation.targetContext.resources.displayMetrics.density
                val camera = Projection(house, 12.0, androidx.compose.ui.unit.IntSize(bounds.width(), bounds.height()),
                    -.56, .44, 1f, androidx.compose.ui.geometry.Offset.Zero, 12 * density)
                fun at(x: Double, y: Double, z: Double = 0.0) = camera.point(x, y, z).let {
                    it.x / bounds.width() to it.y / bounds.height()
                }
                gesture(bounds, listOf(.1f to .03f, at(2.0, 18.0)))
                find("Drop inside the greenhouse floor")
                assertFalse(store.load().activeGreenhouse.sensorPositions.containsKey(sensor.id))
                gesture(bounds, listOf(.1f to .03f, at(8.0, 18.0)))
                click("Upper")
                click("Save position")
                find("Place sensor")
                val saved = store.load().activeGreenhouse
                val point = saved.sensorPositions.getValue(sensor.id)
                assertEquals(8.0, point.xFeet, .15)
                assertEquals(18.0, point.yFeet, .15)
                assertEquals(MountLevel.Upper, point.level)
                assertEquals(9.0, studio.junecity.greenhouse.data.ZoneClimate.mountingHeight(saved, point)!!, .01)
                assertEquals(other, saved.sensorPositions["other"])
                click("Place sensor")
                gesture(bounds, listOf(at(8.0, 18.0, 9.0), at(7.0, 12.0, 9.0)))
                click("Cancel")
                assertEquals(saved, store.load().activeGreenhouse)
            }
        } finally { store.clear() }
    }

    @Test fun dragChooseUpperAndRefineWithOneDistance() {
        val store = AppPreferencesStore(instrumentation.targetContext, "_drag_mount_fixture")
        val sensor = GoveeSensor("fixture", "H5179", "Stem probe")
        val house = GreenhouseConfig(shape=GreenhouseShape.TShape, heightFeet=8.0, attachedHeightFeet=12.0,
            sensorPositions=mapOf(sensor.id to SensorPosition(8.0, 4.0, 3.5)))
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.requestedOrientation=android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    activity.setContent {
                        SensorPositionDialog(house, sensor, { error("Dismissed") }) { pos ->
                            store.save(AppPreferences(greenhouses=listOf(house.copy(sensorPositions=mapOf(sensor.id to pos)))))
                        }
                    }
                }
                find("Measured height · 3.5 ft")
                val map = Rect().also { find("Sensor placement map").getBoundsInScreen(it) }
                gesture(map, listOf(.5f to .31f, .5f to .77f))
                click("Upper")
                fun input(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
                    if (node.isEditable) return node
                    for (index in 0 until node.childCount) node.getChild(index)?.let { input(it) }?.let { return it }
                    return null
                }
                val field = requireNotNull(input(ui.rootInActiveWindow))
                check(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, android.os.Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "2.5")
                }))
                find("2.5")
                click("Save position")
                val restored = store.decode(store.exportJson()).activeGreenhouse
                val pos = restored.sensorPositions.getValue(sensor.id)
                assertTrue(pos.yFeet > 8.0)
                assertEquals(2.5, pos.floorDistance(requireNotNull(restored.placementReference(pos))), .001)
                assertEquals(MountLevel.Upper, pos.level)
                assertNull(pos.heightFeet)
                assertEquals(9.0, studio.junecity.greenhouse.data.ZoneClimate.mountingHeight(restored, pos)!!, .001)
            }
        } finally { store.clear() }
    }

    @Test fun sectionMeasurementsSurviveEditingAndSnapshotDecode() {
        val store = AppPreferencesStore(instrumentation.targetContext, "_section_heights_fixture")
        val sensor = GoveeSensor("probe", "H5179", "Bench sensor")
        val house = GreenhouseConfig(shape=GreenhouseShape.TShape, heightFeet=8.0, attachedHeightFeet=10.0,
            sensorPositions=mapOf(sensor.id to SensorPosition(7.0, 12.0, 2.0)))
        fun edit(old: String, value: String) {
            check(find(old).performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, android.os.Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
            }))
            find(value)
        }
        try {
            store.save(AppPreferences(greenhouses=listOf(house)))
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { a ->
                    a.requestedOrientation=android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    a.setContent {
                        val stage=androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(0) }
                        val g=store.load().activeGreenhouse
                        when(stage.intValue) {
                            0 -> HeightDialog(g,null,{error("Dismissed")}) { main, stem, _ ->
                                store.save(AppPreferences(greenhouses=listOf(g.copy(heightFeet=main, attachedHeightFeet=stem))))
                                stage.intValue=1
                            }
                            1 -> SensorPositionDialog(g,sensor,{error("Dismissed")}) { point ->
                                store.save(AppPreferences(greenhouses=listOf(g.copy(sensorPositions=mapOf(sensor.id to point)))))
                                stage.intValue=2
                            }
                            else -> androidx.compose.material3.Text("Measurements saved")
                        }
                    }
                }
                edit("10.0", "12.5")
                click("Save")
                find("Bench sensor")
                click("Exact measurements")
                edit("2", "4.5")
                click("Show all corner distances")
                find("Numbered greenhouse corners, viewed from above. The sensor is marked in blue.")
                click("Use two corner distances")
                click("Save position")
                find("Measurements saved")
                val restored = store.decode(store.exportJson()).activeGreenhouse
                assertEquals(8.0, restored.heightFeet!!, .00001)
                assertEquals(12.5, restored.attachedHeightFeet!!, .00001)
                val pos=restored.sensorPositions.getValue(sensor.id)
                assertEquals(7.0,pos.xFeet,.00001)
                assertEquals(12.0,pos.yFeet,.00001)
                assertEquals(4.5,pos.heightFeet!!,.00001)
            }
        } finally { store.clear() }
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
                    val deadline = SystemClock.uptimeMillis() + 3000
                    while (find(view).stateDescription?.toString() == "Zoom 100%" && SystemClock.uptimeMillis() < deadline) {
                        SystemClock.sleep(50)
                    }
                    assertNotEquals("Zoom 100%", find(view).stateDescription?.toString())
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

    @Test fun compactZoneToolsRemainAccessible() {
        val chosen = AtomicReference("")
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    androidx.compose.foundation.layout.Box(Modifier.width(160.dp)) {
                        ZoneTools({ chosen.set("3D view") }, { chosen.set("Compare") }, { chosen.set("Replay") })
                    }
                }
            }
            for (action in listOf("3D view", "Compare", "Replay")) {
                click("Zone tools ▾")
                click(action)
                assertEquals(action, chosen.get())
            }
        }
    }

    @Test fun settingsUpdatesOnEmulatedCarrierHandoff() {
        assumeTrue(android.os.Build.FINGERPRINT.contains("generic") || android.os.Build.MODEL.contains("sdk_gphone"))
        val provider = studio.junecity.greenhouse.data.AndroidDeviceStatusProvider(instrumentation.targetContext)
        assumeTrue(provider.current().wifiConnected)
        val state = GreenhouseUiState(preferences = AppPreferences(cameraMode = CameraMode.ViewerClient))
        fun wifi(enabled: Boolean) {
            val command = if (enabled) "svc wifi enable" else "svc wifi disable"
            android.os.ParcelFileDescriptor.AutoCloseInputStream(ui.executeShellCommand(command)).use { it.readBytes() }
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                activity.setContent {
                    val status = androidx.compose.runtime.produceState(provider.current()) {
                        provider.networkChanges().collect { value = provider.current() }
                    }
                    ApprovedFrame(AppScreen.Settings, status.value, java.time.LocalDateTime.now(), {},
                        Modifier.fillMaxSize(), state = state) { _, _ -> }
                }
            }
            try {
                find("Wi-Fi")
                wifi(false)
                find("Cellular")
            } finally {
                wifi(true)
            }
            find("Wi-Fi")
        }
    }

    @Test fun settingsSeparatesPhoneTransportFromServerHeartbeat() {
        val state = GreenhouseUiState(preferences = AppPreferences(cameraMode = CameraMode.ViewerClient))
        val local = androidx.compose.runtime.mutableStateOf(DeviceStatus(90, false, true, "Wi-Fi"))
        val server = androidx.compose.runtime.mutableStateOf(studio.junecity.greenhouse.cloud.RegisteredDevice(
            "test-server", "tenant", CloudDeviceRole.GreenhouseServer, online = true,
            lastSeenAtEpochMs = System.currentTimeMillis()))
        val verified = androidx.compose.runtime.mutableStateOf(true)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                activity.setContent {
                    ApprovedFrame(AppScreen.Settings, local.value, java.time.LocalDateTime.now(), {},
                        Modifier.fillMaxSize(), state = state) { _, text ->
                        Column { studio.junecity.greenhouse.cloud.ServerConnectionStatus(server.value, verified.value, text) }
                    }
                }
            }
            find("This phone")
            find("Wi-Fi")
            find("Greenhouse server online")
            scenario.onActivity { local.value = DeviceStatus(90, false, false, "Cellular") }
            find("Cellular")
            find("Greenhouse server online")
            scenario.onActivity { server.value = server.value.copy(online = false) }
            find("Greenhouse server offline")
            find("Cellular")
            scenario.onActivity { server.value = server.value.copy(online = true); verified.value = false }
            find("Server status unavailable")
            scenario.onActivity { server.value = server.value.copy(online = false, lastSeenAtEpochMs = null); verified.value = true }
            find("Waiting for first heartbeat")
        }
    }

    @Test fun settingsReadsVersionFromSyncedHouseWhenInventoryOmitsIt() {
        val item = studio.junecity.greenhouse.cloud.CloudJsonCodec.accountDevice(org.json.JSONObject()
            .put("id", "server").put("role", "GREENHOUSE_SERVER").put("greenhouseId", "house")
            .put("createdAtEpochMs", 1000).put("lastSeenAtEpochMs", 3000).put("online", true))
        val client = studio.junecity.greenhouse.BuildConfig.VERSION_NAME
        val server = DeviceStatus(50, true, true, reportedAt = 2000, appVersion = client)
        val payload = org.json.JSONObject().put("serverHealth", server.toHealthJson())
        val status = androidx.compose.runtime.mutableStateOf(payload.serverHealth("house"))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    studio.junecity.greenhouse.cloud.ServerVersionRow(item, status.value, { it.dp }, { it.sp }) {}
                }
            }
            find("Client $client  ·  Last greenhouse report $client\nVersions match")
            scenario.onActivity {
                payload.put("serverHealth", server.copy(sourceDeviceId = "server").toHealthJson())
                status.value = payload.serverHealth("house")
            }
            find("Client $client  ·  Server $client\nVersions match")
            scenario.onActivity { status.value = payload.serverHealth("different-house") }
            find("Client $client  ·  Server —\nCheck the version in Settings → General on the greenhouse phone.")
        }
    }

    @Test fun connectivityOffersPublishedUpdateThenReportsServerProgress() {
        val client = studio.junecity.greenhouse.BuildConfig.VERSION_NAME
        val installed = studio.junecity.greenhouse.BuildConfig.VERSION_CODE
        val target = installed + 3
        val server = studio.junecity.greenhouse.cloud.CloudJsonCodec.accountDevice(org.json.JSONObject()
            .put("id", "test-server").put("role", "GREENHOUSE_SERVER").put("greenhouseId", "house")
            .put("createdAtEpochMs", 1000).put("appVersion", client).put("appVersionCode", installed)
            .put("managedUpdates", true).put("latestReleaseCode", target).put("latestReleaseName", "3.0.0"))
        val observed = androidx.compose.runtime.mutableStateOf(server)
        val requests = mutableListOf<Int>()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    Column {
                        studio.junecity.greenhouse.cloud.ServerVersionRow(observed.value, null, { it.dp }, { it.sp }) { code ->
                            requests.add(code)
                            observed.value = server.copy(device = server.device.copy(requestedUpdateCode = code))
                        }
                    }
                }
            }
            click("Update server")
            find("Client $client  ·  Server $client\nBuild $target · Update queued for the server’s next check-in.")
            scenario.onActivity {
                assertEquals(listOf(target), requests)
                observed.value = server.copy(device = server.device.copy(requestedUpdateCode = target, updateStatus = "INSTALLING"))
            }
            find("Client $client  ·  Server $client\nBuild $target · Installing the update. Monitoring will restart.")
            scenario.onActivity { observed.value = server.copy(device = server.device.copy(appVersion = "3.0.0", appVersionCode = target)) }
            find("Client $client  ·  Server 3.0.0\nServer is up to date.")
            scenario.onActivity { observed.value = server.copy(device = server.device.copy(managedUpdates = false)) }
            find("Send install reminder")
            scenario.onActivity { observed.value = server.copy(device = server.device.copy(managedUpdates = false, updateMode = "CONFIRM_ON_SERVER")) }
            click("Update server")
            scenario.onActivity {
                assertEquals(listOf(target, target), requests)
                observed.value = server.copy(device = server.device.copy(managedUpdates = false, updateMode = "CONFIRM_ON_SERVER",
                    requestedUpdateCode = target, requestedUpdateMode = "CONFIRM_ON_SERVER", updateStatus = "APPROVAL_REQUIRED"))
            }
            find("Client $client  ·  Server $client\nBuild $target · Approve the update on the greenhouse phone in Settings → Connectivity.")
        }
    }

    @Test fun directlyInstalledServerEnablesUpdatesFromAndroidSettingsAndCanTurnThemOff() {
        val context = instrumentation.targetContext
        val secure = SecureSettingsStore(context)
        val updates = studio.junecity.greenhouse.cloud.ManagedServerUpdate
        assumeTrue("Requires an isolated direct-download installation", secure.cloudEnrollment() == null &&
            updates.canEnableDirectUpdates(context) && !context.packageManager.canRequestPackageInstalls())
        val server = CloudEnrollment("ui-server", "ui-house", CloudDeviceRole.GreenhouseServer, "ui-test-secret")
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    secure.saveCloudEnrollment(server)
                    activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    activity.setContent {
                        StageScreen(GreenhouseUiState(screen = AppScreen.Settings), DeviceStatus(80,true,true),
                            java.time.LocalDateTime.now(),{},{},{},{},{_,_,_->},{_,_->},{},{},{_,_,_->},{},Modifier.fillMaxSize())
                    }
                }
                click("General")
                click("Connectivity")
                click("Allow remote updates")
                click("Allow from this source")
                assertTrue(ui.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK))
                find("Turn off remote updates")
                assertTrue(context.packageManager.canRequestPackageInstalls())
                assertTrue(updates.mode(context, server) in listOf("SELF_UPDATE", "CONFIRM_ON_SERVER"))
                click("Turn off remote updates")
                find("Allow remote updates")
                assertEquals("MANUAL", updates.mode(context, server))
            }
        } finally {
            updates.setDirectUpdatesEnabled(context, server, false)
            secure.clearCloudEnrollment()
        }
    }

    @Test fun serverReportAndSensorAssignmentsSurviveSync() {
        val context = instrumentation.targetContext
        val store = CurrentSensorStore(context, "_server_health_fixture")
        val viewer = CurrentSensorStore(context, "_viewer_health_fixture")
        val reportedAt = System.currentTimeMillis()
        val server = DeviceStatus(42, true, true, "Wi-Fi", -67, reportedAt, "2.32.1", 72)
        val ids = listOf("d", "a", "b", "c", "g")
        val sensors = ids.map { GoveeSensor(it, "H5179", "Sensor $it", 70.0, 75.0, true, reportedAt) }
        try {
            store.clear()
            store.save(sensors)
            val original = store.colorSlots()
            assertEquals(5, original.values.map(::sensorSlotColor).distinct().size)
            store.save(sensors.reversed() + GoveeSensor("j", "H5179", "New"))
            val payload = org.json.JSONObject().put("serverHealth", server.toHealthJson())
            store.writeBackup(payload)
            viewer.restore(CurrentSensorCodec.decode(payload.getJSONArray("sensors")), null, null,
                slots = payload.getJSONObject("sensorColorSlots"))
            assertEquals(store.colorSlots(), viewer.colorSlots())
            assertTrue(original.all { (id, slot) -> viewer.colorSlots()[id] == slot })
            assertEquals(server, payload.serverHealth())
            assertNull(org.json.JSONObject().serverHealth())
            val state = GreenhouseUiState(sensors = viewer.load(), sensorColorSlots = viewer.colorSlots(),
                serverStatus = payload.serverHealth(), preferences = AppPreferences(cameraMode = CameraMode.ViewerClient))
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                    activity.setContent {
                        DashboardScreen(state, DeviceStatus(99,false,false,"Cellular",-100),java.time.LocalDateTime.now(),{})
                    }
                }
                find("42%")
                find("Version mismatch")
                find("Wi-Fi -67 dBm")
                screenshot("server-battery-signal")
                click("Server · Battery 42% charging · Wi-Fi · -67 dBm")
                find("Battery · 42% charging")
                find("Signal · -67 dBm")
            }
        } finally { store.clear(); viewer.clear() }
    }

    @Test fun temperatureHistoryOpensShellyLightTransition() {
        val now = java.time.ZonedDateTime.now()
        val morning = now.withHour(7).withMinute(0).withSecond(0).withNano(0)
        val evening = now.withHour(18).withMinute(0).withSecond(0).withNano(0)
        val transition = listOf(morning.minusDays(1), evening.minusDays(1), morning, evening)
            .last { it.isBefore(now.minusHours(1)) }
        val brightening = transition.hour == 7
        val whenMs = transition.toInstant().toEpochMilli()
        val sensor = GoveeSensor("shelly:test", "SBHT-103C", "Display bench", 62.0, 70.0, true, whenMs)
        val history = (-10..60 step 5).map { minute ->
            val bright = (minute >= 0) == brightening
            HistoryReading(sensor.id, sensor.name, 60.0 + minute / 10.0, 70.0, whenMs + minute * 60_000,
                if (bright) "Bright" else "Twilight")
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                activity.setContent {
                    StageScreen(GreenhouseUiState(screen = AppScreen.History, sensors = listOf(sensor), history = history),
                        DeviceStatus(80,true,true), now.toLocalDateTime(),
                        {}, {}, {}, {}, {_,_,_->}, {_,_->}, {}, {}, {_,_,_->}, {}, Modifier.fillMaxSize())
                }
            }
            find("Light history for Display bench")
            screenshot("light-history")
            click("Light ▾")
            val title = if (brightening) "Morning light" else "Evening dimming"
            click("$title · ${chartTime(whenMs, "MMM d, h:mm a")}")
            find(if (brightening) "Morning warm-up" else "Evening cooling")
            find("First hour after the light changed")
            find("Display bench")
            screenshot("light-transition")
            click("Close")
            find("Light history for Display bench")
        }
    }

    @Test fun sensorColorsMatchAcrossZonesAndHistory() {
        val now = System.currentTimeMillis()
        val sensors = listOf("d", "b", "a", "c").mapIndexed { index, id ->
            GoveeSensor(id, "H5179", "Bench $id", 60.0 + index * 4, 80.0 - index, true, now,
                zone = "Zone ${index + 1}")
        }
        val history = sensors.flatMap { s -> (0..72).map { step ->
            HistoryReading(s.id, "Former label", s.temperatureF!! + step % 3, s.humidityPercent!!,
                now - step * 300_000L)
        } }
        val plan = GreenhouseConfig(sensorPositions = sensors.mapIndexed { index, s ->
            s.id to SensorPosition(2.0 + index * 3, 2.0 + index % 2 * 4)
        }.toMap())
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            fun show(screen: AppScreen, metric: Metric) = scenario.onActivity { activity ->
                activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                activity.setContent {
                    StageScreen(GreenhouseUiState(screen = screen, metric = metric, sensors = sensors,
                        history = history, preferences = AppPreferences(greenhouses = listOf(plan))),
                        DeviceStatus(80,true,true), java.time.LocalDateTime.now(),
                        {}, {}, {}, {}, {_,_,_->}, {_,_->}, {}, {}, {_,_,_->}, {}, Modifier.fillMaxSize())
                }
            }
            fun checkRows(checkPlot: Boolean) {
                // The rows use different sort orders on the two screens. Match each accent to its label.
                val labels = sensors.associateWith { sensor ->
                    Rect().also { find(sensor.name).getBoundsInScreen(it) }
                }
                SystemClock.sleep(600)
                val image = ui.takeScreenshot() ?: error("No screen capture")
                try {
                    for ((sensor, bounds) in labels) {
                        val color = sensorAccent(sensor.id).toArgb()
                        val y = bounds.centerY()
                        assertTrue("Missing accent beside ${sensor.name}", (bounds.left - 100 until bounds.left)
                            .any { x -> x >= 0 && image.getPixel(x, y) == color })
                        if (checkPlot) {
                            val plot = Rect().also { find("Temperature trend").getBoundsInScreen(it) }
                            assertTrue("Missing history line for ${sensor.name}", (plot.top until plot.bottom step 2)
                                .any { py -> (plot.left until plot.right step 2).any { px -> image.getPixel(px, py) == color } })
                        }
                    }
                } finally { image.recycle() }
            }
            show(AppScreen.Zones, Metric.Humidity)
            find("80%")
            checkRows(false)
            screenshot("zone-sensor-colors")
            show(AppScreen.History, Metric.Temperature)
            find("Latest readings")
            SystemClock.sleep(600)
            screenshot("history-sensor-colors")
            checkRows(true)
        }
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
                            dailyHistory=history.map { it.copy(timestampEpochMs = now - 80 * DAY_MS) },preferences=AppPreferences(greenhouses=listOf(g))),DeviceStatus(80,true,true),
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
                ui.waitForIdle(200, 2000)
                val bounds = Rect().also { find(label).getBoundsInScreen(it) }
                gesture(bounds, listOf(.1f to .5f, fraction to .5f))
                ui.waitForIdle(200, 2000)
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
            click("24h ▾")
            click("7d")
            scrub("Temperature trend", .5f)
            find("—")
            click("7d ▾")
            click("6h")
            find("Latest readings")
            screenshot("history")
            for (range in listOf("30d", "90d", "180d")) {
                click(when (range) { "30d" -> "6h ▾"; "90d" -> "30d ▾"; else -> "90d ▾" })
                click(range)
                find("Daily closing readings · UTC")
                if (range == "30d") find("No readings in this period") else find("Temperature trend")
            }
            scrub("Temperature trend", .55f)
            click("View day")
            find("No readings in this period")
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

    private fun enterFanField(label: String, value: String) {
        var node: AccessibilityNodeInfo? = find(label)
        while (node != null && !node.isEditable) node = node.parent
        val field = requireNotNull(node) { "No editable field for $label" }
        check(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, android.os.Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }))
        instrumentation.waitForIdleSync()
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
        // Rotation or opening a popup can replace a node between lookup and interaction.
        repeat(5) {
            var node: AccessibilityNodeInfo? = find(label)
            while (node != null && !node.isClickable) node = node.parent
            if (node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) {
                instrumentation.waitForIdleSync()
                return
            }
            SystemClock.sleep(100)
        }
        error("Could not click $label")
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
