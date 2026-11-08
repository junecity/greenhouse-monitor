package studio.junecity.greenhouse.cloud

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudOnboardingCopyTest {
    @Test
    fun invalidPasswordExplainsHowToCreateAnAccountWithoutProviderBranding() {
        val message = accountErrorMessage(IllegalArgumentException("Incorrect username or password."))

        assertTrue(message.contains("Create account"))
        assertFalse(message.contains("AWS", ignoreCase = true))
        assertFalse(message.contains("Cognito", ignoreCase = true))
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
}
