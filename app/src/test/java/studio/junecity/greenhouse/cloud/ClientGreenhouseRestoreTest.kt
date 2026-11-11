package studio.junecity.greenhouse.cloud

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import studio.junecity.greenhouse.settings.CloudEnrollment

class ClientGreenhouseRestoreTest {
    @Test fun reopeningRetainsSelectionIncludingWithALegacyRegisteredGreenhouse() = runBlocking {
        assertEquals("chosen-house", resolveClientGreenhouse("chosen-house", "other-house") {
            error("A saved selection should not require device discovery")
        })
    }

    @Test fun repairsPreviouslyPersistedNullFromAuthorizedServers() = runBlocking {
        val oldEnrollment = CloudEnrollment("viewer-device", "null", CloudDeviceRole.ViewerClient)
        val saved = CloudEnrollment.parse(oldEnrollment.toJson())
        assertEquals("", saved.greenhouseId)
        val active = RegisteredDevice("server-phone", "tenant", CloudDeviceRole.GreenhouseServer, greenhouseId = "house-b")
        val devices = listOf(active.copy(greenhouseId = "house-a", enabled = false),
            active.copy(greenhouseId = "paused", communicationSuspended = true), active)
        assertEquals("house-b", resolveClientGreenhouse(saved.greenhouseId, null) { devices.map { AccountDevice(it, 0) } })
    }

    @Test fun unpairedCustomerHasNoCameraTarget() = runBlocking {
        assertEquals("", resolveClientGreenhouse(null, null) { emptyList() })
    }
}
