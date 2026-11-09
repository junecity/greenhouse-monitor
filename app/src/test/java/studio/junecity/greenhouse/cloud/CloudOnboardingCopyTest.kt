package studio.junecity.greenhouse.cloud

import android.content.pm.ActivityInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudOnboardingCopyTest {
    @Test
    fun settingsUsesPortraitWhileMonitoringScreensRemainLandscape() {
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT, monitoringOrientation(monitoring = true, portraitMonitoringScreen = true))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, monitoringOrientation(monitoring = true, portraitMonitoringScreen = false))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT, monitoringOrientation(monitoring = false, portraitMonitoringScreen = false))
    }

    @Test
    fun invalidPasswordExplainsHowToCreateAnAccountWithoutProviderBranding() {
        val message = accountErrorMessage(IllegalArgumentException("Incorrect username or password."))

        assertTrue(message.contains("Forgot password"))
        assertFalse(message.contains("AWS", ignoreCase = true))
        assertFalse(message.contains("Cognito", ignoreCase = true))
    }

    @Test
    fun existingAccountDirectsCustomerToPasswordRecovery() {
        val message = accountErrorMessage(IllegalStateException("UsernameExistsException"))

        assertTrue(message.contains("already exists"))
        assertTrue(message.contains("Forgot password"))
    }

    @Test
    fun passwordPolicyIsPresentedInCustomerFriendlyLanguage() {
        val message = accountErrorMessage(IllegalArgumentException("The password given is invalid."))

        assertTrue(message.contains("12 characters"))
        assertTrue(message.contains("uppercase"))
        assertTrue(message.contains("symbol"))
    }

    @Test
    fun newAccountPasswordIsValidatedBeforeNetworkSubmission() {
        assertTrue(newAccountPasswordIssue("short") != null)
        assertTrue(newAccountPasswordIssue("LongEnoughButNoSymbol1") != null)
        assertTrue(newAccountPasswordIssue("ValidPassword1!") == null)
    }

    @Test
    fun staleSignedInSessionGetsActionableRecoveryCopy() {
        val message = accountErrorMessage(IllegalStateException("There is already a signed in user."))

        assertTrue(message.contains("older app session", ignoreCase = true))
        assertTrue(message.contains("Sign in"))
    }

    @Test
    fun nestedAuthenticationCauseIsNotHiddenByGenericWrapper() {
        val message = accountErrorMessage(
            IllegalStateException("Sign in failed", IllegalArgumentException("Incorrect username or password."))
        )

        assertTrue(message.contains("Forgot password"))
    }
}
