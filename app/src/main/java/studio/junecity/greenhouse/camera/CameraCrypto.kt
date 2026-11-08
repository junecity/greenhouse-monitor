package studio.junecity.greenhouse.camera

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object CameraCrypto {
    private val random = SecureRandom()

    fun encrypt(jpeg: ByteArray, pairing: CameraPairing): ByteArray {
        val nonce = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(pairing), GCMParameterSpec(128, nonce))
        cipher.updateAAD(pairing.id.toByteArray())
        return nonce + cipher.doFinal(jpeg)
    }

    fun decrypt(packet: ByteArray, pairing: CameraPairing): ByteArray {
        require(packet.size > 28) { "Invalid encrypted camera frame" }
        val nonce = packet.copyOfRange(0, 12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(pairing), GCMParameterSpec(128, nonce))
        cipher.updateAAD(pairing.id.toByteArray())
        return cipher.doFinal(packet, 12, packet.size - 12)
    }

    private fun key(pairing: CameraPairing) = SecretKeySpec(
        Base64.getUrlDecoder().decode(pairing.key), "AES"
    )
}
