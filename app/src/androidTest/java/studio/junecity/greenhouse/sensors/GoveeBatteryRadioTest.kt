package studio.junecity.greenhouse.sensors

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import studio.junecity.greenhouse.model.GoveeSensor
import studio.junecity.greenhouse.settings.CurrentSensorStore

class GoveeBatteryRadioTest {
    @Test fun filteredScanEnrichesEnrolledCloudSensorsAndSurvivesRefresh() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("goveeRadio") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = CurrentSensorStore(context, "_govee_radio_test")
        val expected = listOf(84, 17)
        val rows = expected.mapIndexed { i, _ ->
            GoveeSensor("11:22:C1:00:00:00:00:0${i + 1}", "H5179", "Radio $i")
        }
        try {
            store.save(rows)
            runBlocking {
                withTimeout(30_000) {
                    GoveeBattery.packets(context, false).first { packet ->
                        store.update { GoveeBattery.apply(it, packet) }.map { it.batteryPercent } == expected
                    }
                }
            }
            store.replaceGovee(rows.map { it.copy(temperatureF = 71.0) })
            val saved = CurrentSensorStore(context, "_govee_radio_test").load()
            assertEquals(expected, saved.map { it.batteryPercent })
            assertTrue(saved.all { it.temperatureF == 71.0 && it.batteryObservedAt != null && !it.online })
        } finally { store.clear() }
    }
}
