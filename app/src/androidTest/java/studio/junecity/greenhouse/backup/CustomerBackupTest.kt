package studio.junecity.greenhouse.backup

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import studio.junecity.greenhouse.cloud.CloudDeviceRole
import studio.junecity.greenhouse.data.*
import studio.junecity.greenhouse.model.*
import studio.junecity.greenhouse.settings.*
import java.io.ByteArrayOutputStream
import java.util.UUID

class CustomerBackupTest {
    private val target = InstrumentationRegistry.getInstrumentation().targetContext
    // Private names keep tests independent of the actual enrollment, sensors and history.
    private val prefix = "backup_spec_" + UUID.randomUUID() + "_"
    private val context = object : ContextWrapper(target) {
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            super.getSharedPreferences(prefix + name, mode)
        override fun getDatabasePath(name: String) = super.getDatabasePath(prefix + name)
    }
    private val enrollment = CloudEnrollment("fixture-client", "", CloudDeviceRole.ViewerClient, localSensors = true)
    private val scope = BackupScope("fixture-owner", "", "standalone")
    private val pass = "backup test fixture".toCharArray()
    private val secure = SecureSettingsStore(context)
    private val prefs = AppPreferencesStore(context, "_standalone")
    private val zones = ZoneAssignmentStore(context, "_standalone")
    private val current = CurrentSensorStore(context, "_standalone")
    private val rules = ForecastRules(lowF = 38, highF = 92, minuteOfDay = 555)
    private val house = GreenhouseConfig(id = "test-house", name = "North bench", shape = GreenhouseShape.TShape,
        sensorPositions = mapOf("probe" to SensorPosition(3.0, 2.0, 1.0)), refreshMinutes = 10)
    private val settings = AppPreferences(zipCode = "12345", greenhouses = listOf(GreenhouseConfig(),house),
        activeGreenhouseId = house.id, sensorGreenhouseIds = mapOf("probe" to house.id),
        notifications = NotificationPreferences(lowTemperatureF = 42.0, recoveryMargin = 3.0))
    private val sensor = GoveeSensor("probe", "H5179", "Bench", 71.0, 75.0, true, System.currentTimeMillis(),house.id,"Zone 2")

    private fun seed(): Pair<ByteArray,List<HistoryReading>> {
        secure.saveCloudEnrollment(enrollment)
        prefs.save(settings)
        zones.assign(sensor.id, sensor.zone)
        current.save(listOf(sensor))
        ForecastAlerts(context).save(rules)
        val rows = (1..2200).map { HistoryReading(sensor.id,sensor.name,70.0,80.0,sensor.updatedAtEpochMs - it) }
        HistoryStore(context,"_standalone").use { it.importReadings(rows) }
        val stream = ByteArrayOutputStream()
        assertEquals(rows.size,CustomerBackup(context,enrollment).write(stream, pass, scope))
        return stream.toByteArray() to rows
    }

    @Test fun restoreIntoReplacementMergesFullHistoryAndPersists() {
        val (file,rows) = seed()
        val fresh = sensor.copy(updatedAtEpochMs = sensor.updatedAtEpochMs + 1000, temperatureF = 78.0)
        val replacement = enrollment.copy(deviceId = "replacement-client")
        secure.saveCloudEnrollment(replacement)
        secure.saveSensorKey(replacement, "replacement-fixture-key")
        prefs.clear()
        zones.clear()
        current.save(listOf(fresh))
        HistoryStore(context,"_standalone").use { db -> db.clear(); db.insert(fresh) }
        repeat(2) {
            assertEquals(2200,CustomerBackup(context,replacement).restore(file.inputStream(),pass,scope))
        }
        assertEquals(settings,prefs.load())
        assertEquals("Zone 2", zones.zone("probe"))
        assertEquals(listOf(fresh), current.load())
        assertEquals(rules, ForecastAlerts(context).load())
        assertEquals(replacement, secure.cloudEnrollment())
        assertEquals("replacement-fixture-key", secure.sensorKey(replacement))
        HistoryStore(context,"_standalone").use { db ->
            val restored = db.recent(2500)
            assertEquals(2201,restored.size)
            assertEquals(rows, restored.drop(1))
            assertNull(db.pendingRestore())
        }
        assertEquals(AppPreferences(), AppPreferencesStore(context).load())
    }

