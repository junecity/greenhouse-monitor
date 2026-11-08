package studio.junecity.greenhouse.camera

import android.content.Context
import org.json.JSONObject
import studio.junecity.greenhouse.settings.SecureSettingsStore
import java.security.SecureRandom
import java.util.Base64

data class CameraPairing(
    val id: String,
    val token: String,
    val key: String,
    val localHost: String,
    val publicHost: String? = null,
    val port: Int = CameraStreamServer.DEFAULT_PORT
) {
    fun endpoint(host: String) = "http://$host:$port/frame?t=$token"
    fun toJson() = JSONObject()
        .put("v", 1).put("id", id).put("token", token).put("key", key)
        .put("local", localHost).put("public", publicHost ?: JSONObject.NULL).put("port", port)
        .toString()

    fun pairingUri(): String = "greenhouse://camera/pair?data=" +
        Base64.getUrlEncoder().withoutPadding().encodeToString(toJson().toByteArray())

    companion object {
        fun parse(value: String): CameraPairing? = runCatching {
            val encoded = value.substringAfter("data=", value)
            val json = if (value.startsWith("greenhouse://")) {
                String(Base64.getUrlDecoder().decode(encoded))
            } else value
            val o = JSONObject(json)
            CameraPairing(
                o.getString("id"), o.getString("token"), o.getString("key"),
                o.getString("local"), o.optString("public").takeIf { it.isNotBlank() && it != "null" },
                o.optInt("port", CameraStreamServer.DEFAULT_PORT)
            )
        }.getOrNull()
    }
}

class CameraPairingStore(context: Context) {
    private val secure = SecureSettingsStore(context)

    fun load(): CameraPairing? = secure.cameraPairing()?.let(CameraPairing::parse)

    fun save(pairing: CameraPairing) = secure.saveCameraPairing(pairing.toJson())

    fun getOrCreateServer(localHost: String): CameraPairing {
        val current = load()
        if (current != null && current.localHost == localHost) return current
        val random = SecureRandom()
        fun secret(bytes: Int): String = ByteArray(bytes).also(random::nextBytes).let {
            Base64.getUrlEncoder().withoutPadding().encodeToString(it)
        }
        return CameraPairing(secret(9), secret(24), secret(32), localHost).also(::save)
    }

    fun revoke(localHost: String): CameraPairing {
        secure.clearCameraPairing()
        return getOrCreateServer(localHost)
    }
}
