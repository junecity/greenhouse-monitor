package studio.junecity.greenhouse.camera

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.util.Base64

class CameraSecurityTest {
    private val pairing = CameraPairing(
        id = "greenhouse-test",
        token = "viewer-token",
        key = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { (it + 1).toByte() }),
        localHost = "192.168.1.50",
        publicHost = "203.0.113.8",
        port = 45891
    )

    @Test fun pairingUriRoundTripsAllConnectionData() {
        val parsed = CameraPairing.parse(pairing.pairingUri())
        assertNotNull(parsed)
        assertEquals(pairing, parsed)
    }

    @Test fun encryptedFrameDecryptsToOriginalJpegBytes() {
        val frame = ByteArray(4_096) { (it * 31).toByte() }
        val encrypted = CameraCrypto.encrypt(frame, pairing)
        assertArrayEquals(frame, CameraCrypto.decrypt(encrypted, pairing))
    }

    @Test(expected = Exception::class)
    fun modifiedEncryptedFrameIsRejected() {
        val encrypted = CameraCrypto.encrypt("jpeg".toByteArray(), pairing)
        encrypted[encrypted.lastIndex] = (encrypted.last() + 1).toByte()
        CameraCrypto.decrypt(encrypted, pairing)
    }
}
