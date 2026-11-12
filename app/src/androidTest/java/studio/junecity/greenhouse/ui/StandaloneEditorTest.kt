package studio.junecity.greenhouse.ui

import android.app.Application
import android.graphics.Rect
import androidx.lifecycle.viewModelScope
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
