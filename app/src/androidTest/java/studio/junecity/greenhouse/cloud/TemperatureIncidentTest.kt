package studio.junecity.greenhouse.cloud

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import studio.junecity.greenhouse.settings.CloudEnrollment

class TemperatureIncidentTest {
    @Test fun unresolvedSensorsSurviveReopenWithinOnlyTheirEnrollmentAndFloorPlan() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val server = CloudEnrollment("test-device", "test-greenhouse", CloudDeviceRole.GreenhouseServer, tenantId = "test-owner")
        val scope = TemperatureIncidentSensors(context, server, "north")
        try {
            scope.save(setOf("fixture-govee", "fixture-shelly"))
            assertEquals(setOf("fixture-govee", "fixture-shelly"), TemperatureIncidentSensors(context, server, "north").load())
            assertTrue(TemperatureIncidentSensors(context, server, "south").load().isEmpty())
            assertTrue(TemperatureIncidentSensors(context, server.copy(tenantId = "other-owner"), "north").load().isEmpty())
            assertTrue(TemperatureIncidentSensors(context, server.copy(deviceId = "replacement"), "north").load().isEmpty())
            assertTrue(TemperatureIncidentSensors(context, server.copy(greenhouseId = "other-house"), "north").load().isEmpty())
            scope.save(emptySet())
            assertTrue(TemperatureIncidentSensors(context, server, "north").load().isEmpty())
        } finally {
            scope.save(emptySet())
        }
    }
}
