package studio.junecity.greenhouse.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CloudPairingPayloadTest {
    private val code = "abcdefghijklmnopqrstuvwxyz123456"

    @Test fun acceptsRawOneUseCode() {
        assertEquals(code, pairingCodeFromPayload("  $code  "))
    }

    @Test fun acceptsVersionedGreenhouseQrPayload() {
        assertEquals(code, pairingCodeFromPayload("greenhouse://cloud-pair?v=1&code=$code"))
    }

    @Test fun rejectsForeignOrShortPayload() {
        assertNull(pairingCodeFromPayload("https://example.test/?code=$code"))
        assertNull(pairingCodeFromPayload("short"))
    }

    @Test fun serverPairingPayloadRoundTrips() {
        assertEquals(code, serverPairingCodeFromPayload(serverPairingPayload(code)))
    }

    @Test fun serverPairingRejectsLegacyClientPairingPayload() {
        assertNull(serverPairingCodeFromPayload("greenhouse://cloud-pair?v=1&code=$code"))
    }

    @Test fun deviceSecretsAreStrongAndUnique() {
        val first = newDeviceSecret()
        val second = newDeviceSecret()
        assertEquals(43, first.length)
        org.junit.Assert.assertNotEquals(first, second)
    }
}
