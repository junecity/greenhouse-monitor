package studio.junecity.greenhouse.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import okhttp3.ResponseBody.Companion.toResponseBody
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

    @Test fun updatesFollowThePublishedBuildIndependentlyOfClientVersion() {
        val server = item.device.copy(appVersionCode = 79, latestReleaseCode = 83, latestReleaseName = "2.40.0",
            managedUpdates = true, hasReleaseCatalog = true)
        for (client in listOf(75, 79, 83, 84)) {
            val control = requireNotNull(serverUpdateControl(server, client))
            assertEquals(83, control.target)
            assertTrue(control.detail.contains("2.40.0"))
        }
        assertEquals(null, serverUpdateControl(server.copy(appVersionCode = 83), 84)?.target)
        assertEquals(null, serverUpdateControl(server.copy(appVersionCode = 84), 84)?.target)
        assertEquals(null, serverUpdateControl(server.copy(latestReleaseCode = null), 84)?.target)
        assertEquals(null, serverUpdateControl(server.copy(requestedUpdateCode = 83), 84)?.target)
        assertEquals(null, serverUpdateControl(server.copy(communicationSuspended = true), 84)?.target)
        assertEquals(null, serverUpdateControl(server.copy(enabled = false), 84)?.target)
        assertEquals(null, serverUpdateControl(server.copy(role = CloudDeviceRole.ViewerClient), 84)?.target)
        assertEquals(null, serverUpdateControl(server.copy(appVersionCode = 72), 84)?.target)
    }

    @Test fun updateRequestUsesSelectedServerAndExactApprovedBuild() = kotlinx.coroutines.runBlocking {
        val responses = listOf(
            """{"devices":[{"id":"server-001","role":"GREENHOUSE_SERVER","createdAtEpochMs":1,"appVersionCode":79,"managedUpdates":true,"latestReleaseCode":83,"latestReleaseName":"2.40.0"}]}""",
            """{"requestedUpdateCode":83}"""
        ).iterator()
        val transport = okhttp3.OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            if (request.method == "POST") {
                assertEquals("/v1/devices/server-001/update-request", request.url.encodedPath)
                val payload = okio.Buffer().also { requireNotNull(request.body).writeTo(it) }
                val json = org.json.JSONObject(payload.readUtf8())
                assertEquals(83, json.getInt("versionCode"))
                assertEquals(true, json.getBoolean("requireManaged"))
            }
            okhttp3.Response.Builder().request(request).protocol(okhttp3.Protocol.HTTP_1_1)
                .code(200).message("OK").body(responses.next().toResponseBody()).build()
        }.build()
        val api = AuthenticatedCloudGateway(CloudApiConfig("https://fixture.invalid", "client-001"),
            CloudTokenProvider { "fixture" }, client = transport)
        val server = api.devices().single().device
        val target = requireNotNull(serverUpdateControl(server, clientCode = 78)?.target)
        api.requestServerUpdate(server.id, target, requireManaged = server.managedUpdates)
        assertEquals(false, responses.hasNext())
    }
}
