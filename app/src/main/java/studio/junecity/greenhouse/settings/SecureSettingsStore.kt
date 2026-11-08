package studio.junecity.greenhouse.settings

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONObject

data class CloudSession(
    val apiBaseUrl: String,
    val deviceId: String,
    val accessToken: String,
    val accessTokenExpiresAtEpochMs: Long,
    val refreshToken: String? = null
) {
    fun toJson() = JSONObject().put("apiBaseUrl", apiBaseUrl).put("deviceId", deviceId).put("accessToken", accessToken)
        .put("accessTokenExpiresAtEpochMs", accessTokenExpiresAtEpochMs).put("refreshToken", refreshToken).toString()
    companion object { fun parse(value: String) = JSONObject(value).let { CloudSession(it.getString("apiBaseUrl"), it.getString("deviceId"), it.getString("accessToken"), it.getLong("accessTokenExpiresAtEpochMs"), if(it.has("refreshToken")&&!it.isNull("refreshToken"))it.getString("refreshToken").ifBlank { null }else null) } }
}

class SecureSettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("secure_greenhouse_settings", Context.MODE_PRIVATE)
    private val alias = "greenhouse_govee_api_key"

    fun saveGoveeApiKey(value: String) {
        saveEncrypted("govee_key", value)
    }

    fun saveCameraPairing(value: String) = saveEncrypted("camera_pairing", value)
    fun cameraPairing(): String? = loadEncrypted("camera_pairing")
    fun clearCameraPairing() = prefs.edit().remove("camera_pairing").apply()
    fun saveCloudSession(value: CloudSession) = saveEncrypted("cloud_session", value.toJson())
    fun cloudSession(): CloudSession? = loadEncrypted("cloud_session")?.let { runCatching { CloudSession.parse(it) }.getOrNull() }
    fun clearCloudSession() = prefs.edit().remove("cloud_session").apply()

    private fun saveEncrypted(preferenceKey: String, value: String) {
        if (value.isBlank()) { prefs.edit().remove(preferenceKey).apply(); return }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(value.trim().toByteArray(Charsets.UTF_8))
        val packed = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(encrypted, Base64.NO_WRAP)
        prefs.edit().putString(preferenceKey, packed).apply()
    }

    private fun loadEncrypted(preferenceKey: String): String? = runCatching {
        val pieces = prefs.getString(preferenceKey, null)?.split(":", limit = 2) ?: return null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, Base64.decode(pieces[0], Base64.NO_WRAP)))
        String(cipher.doFinal(Base64.decode(pieces[1], Base64.NO_WRAP)), Charsets.UTF_8)
    }.getOrNull()

    fun goveeApiKey(): String? = loadEncrypted("govee_key")

    fun saveLocation(latitude: Double, longitude: Double) = prefs.edit().putLong("latitude", latitude.toBits()).putLong("longitude", longitude.toBits()).apply()
    fun location(): Pair<Double,Double>? = if (prefs.contains("latitude")) Double.fromBits(prefs.getLong("latitude",0)) to Double.fromBits(prefs.getLong("longitude",0)) else null

    private fun getOrCreateKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        return generator.generateKey()
    }
}
