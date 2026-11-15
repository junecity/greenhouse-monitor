package studio.junecity.greenhouse.sensors

import org.junit.Assert.*
import org.junit.Test
import studio.junecity.greenhouse.model.GoveeSensor
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
    @Test fun widgetAveragesFreshSensorsAndRespectsUnits() {
        val now = 9_000_000L
        val row = GoveeSensor("a", "BLE ESS", "North", 50.0, 50.0, true, now)
        val rows = listOf(row, row.copy(id="b",temperatureF=86.0,humidityPercent=70.0,updatedAtEpochMs=now-100),row.copy(id="c",updatedAtEpochMs=1))
        assertEquals(WidgetReading("20°C  60% RH",now-100,false),widgetReading(rows,WidgetMetric.Both,true,now))
        assertEquals("50°F",widgetReading(listOf(row),WidgetMetric.Temperature,false,now).text)
        assertEquals("50% RH",widgetReading(listOf(row),WidgetMetric.Humidity,false,now).text)
        assertEquals(WidgetReading("—",now,true),widgetReading(rows,WidgetMetric.Both,false,now+700000))
        assertEquals(WidgetReading("—",null,false),widgetReading(emptyList(),WidgetMetric.Both,true,now))
    }
}
