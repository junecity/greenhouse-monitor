package studio.junecity.greenhouse.cloud

import org.junit.Assert.assertEquals
import org.junit.Test

class CognitoConfigurationTest {
    @Test fun validAwsConfigurationIsAccepted() {
        val value = CognitoConfiguration("us-east-1", "us-east-1_AbCd12345", "12345678901234567890", "https://api.example.test/dev")
        assertEquals("us-east-1", value.region)
    }

    @Test(expected = IllegalArgumentException::class)
    fun mismatchedPoolRegionIsRejected() {
        CognitoConfiguration("us-east-1", "us-west-2_AbCd12345", "12345678901234567890", "https://api.example.test/dev")
    }

    @Test(expected = IllegalArgumentException::class)
    fun cleartextApiIsRejected() {
        CognitoConfiguration("us-east-1", "us-east-1_AbCd12345", "12345678901234567890", "http://api.example.test/dev")
    }
}
