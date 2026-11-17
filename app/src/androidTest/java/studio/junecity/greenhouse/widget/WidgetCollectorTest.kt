package studio.junecity.greenhouse.widget

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import studio.junecity.greenhouse.cloud.CloudDeviceRole
import studio.junecity.greenhouse.data.HistoryStore
import studio.junecity.greenhouse.sensors.*
import studio.junecity.greenhouse.settings.*
import java.util.concurrent.atomic.AtomicInteger

class WidgetCollectorTest {
    @Test fun concurrentCollectorsKeepRealTimeAndDiscardReadsAfterSwitchToViewer() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val secure = SecureSettingsStore(app)
        val single = CloudEnrollment("local-widget-qa", "", CloudDeviceRole.ViewerClient, localSensors = true, tenantId = "widget-tests")
        val viewer = single.copy(greenhouseId = "paired-house", localSensors = false)
        val store = CurrentSensorStore(app, "_standalone")
        require(secure.cloudEnrollment() == null && secure.sensorKey(single) == null && store.load().isEmpty()) { "Isolated test profile required" }
        val id = "widget-govee-collector-qa"
        val at = System.currentTimeMillis() - 2_000L
        val calls = AtomicInteger()
        var changeOwner = false
        val provider = object : SensorProvider {
            override val providerId = "fixture"
            override suspend fun discover(credential: SensorProviderCredential) = listOf(SensorDevice(providerId, id, "H5179", "Bench"))
            override suspend fun read(credential: SensorProviderCredential, device: SensorDevice): SensorMeasurement {
                calls.incrementAndGet()
                delay(50L)
                if (changeOwner) secure.saveCloudEnrollment(viewer)
                return SensorMeasurement(device, if (changeOwner) 40.0 else 65.0, 71.0, connected = true, recordedAtEpochMs = at)
            }
        }
        try {
            secure.saveCloudEnrollment(single)
            secure.saveSensorKey(single, "widget-test")
            val result = coroutineScope { List(2) { async { CurrentGoveeReadings.refresh(app, single, provider) } }.awaitAll() }
            assertEquals(1, calls.get())
            assertTrue(result.all { it?.single()?.updatedAtEpochMs == at })
            val card = ClimateCardRepository(app).load(single, now = at + 3_000L)
            assertEquals("65°F", card.temperatureText)
            assertEquals("71%", card.humidityText)
            assertEquals(at, card.measuredAt)
            changeOwner = true
            assertNull(CurrentGoveeReadings.refresh(app, single, provider, force = true))
            assertNull(CurrentGoveeReadings.refresh(app, viewer, provider, force = true))
            assertEquals(2, calls.get())
            assertEquals(65.0, store.load().single().temperatureF!!, 0.0)
            assertNull(ClimateCardRepository(app).load(viewer).temperatureF)
        } finally {
            secure.clearCloudEnrollment()
            secure.clearStandaloneKey()
            store.clear()
            HistoryStore(app, "_standalone").use { it.writableDatabase.delete("readings", "sensor_id=?", arrayOf(id)) }
        }
    }
}
