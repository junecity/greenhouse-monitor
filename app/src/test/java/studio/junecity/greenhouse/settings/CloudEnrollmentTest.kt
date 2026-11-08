package studio.junecity.greenhouse.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import studio.junecity.greenhouse.cloud.CloudDeviceRole

class CloudEnrollmentTest {
    @Test fun serverCredentialRoundTripsInsideEncryptedEnrollmentPayload() {
        val enrollment = CloudEnrollment("server-12345678", "greenhouse-12345678", CloudDeviceRole.GreenhouseServer, "device-secret")
        assertEquals(enrollment, CloudEnrollment.parse(enrollment.toJson()))
    }

    @Test fun legacyEnrollmentWithoutCredentialStillParses() {
        val enrollment = CloudEnrollment.parse("""{"deviceId":"client-12345678","greenhouseId":"","role":"ViewerClient"}""")
        assertEquals(CloudDeviceRole.ViewerClient, enrollment.role)
        assertNull(enrollment.deviceSecret)
    }
}
