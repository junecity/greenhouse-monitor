package studio.junecity.greenhouse.backup

import android.content.Context
import com.google.crypto.tink.subtle.AesGcmHkdfStreaming
import org.json.JSONObject
import studio.junecity.greenhouse.cloud.*
import studio.junecity.greenhouse.data.*
import studio.junecity.greenhouse.model.*
import studio.junecity.greenhouse.settings.*
import java.io.*
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

internal data class BackupScope(val tenant: String, val house: String, val kind: String)

internal suspend fun verifiedBackupScope(context: Context, enrollment: CloudEnrollment?): BackupScope {
    val config = CloudRuntime.configurationOrNull()
    if (config == null && enrollment == null) return BackupScope("local", "", "collector")
    check(config != null && enrollment != null) { "Sign in or pair this phone first." }
    val device = gatewayForEnrollment(context, config, enrollment).currentDevice()
    check(device.id == enrollment.deviceId && device.enabled && !device.communicationSuspended &&
        device.role == enrollment.role && device.tenantId.isNotBlank()) { "This phone is no longer authorized." }
    return when {
        device.role == CloudDeviceRole.GreenhouseServer -> {
            check(!device.greenhouseId.isNullOrBlank() && device.greenhouseId == enrollment.greenhouseId)
            BackupScope(device.tenantId, device.greenhouseId, "collector")
        }
        enrollment.isStandaloneClient() -> BackupScope(device.tenantId, "", "standalone")
        else -> BackupScope(device.tenantId, "", "viewer")
    }
}

internal class LegacyPasswordRequired : IOException("This older backup requires its original password.")

/** GHBACK02 uses a checksummed gzip payload. GHBACK01 remains readable with its original password. */
internal class CustomerBackup(private val context: Context, private val enrollment: CloudEnrollment?) {
    private val suffix = if (enrollment.isStandaloneClient()) "_standalone" else ""
    private val preferences = AppPreferencesStore(context, suffix)
    private val sensors = CurrentSensorStore(context, suffix)
    private val zones = ZoneAssignmentStore(context, suffix)
    private val forecast = ForecastAlerts(context)
    private val deviceId = enrollment?.deviceId ?: "local"

