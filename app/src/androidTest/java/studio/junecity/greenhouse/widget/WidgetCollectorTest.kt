package studio.junecity.greenhouse.widget

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import studio.junecity.greenhouse.seedMonitoredPlan
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
            GreenhousePlanCache.resetLocalCollectorOwner(app, single)
            seedMonitoredPlan(app, single)
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

    @Test fun localReadingsContinueWhileViewingRemoteAndStopOnAccountSwitch() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val secure = SecureSettingsStore(app)
        require(secure.cloudEnrollment() == null) { "Isolated test user required" }
        val local = CloudEnrollment("mixed-collector", "", CloudDeviceRole.ViewerClient, localSensors = true, tenantId = "own-tenant")
        val viewer = local.copy(greenhouseId = "remote-house", localSensors = false)
        val other = viewer.copy(tenantId = "another-tenant")
        val localStore = CurrentSensorStore(app, "_standalone")
        val viewerStore = CurrentSensorStore(app)
        val known = "enrolled-local-sensor"
        val requested = mutableListOf<String>()
        var measuredAt = System.currentTimeMillis() - 120_000L
        var duringRead: () -> Unit = {}
        val provider = object : SensorProvider {
            override val providerId = "fixture"
            override suspend fun discover(credential: SensorProviderCredential) =
                listOf(known, "never-enrolled").map { SensorDevice(providerId, it, "H5179", it) }
            override suspend fun read(credential: SensorProviderCredential, device: SensorDevice): SensorMeasurement {
                requested.add(device.deviceId)
                duringRead()
                return SensorMeasurement(device, 68.0, 60.0, connected = true, recordedAtEpochMs = measuredAt)
            }
        }
        try {
            secure.saveCloudEnrollment(local)
            GreenhousePlanCache.resetLocalCollectorOwner(app, local)
            seedMonitoredPlan(app, local)
            secure.saveSensorKey(local, "fixture-key")
            localStore.enroll(studio.junecity.greenhouse.model.GoveeSensor(known, "H5179", "Bench", greenhouseId="main"))
            viewerStore.enroll(studio.junecity.greenhouse.model.GoveeSensor("remote", "H5179", "Remote bench"))
            secure.saveCloudEnrollment(viewer)
            val source = requireNotNull(GreenhousePlanCache.localCollector(app, viewer))
            val result = CurrentGoveeReadings.refresh(app, source, provider, force=true, authenticatedAs=viewer)
            assertEquals(listOf(known), requested)
            assertEquals(measuredAt, result!!.single().updatedAtEpochMs)
            assertEquals(known, localStore.load().single().id)
            assertEquals("remote", viewerStore.load().single().id)
            HistoryStore(app, "_standalone").use { db -> assertTrue(db.recent(1).any { it.sensorId == known }) }
            val successfulAt = measuredAt
            measuredAt = System.currentTimeMillis()
            requested.clear()
            duringRead = { secure.saveCloudEnrollment(other) }
            assertNull(CurrentGoveeReadings.refresh(app, source, provider, force=true, authenticatedAs=viewer))
            assertNull(GreenhousePlanCache.localCollector(app, other))
            assertNull(CurrentGoveeReadings.refresh(app, source, provider, force=true, authenticatedAs=other))
            assertEquals(listOf(known), requested)
            assertEquals(successfulAt, localStore.load().single().updatedAtEpochMs)
            assertEquals("remote", viewerStore.load().single().id)
        } finally {
            secure.clearCloudEnrollment()
            secure.clearStandaloneKey()
            localStore.clear()
            viewerStore.clear()
            HistoryStore(app, "_standalone").use { it.writableDatabase.delete("readings", "sensor_id=?", arrayOf(known)) }
        }
    }
}
