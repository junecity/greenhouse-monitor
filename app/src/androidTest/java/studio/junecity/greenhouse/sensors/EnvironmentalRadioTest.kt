package studio.junecity.greenhouse.sensors

import android.appwidget.*
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
            var received: android.widget.RemoteViews? = null
            val customHost = object : AppWidgetHost(context,4271) {
                override fun onCreateView(c: android.content.Context, widgetId:Int, info:AppWidgetProviderInfo) =
                    object : AppWidgetHostView(c) {
                        override fun updateAppWidget(views: android.widget.RemoteViews?) { super.updateAppWidget(views); received = views }
                    }
            }
            fun displayedReading() : String {
                var reading = ""
                instrumentation.runOnMainSync {
                    customHost.createView(context,id,manager.getAppWidgetInfo(id))
                    val view = received!!.apply(context,android.widget.FrameLayout(context))
                    reading = view.findViewById<android.widget.TextView>(studio.junecity.greenhouse.R.id.widget_reading).text.toString()
                }
                return reading
            }
            assertEquals("20°C  51% RH", displayedReading())
            secure.clearCloudEnrollment()
            ClimateWidget.render(context,id)
            instrumentation.runOnMainSync { customHost.createView(context,id,manager.getAppWidgetInfo(id)) }
            assertNotNull(received)
            instrumentation.runOnMainSync {
                val view = received!!.apply(context,android.widget.FrameLayout(context))
                assertEquals("Open Greenhouse Studio to set up",view.findViewById<android.widget.TextView>(studio.junecity.greenhouse.R.id.widget_time).text.toString())
            }
        } finally {
            sensors.forget(sample)
            host.deleteHost()
            if (previous != null) secure.saveCloudEnrollment(previous) else secure.clearCloudEnrollment()
        }
    }
}
