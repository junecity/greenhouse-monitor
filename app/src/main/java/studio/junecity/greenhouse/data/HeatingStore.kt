package studio.junecity.greenhouse.data

import android.content.Context
import androidx.work.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import studio.junecity.greenhouse.cloud.CloudDeviceRole
import studio.junecity.greenhouse.model.inSavedHouses
import studio.junecity.greenhouse.settings.*
import java.time.LocalDate
import java.util.concurrent.TimeUnit

// Remote device IDs can repeat across tenants. Keep each authorized server archive separate.
internal fun viewerHistorySuffix(owner: CloudEnrollment): String {
    val identity = listOf(owner.tenantId, owner.deviceId, owner.greenhouseId).joinToString("\u0000")
    return "_viewer_" + java.security.MessageDigest.getInstance("SHA-256")
        .digest(identity.toByteArray()).joinToString("") { "%02x".format(it) }
}

internal class HeatingStore(context: Context, owner: CloudEnrollment?, greenhouse: String) {
    internal val namespace = listOf(owner?.tenantId, owner?.deviceId, owner?.greenhouseId,
        owner.isStandaloneClient(), greenhouse).joinToString("|")
    private val prefs = context.getSharedPreferences("night_heating", Context.MODE_PRIVATE)
    fun settings(): HeatingSettings = HeatingSettings(
        EnergyUnit.entries.find { it.name == prefs.getString("$namespace.unit", null) } ?: EnergyUnit.Kwh)
    val fuelFlow = kotlinx.coroutines.flow.callbackFlow {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "$namespace.unit") trySend(settings().unit)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        trySend(settings().unit)
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    fun save(value: HeatingSettings) {
        prefs.edit()
            .putString("$namespace.unit", value.unit.name)
            .remove("$namespace.price").apply()
    }

    fun history(zip: String): List<ThermalPoint> {
        val raw = prefs.getString("$namespace.$zip.points", null) ?: return emptyList()
        return runCatching {
            val rows = JSONArray(raw)
            List(rows.length()) { i ->
                val row = rows.getJSONArray(i)
                val ids = row.getJSONArray(4)
                ThermalPoint(LocalDate.parse(row.getString(0)), row.getLong(1), row.getDouble(2),
                    row.getDouble(3), List(ids.length()) { ids.getString(it) })
            }
        }.getOrDefault(emptyList())
    }

    // Foreground sampling and WorkManager may write the same greenhouse concurrently.
    fun update(zip: String, points: List<ThermalPoint>, now: Long): List<ThermalPoint> = synchronized(trainingLock) {
        val before = history(zip)
        val merged = (before + points).associateBy { it.time }.values
            .filter { it.time >= now - TRAINING_DAYS * 24 * HEATING_HOUR }.sortedBy { it.time }
        if (before == merged) return@synchronized merged
        val rows = JSONArray()
        merged.forEach { p -> rows.put(JSONArray().put(p.date.toString()).put(p.time).put(p.indoor)
            .put(p.outdoor).put(JSONArray(p.sensors))) }
        check(prefs.edit().putString("$namespace.$zip.points", rows.toString()).commit())
        merged
    }

    companion object {
        private val trainingLock = Any()
        const val TRAINING_DAYS = 90L
    }
}

// A delayed run can reconstruct up to seven nights from the existing collector and weather service.
class NightHeatingWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val secure = SecureSettingsStore(applicationContext)
        val owner = secure.cloudEnrollment()
        val pairedViewer = owner?.role == CloudDeviceRole.ViewerClient && !owner.isStandaloneClient()
        if (pairedViewer && owner?.greenhouseId.isNullOrBlank()) return@withContext Result.success()
        val suffix = if (owner.isStandaloneClient()) "_standalone" else ""
        val preferences = AppPreferencesStore(applicationContext, suffix)
        val prefs = preferences.load()
        val sensors = CurrentSensorStore(applicationContext, suffix).load().inSavedHouses(prefs)
        if (sensors.isEmpty()) return@withContext Result.success()
        try {
            val archiveSuffix = if (pairedViewer) viewerHistorySuffix(requireNotNull(owner)) else suffix
            val readings = HistoryStore(applicationContext, archiveSuffix).use { it.weekReadings() }
            if (readings.isEmpty()) return@withContext Result.success()
            val weather = StationWeather(applicationContext).update(LiveWeatherProvider().current(prefs.zipCode))
            if (secure.cloudEnrollment() != owner || preferences.load() != prefs) return@withContext Result.success()
            val now = System.currentTimeMillis()
            for (g in prefs.greenhouses) {
                val ids = sensors.filter { it.greenhouseId == g.id }.map { it.id }.toSet()
                val points = NightHeating.observations(weather, readings, ids, now)
                val store = HeatingStore(applicationContext, owner, g.id)
                val training = store.update(prefs.zipCode, points, now).filter { point -> point.sensors.all(ids::contains) }
                val fuel = store.settings().unit
                val price = FuelPrices(applicationContext).refresh(fuel, weather.stateCode) ?: continue
                if (secure.cloudEnrollment() != owner || preferences.load() != prefs) return@withContext Result.success()
                EnergyArchive(applicationContext).use { it.record(store.namespace, g, weather, training, fuel, price, now) }
            }
            Result.success()
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { Result.retry() }
    }

    companion object {
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<NightHeatingWorker>(12, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("night_heating_history",
                ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
