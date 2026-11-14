package studio.junecity.greenhouse.sensors

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import kotlinx.coroutines.flow.first
import org.junit.Test
import org.junit.runner.RunWith
import studio.junecity.greenhouse.model.GoveeSensor
import studio.junecity.greenhouse.settings.CurrentSensorStore

@RunWith(AndroidJUnit4::class)
class ShellyInventoryTest {
    @Test fun discoverBothModelsAcrossAndroidBluetooth() {
        org.junit.Assume.assumeTrue("Requires two virtual radio fixtures",
            InstrumentationRegistry.getArguments().getString("shellyRadio") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val found = mutableMapOf<ShellyModel, ShellyBeacon>()
        kotlinx.coroutines.runBlocking {
            kotlinx.coroutines.withTimeout(25_000) {
                ShellyBluetooth.advertisements(context, true).first { ad ->
                    ad.model?.let { found[it] = ad.beacon }
                    found.size == 2
                }
            }
        }
        assertEquals(77.0, found.getValue(ShellyModel.HT).temperatureF!!, .01)
        assertEquals(50.0, found.getValue(ShellyModel.Display).humidity!!, .01)
        assertEquals("Bright", found.getValue(ShellyModel.Display).light)
    }

    @Test fun mixedFleetSurvivesRefreshAndReloadWithLightAndAssignments() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = CurrentSensorStore(context, "_shelly_fixture")
        val now = System.currentTimeMillis()
        val units = (1..50).map { GoveeSensor("shelly:$it", ShellyModel.Display.sku, "Sensor $it", greenhouseId="west", zone="Bench $it") }
        try {
            store.save(units)
            val govee = GoveeSensor("govee-1","H5179","Existing")
            store.replaceGovee(listOf(govee))
            val packets = (units.map { it.id } + "shelly:unknown").associateWith { id ->
                ShellyAdvertisement(id, ShellyModel.Display, ShellyBeacon(68.0, 75.0, "Twilight", null, false), now)
            }
            var received = 0
            store.applyShellyPackets(packets) { received++ }
            store.applyShellyPackets(packets) { received++ }
            store.replaceGovee(listOf(govee))
            val restored = CurrentSensorStore(context, "_shelly_fixture").load()
            assertEquals(51, restored.size)
            assertEquals(50, received)
            assertEquals(units.map { it.zone }, restored.filter { it.isShelly() }.map { it.zone })
            assertTrue(restored.filter { it.isShelly() }.all { it.greenhouseId == "west" && it.lightLevel == "Twilight" && it.isFresh(now) })
            assertTrue(govee in restored)
        } finally { store.clear() }
    }
}
