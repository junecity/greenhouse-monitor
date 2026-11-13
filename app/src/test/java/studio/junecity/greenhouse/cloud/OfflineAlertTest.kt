package studio.junecity.greenhouse.cloud

import org.junit.Assert.assertEquals
import org.junit.Test

class OfflineAlertTest {
    @Test fun suppressesRecoveredIncidentsAndKeepsIndependentUnreachableServers() {
        val old = MonitoringAlert("first","house",MonitoringAlertType.ServerOffline,MonitoringAlertPriority.High,
            "offline",100,true,"server")
        val recent = old.copy(id="current",occurredAtEpochMs=200)
        val other = old.copy(id="another",greenhouseId="other",serverId="other")
        val critical = old.copy(id="heat",type=MonitoringAlertType.CriticalTemperature)
        val reports = listOf(old,recent,other,critical)
        val server = RegisteredDevice("server","test",CloudDeviceRole.GreenhouseServer,greenhouseId="house",
            lastSeenAtEpochMs=150)
        assertEquals(setOf(critical,recent,other), currentAlerts(reports,listOf(server)).toSet())
        for (healthy in listOf(server.copy(online=true),server.copy(lastSeenAtEpochMs=201),
                server.copy(enabled=false),server.copy(communicationSuspended=true))) {
            assertEquals(setOf(critical,other),currentAlerts(reports,listOf(healthy)).toSet())
        }
        assertEquals(setOf(critical,recent,other), currentAlerts(reports,null).toSet())
    }
}
