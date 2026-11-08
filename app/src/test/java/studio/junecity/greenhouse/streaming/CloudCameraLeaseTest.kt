package studio.junecity.greenhouse.streaming

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudCameraLeaseTest {
    @After fun reset() = CloudCameraLease.setRemoteActive(false)

    @Test fun remoteSessionExclusivelyMarksCameraForWebRtc() {
        assertFalse(CloudCameraLease.remoteActive.value)
        CloudCameraLease.setRemoteActive(true)
        assertTrue(CloudCameraLease.remoteActive.value)
    }
}
