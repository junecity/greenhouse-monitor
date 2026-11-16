package studio.junecity.greenhouse.cloud

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import studio.junecity.greenhouse.model.*
import studio.junecity.greenhouse.settings.AppPreferencesStore
import studio.junecity.greenhouse.settings.CloudEnrollment
import java.util.UUID

class AlertRulePersistenceTest {
    @Test fun appliedLimitsSurviveServerRestartAndStayWithTheirServerAndGreenhouse() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val id = UUID.randomUUID().toString()
        val owner = CloudEnrollment(id, "greenhouse-1", CloudDeviceRole.GreenhouseServer, tenantId = "tenant-test")
        val local = AppPreferencesStore(context)
        val prefs = local.load()
        val measured = AlertRules.from(NotificationPreferences()).copy(lowTemperatureF = 51.75, temperatureAlerts = false)
        val state = AlertRuleState(12, measured, 8)
        val store = AppliedAlertRules(context, owner)
        try {
            assertNull(store.load())
            assertTrue(store.install(state))
            assertFalse(store.install(state))
            assertEquals(state.copy(appliedRevision = 12), AppliedAlertRules(context, owner).load())
            assertEquals(measured.applyTo(prefs.notifications), local.loadForMonitoring(owner).forAlertsIn())
            assertEquals(measured, local.decode(local.exportJson(owner)).activeGreenhouse.alertRules)
            for (other in listOf(owner.copy(greenhouseId = "greenhouse-2"), owner.copy(deviceId = "other-phone"), owner.copy(tenantId = "another-account"))) {
                assertNull(AppliedAlertRules(context, other).load())
                assertEquals(prefs, local.loadForMonitoring(other))
            }
            assertEquals(prefs, local.loadForMonitoring(owner.copy(role = CloudDeviceRole.ViewerClient)))
            assertEquals(prefs, local.loadForMonitoring(null))
        } finally {
            context.getSharedPreferences("applied_greenhouse_alert_rules", Context.MODE_PRIVATE).edit()
                .remove("${owner.tenantId}/${owner.deviceId}/${owner.greenhouseId}").commit()
        }
    }

    @Test fun viewerRestoresOldThresholdsAndNewHouseRulesWithoutChangingPhoneDelivery() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val local = AppPreferencesStore(context)
        val before = local.load()
        val snapshotSource = AppPreferencesStore(context, "_test_alert_rule_snapshot")
        try {
            val phone = NotificationPreferences(soundEnabled = true, vibrationEnabled = true, repeatedAlerts = true, snoozedUntilEpochMs = 5678)
            local.save(AppPreferences(notifications = phone))
            val legacy = NotificationPreferences(lowTemperatureF = 49.5, highTemperatureF = 86.0, soundEnabled = false)
            val remote = AppPreferences(notifications = legacy, greenhouses = listOf(GreenhouseConfig(), GreenhouseConfig("annex")))
            val rules = AlertRules.from(legacy).copy(lowTemperatureF = 54.5, lowHumidity = 40.0)
            snapshotSource.save(remote.withAlertRules("annex", rules))
            val snapshot = JSONObject().put("schema", 2).put("preferences", snapshotSource.exportJson())
            GreenhouseBackupStore(context).restore(snapshot, CameraMode.ViewerClient)
            val viewer = AppPreferencesStore(context).load()
            assertEquals(AlertRules.from(legacy).applyTo(phone), viewer.forAlertsIn("main"))
            assertEquals(rules.applyTo(phone), viewer.forAlertsIn("annex"))
            assertEquals(CameraMode.ViewerClient, viewer.cameraMode)
        } finally {
            local.save(before)
            snapshotSource.clear()
        }
    }
}
