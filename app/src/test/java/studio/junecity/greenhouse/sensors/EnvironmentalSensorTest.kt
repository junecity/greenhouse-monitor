package studio.junecity.greenhouse.sensors

import org.junit.Assert.*
import org.junit.Test
import studio.junecity.greenhouse.model.GoveeSensor
import studio.junecity.greenhouse.model.NotificationPreferences
import studio.junecity.greenhouse.widget.*

class EnvironmentalSensorTest {
    @Test fun standardMeasurementsUseLittleEndianHundredths() {
        assertEquals(27.5, EnvironmentalBluetooth.temperatureF(byteArrayOf(6, -1)), .001)
        assertEquals(48.25, EnvironmentalBluetooth.humidityPercent(byteArrayOf(-39, 18)), .001)
        assertEquals(0.0, EnvironmentalBluetooth.humidityPercent(byteArrayOf(0, 0)), .001)
        assertEquals("ess:AA:BB:CC:DD:EE:FF", environmentalId("aa-bb-cc-dd-ee-ff"))
    }
    @Test fun invalidReadingsDoNotBecomeMeasurements() {
        assertTrue(runCatching { EnvironmentalBluetooth.temperatureF(byteArrayOf(0, -128)) }.isFailure)
        assertTrue(runCatching { EnvironmentalBluetooth.humidityPercent(byteArrayOf(-1, -1)) }.isFailure)
        assertTrue(runCatching { EnvironmentalBluetooth.temperatureF(byteArrayOf(5)) }.isFailure)
    }
    @Test fun widgetAveragesFreshSensorsAndKeepsAgingReadingsMarked() {
        val now = 9_000_000L
        val row = GoveeSensor("a", "BLE ESS", "North", 50.0, 50.0, true, now)
        val rows = listOf(row, row.copy(id = "b", temperatureF = 86.0, humidityPercent = 70.0, updatedAtEpochMs = now - 100),
            row.copy(id = "c", updatedAtEpochMs = 1L))
        fun model(samples: List<GoveeSensor>, at: Long, celsius: Boolean) =
            climateCardState("House", samples, emptyList(), at, 1, celsius, null, null, NotificationPreferences())
        assertEquals("20°C", model(rows, now, true).temperatureText)
        assertEquals("60%", model(rows, now, false).humidityText)
        assertEquals("50°F", model(listOf(row), now, false).temperatureText)
        assertEquals(WidgetFeedStatus.Offline, model(listOf(row), now + 700_000, false).status)
        assertEquals("50°F", model(listOf(row), now + 700_000, false).temperatureText)
        assertEquals("—", model(emptyList(), now, true).temperatureText)
    }
    @Test fun launcherUsesWidthHeightAndFontScaleBeforeOfferingAChart() {
        assertEquals(LauncherCard.Compact, launcherCard(299, 150, 1f))
        assertEquals(LauncherCard.Compact, launcherCard(360, 119, 1f))
        assertEquals(LauncherCard.Wide, launcherCard(300, 120, 1f))
        assertEquals(LauncherCard.Wide, launcherCard(386, 156, 1f))
        assertEquals(LauncherCard.Wide, launcherCard(620, 220, 1f))
        assertEquals(LauncherCard.Standard, launcherCard(340, 280, 1f))
        assertEquals(LauncherCard.Compact, launcherCard(340, 138, 1.3f))
        assertEquals(LauncherCard.Standard, launcherCard(390, 360, 1.3f))
    }
}
