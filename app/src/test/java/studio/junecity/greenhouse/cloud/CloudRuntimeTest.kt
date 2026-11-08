package studio.junecity.greenhouse.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CloudRuntimeTest {
    @Test fun emptyDeploymentOutputsKeepLocalOnlyBuildWorking() {
        assertNull(CloudRuntime.configurationOrNull("", "", "", ""))
    }

    @Test fun completeDeploymentOutputsCreateCognitoConfiguration() {
        val value = CloudRuntime.configurationOrNull(
            "us-east-1", "us-east-1_AbCd12345", "12345678901234567890", "https://api.example.test/dev"
        )
        assertEquals("us-east-1_AbCd12345", value?.userPoolId)
    }

    @Test(expected = IllegalArgumentException::class)
    fun partialDeploymentOutputsFailClosed() {
        CloudRuntime.configurationOrNull("us-east-1", "", "", "")
    }
}
