package studio.junecity.greenhouse.ui

import android.Manifest
import android.content.pm.ActivityInfo
import android.os.Build
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import studio.junecity.greenhouse.MainActivity
import studio.junecity.greenhouse.cloud.CloudDeviceRole
import studio.junecity.greenhouse.model.AppScreen
import studio.junecity.greenhouse.model.DeviceStatus
import studio.junecity.greenhouse.model.GreenhouseUiState
import studio.junecity.greenhouse.settings.CloudEnrollment
import studio.junecity.greenhouse.settings.SecureSettingsStore
import studio.junecity.greenhouse.widget.LockScreenClimate
import java.time.LocalDateTime

class LockScreenReadingsSettingsTest {
    @get:Rule(order = 0) val notifications: TestRule = if (Build.VERSION.SDK_INT >= 33)
        GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS) else TestRule { base, _ -> base }
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    @Test fun displayCanEnableAndDisableAverageReadingsWhileLocked() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val secure = SecureSettingsStore(context)
        assumeTrue(Build.MODEL.contains("sdk", true) && secure.cloudEnrollment() == null)
        val owner = CloudEnrollment("display-lock-test", "display-greenhouse", CloudDeviceRole.ViewerClient, tenantId = "display-lock-test")
        try {
            secure.saveCloudEnrollment(owner)
            compose.activityRule.scenario.onActivity { activity ->
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                activity.setContent {
                    StageScreen(GreenhouseUiState(screen = AppScreen.Settings), DeviceStatus(80, true, true),
                        LocalDateTime.now(), {}, {}, {}, {}, { _, _, _ -> }, { _, _ -> }, {}, {}, { _, _, _ -> }, {},
                        Modifier.fillMaxSize())
                }
            }
            compose.onNodeWithText("Display").performClick()
            val toggle = compose.onNode(hasText("Lock screen readings") and isToggleable())
            toggle.performScrollTo().assertIsOff().performClick()
            toggle.assertIsOn()
            assertTrue(LockScreenClimate.enabled(context, owner))
            compose.onNodeWithText("General").performClick()
            compose.onNodeWithText("Display").performClick()
            toggle.performScrollTo().assertIsOn().performClick()
            toggle.assertIsOff()
            assertFalse(LockScreenClimate.enabled(context, owner))
        } finally {
            LockScreenClimate.setEnabled(context, owner, false)
            secure.clearCloudEnrollment()
        }
    }
}