    fun write(output: OutputStream, scope: BackupScope): Int {
        checkEnrollment()
        val rules = forecast.load()
        val meta = JSONObject().put("tenant", scope.tenant).put("house", scope.house).put("kind", scope.kind)
            .put("forecast", JSONObject().put("cold",rules.cold).put("low",rules.lowF).put("heat",rules.heat)
                .put("high",rules.highF).put("reminder",rules.reminder).put("minute",rules.minuteOfDay))
        if (scope.kind != "viewer") {
            meta.put("preferences", preferences.exportJson()).put("sensors", CurrentSensorCodec.encode(sensors.load()))
                .put("zones", JSONObject(zones.exportAssignments()))
        }
        val bytes = meta.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size in 1..MAX_META)
        output.write(LOCAL_FORMAT)
        var count = 0
        DataOutputStream(GZIPOutputStream(output.buffered()).buffered()).use { stream ->
            stream.writeInt(bytes.size)
            stream.write(bytes)
            if (scope.kind != "viewer") HistoryStore(context, suffix).use { db ->
                db.visitReadings { row ->
                    stream.writeByte(1)
                    stream.writeUTF(row.sensorId)
                    stream.writeUTF(row.sensorName)
                    stream.writeDouble(row.temperatureF)
                    stream.writeDouble(row.humidityPercent)
                    stream.writeLong(row.timestampEpochMs)
                    count++
                }
            }
            checkEnrollment()
            stream.writeByte(0)
            stream.writeInt(count)
        }
        return count
    }

    fun restore(input: InputStream, password: CharArray, scope: BackupScope): Int {
        val envelope = DataInputStream(input.buffered())
        val header = ByteArray(MAGIC.size).also(envelope::readFully)
        val decrypted = when {
            header.contentEquals(LOCAL_FORMAT) -> GZIPInputStream(envelope)
            header.contentEquals(MAGIC) -> {
                if (password.isEmpty()) throw LegacyPasswordRequired()
                val salt = ByteArray(16).also(envelope::readFully)
                cipher(password, salt).newDecryptingStream(envelope, MAGIC)
            }
            else -> throw IllegalArgumentException("Choose a Greenhouse Monitor backup file.")
        }
        DataInputStream(decrypted.buffered()).use { stream ->
            val size = stream.readInt()
            require(size in 1..MAX_META)
            val meta = JSONObject(ByteArray(size).also(stream::readFully).toString(Charsets.UTF_8))
            require(meta.getString("tenant") == scope.tenant && meta.getString("house") == scope.house &&
                meta.getString("kind") == scope.kind) { "This backup belongs to another account, greenhouse or phone mode." }
            validateMetadata(meta)
            meta.put("destination", deviceId)
            var count = 0
            synchronized(restoreLock) {
                checkEnrollment()
                HistoryStore(context, suffix).use { db ->
                    db.mergeBackup(meta.toString()) { insert ->
                        var marker = stream.readUnsignedByte()
                        while (marker == 1) {
                            require(scope.kind != "viewer")
                            val row = HistoryReading(stream.readUTF(),stream.readUTF(),stream.readDouble(),stream.readDouble(),stream.readLong())
                            require(row.sensorId.isNotBlank() && row.temperatureF.isFinite() && row.humidityPercent in 0.0..100.0 && row.timestampEpochMs > 0)
                            insert(row)
                            count++
                            marker = stream.readUnsignedByte()
                        }
                        require(marker == 0 && stream.readInt() == count && stream.read() == -1)
                        checkEnrollment()
                    }
                    applyMetadata(meta)
                    db.finishRestore()
                }
            }
            return count
        }
    }

    fun resumePendingRestore() = synchronized(restoreLock) {
        HistoryStore(context, suffix).use { db ->
            val meta = db.pendingRestore()?.let(::JSONObject) ?: return@use
            if (meta.getString("destination") == deviceId) applyMetadata(meta)
            db.finishRestore()
        }
    }

    private fun checkEnrollment() {
        check(SecureSettingsStore(context).cloudEnrollment() == enrollment) { "Phone setup changed. Open Backup again." }
    }

    private fun rules(meta: JSONObject): ForecastRules = meta.getJSONObject("forecast").let {
        ForecastRules(it.getBoolean("cold"),it.getInt("low"),it.getBoolean("heat"),it.getInt("high"),
            it.getBoolean("reminder"),it.getInt("minute")).also { rules -> require(rules.minuteOfDay in 0..1439) }
    }

    private fun validateMetadata(meta: JSONObject) {
        rules(meta)
        if (meta.getString("kind") == "viewer") return
        val settings = meta.getJSONObject("preferences")
        require(settings.getJSONArray("greenhouses").length() > 0)
        preferences.decode(settings)
        CurrentSensorCodec.decode(meta.getJSONArray("sensors"))
        val assignments = meta.getJSONObject("zones")
        assignments.keys().forEach { require(assignments.get(it) is String) }
    }

    private fun applyMetadata(meta: JSONObject) {
        if (meta.getString("kind") != "viewer") {
            val prefs = preferences.decode(meta.getJSONObject("preferences")).copy(cameraMode = preferences.load().cameraMode)
            val saved = CurrentSensorCodec.decode(meta.getJSONArray("sensors"))
            val current = sensors.load().associateBy { it.id }
            val assignments = meta.getJSONObject("zones")
            preferences.restore(prefs)
            zones.replaceAssignments(assignments.keys().asSequence().associateWith(assignments::getString))
            sensors.restore(saved.map { old ->
                current[old.id]?.takeIf { it.updatedAtEpochMs > old.updatedAtEpochMs }?.copy(greenhouseId = old.greenhouseId, zone = old.zone) ?: old
            }.inSavedHouses(prefs))
        }
        forecast.save(rules(meta), durable = true)
    }

    private companion object {
        val LOCAL_FORMAT = "GHBACK02".toByteArray(Charsets.US_ASCII)
        val MAGIC = "GHBACK01".toByteArray(Charsets.US_ASCII)
        const val MAX_META = 2 * 1024 * 1024
        val restoreLock = Any()
        fun cipher(password: CharArray, salt: ByteArray): AesGcmHkdfStreaming {
            val spec = PBEKeySpec(password, salt, 210_000, 256)
            val key = try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded }
                finally { spec.clearPassword() }
            return try { AesGcmHkdfStreaming(key, "HmacSha256", 32, 65536, 0) } finally { key.fill(0) }
        }
    }
}
