package studio.junecity.greenhouse.cloud

import org.junit.Assert.assertEquals
import org.junit.Test
import studio.junecity.greenhouse.model.DeviceStatus

class ServerVersionTest {
    @Test fun comparesBuildsAndLegacyNumericVersions() {
        assertEquals(ServerVersion.Newer, compareServerVersion("2.32.0", 75, "2.33.0", 73))
        assertEquals(ServerVersion.Same, compareServerVersion(null, 73, "2.33.0", 73))
        assertEquals(ServerVersion.Older, compareServerVersion("2.9.9", null, "2.33.0", 73))
        assertEquals(ServerVersion.Unknown, compareServerVersion("unknown", null))
        assertEquals(ServerVersion.Unknown, compareServerVersion(null, null))
    }

    private val item = AccountDevice(RegisteredDevice("phone", "tenant", CloudDeviceRole.GreenhouseServer,
        greenhouseId = "house"), 1_000)
    private val report = DeviceStatus(84, true, true, reportedAt = 2_000, appVersion = "2.33.0",
        appVersionCode = 73, sourceDeviceId = "phone", cloudGreenhouseId = "house")

    @Test fun inventoryWinsAndBuildOnlyHasAReadableLabel() {
        val inventory = item.copy(device = item.device.copy(appVersionCode = 74))
        assertEquals("build 74", resolveServerVersion(inventory, report).label)
        assertEquals("2.33.0", resolveServerVersion(item, report).label)
    }

    @Test fun scopesReportsToTheActiveServerAndHouse() {
        val invalid = listOf(report.copy(sourceDeviceId = "previous"), report.copy(cloudGreenhouseId = "other"),
            report.copy(cloudGreenhouseId = null))
        for (sample in invalid) assertEquals(ServerVersion.Unknown, resolveServerVersion(item, sample).comparison)
        val revoked = item.copy(device = item.device.copy(enabled = false))
        assertEquals(ServerVersion.Unknown, resolveServerVersion(revoked, report).comparison)
    }

    @Test fun unattributedLegacyReportIsLabeledAndCannotPredatePairing() {
        val legacy = report.copy(sourceDeviceId = null)
        val result = resolveServerVersion(item, legacy)
        assertEquals(true, result.fromHouseReport)
        assertEquals("2.33.0", result.label)
        assertEquals(ServerVersion.Unknown, resolveServerVersion(item, legacy.copy(reportedAt = 500)).comparison)
        assertEquals(ServerVersion.Unknown, resolveServerVersion(item, null).comparison)
    }
}
