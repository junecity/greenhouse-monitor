package studio.junecity.greenhouse.cloud

import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectivityServerSelectionTest {
    private fun server(id: String, greenhouseId: String) = AccountDevice(
        RegisteredDevice(id, "tenant", CloudDeviceRole.GreenhouseServer, greenhouseId = greenhouseId),
        createdAtEpochMs = 1L
    )

    @Test fun usesThePreferredGreenhouseWhenItMatches() {
        val first = server("server-one", "greenhouse-one")
        val second = server("server-two", "greenhouse-two")

        assertEquals(listOf(second), connectivityServerCandidates(listOf(first, second), "greenhouse-two"))
    }

    @Test fun fallsBackToRegisteredServersWhenTheSavedGreenhouseIsBlankOrStale() {
        val server = server("server-one", "greenhouse-one")

        assertEquals(listOf(server), connectivityServerCandidates(listOf(server), ""))
        assertEquals(listOf(server), connectivityServerCandidates(listOf(server), "greenhouse-stale"))
    }
}