    @Test fun damagedTruncatedWrongPasswordAndForeignFilesLeaveDestinationIntact() {
        val (file,_) = seed()
        prefs.clear()
        zones.assign("probe","other")
        HistoryStore(context,"_standalone").use { db -> db.clear(); db.insert(sensor) }
        val corrupt = file.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        val backup = CustomerBackup(context,enrollment)
        for ((bytes,pwd,owner) in listOf(Triple(corrupt,pass,scope),Triple(file.copyOf(file.size - 12),pass,scope),
            Triple(file,"wrong password".toCharArray(),scope),Triple(file,pass,scope.copy(tenant="unrelated")),
            Triple(file,pass,scope.copy(house="another")),Triple(file,pass,scope.copy(kind="viewer")))) {
            assertTrue(runCatching { backup.restore(bytes.inputStream(),pwd,owner) }.isFailure)
            assertEquals(AppPreferences(), prefs.load())
            assertEquals("other",zones.zone("probe"))
            HistoryStore(context,"_standalone").use { assertEquals(1,it.recent().size); assertNull(it.pendingRestore()) }
        }
    }

    @Test fun cloudSnapshotRejectsDamagedHistoryBeforeReplacingSettings() {
        val store = studio.junecity.greenhouse.cloud.GreenhouseBackupStore(context)
        AppPreferencesStore(context).save(settings)
        val file = store.snapshot()
        file.getJSONObject("preferences").put("zip","00000")
        file.getJSONArray("history").put(org.json.JSONObject())
        assertTrue(runCatching { store.restore(file) }.isFailure)
        assertEquals(settings,AppPreferencesStore(context).load())
    }

    @Test fun committedRestoreJournalReplaysSettingsAfterAnInterruption() {
        secure.saveCloudEnrollment(enrollment)
        val meta = org.json.JSONObject().put("kind", "standalone").put("destination", enrollment.deviceId)
            .put("preferences", AppPreferencesStore(context,"_standalone").apply { save(settings) }.exportJson())
            .put("zones", org.json.JSONObject().put("probe","Zone 2")).put("sensors", CurrentSensorCodec.encode(listOf(sensor)))
            .put("forecast", org.json.JSONObject().put("low",38).put("high",92).put("cold",false)
                .put("heat",false).put("reminder",false).put("minute",555))
        prefs.clear()
        HistoryStore(context,"_standalone").use { it.mergeBackup(meta.toString()) {} }
        CustomerBackup(context,enrollment).resumePendingRestore()
        assertEquals(settings,prefs.load())
        assertEquals("Zone 2",zones.zone("probe"))
        assertEquals(rules,ForecastAlerts(context).load())
        HistoryStore(context,"_standalone").use { assertNull(it.pendingRestore()) }
    }

    @Test fun viewerBackupRestoresOnlyForecastPreferences() {
        val viewer = enrollment.copy(localSensors=false,greenhouseId="paired")
        secure.saveCloudEnrollment(viewer)
        val backup = CustomerBackup(context,viewer)
        val viewerScope = scope.copy(kind="viewer")
        ForecastAlerts(context).save(rules)
        val file = ByteArrayOutputStream()
        assertEquals(0,backup.write(file,pass,viewerScope))
        AppPreferencesStore(context).save(settings)
        ForecastAlerts(context).save(ForecastRules())
        backup.restore(file.toByteArray().inputStream(), pass, viewerScope)
        assertEquals(rules,ForecastAlerts(context).load())
        assertEquals(settings,AppPreferencesStore(context).load())
    }
}
