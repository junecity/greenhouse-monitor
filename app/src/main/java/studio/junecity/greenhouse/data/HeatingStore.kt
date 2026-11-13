package studio.junecity.greenhouse.data

import android.content.Context
import androidx.work.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import studio.junecity.greenhouse.cloud.CloudDeviceRole
import studio.junecity.greenhouse.model.inSavedHouses
import studio.junecity.greenhouse.settings.*
import java.time.LocalDate
import java.util.concurrent.TimeUnit

internal class HeatingStore(context: Context, owner: CloudEnrollment?, greenhouse: String) {
    private val namespace = listOf(owner?.tenantId, owner?.deviceId, owner?.greenhouseId,
        owner.isStandaloneClient(), greenhouse).joinToString("|")
    private val prefs = context.getSharedPreferences("night_heating", Context.MODE_PRIVATE)
    fun settings(): HeatingSettings = HeatingSettings(
        if (prefs.getString("$namespace.unit", "") == "Therm") EnergyUnit.Therm else EnergyUnit.Kwh,
        prefs.getString("$namespace.price", null)?.toDoubleOrNull())
    fun save(value: HeatingSettings) {
        prefs.edit()
            .putString("$namespace.unit", value.unit.name)
            .putString("$namespace.price", value.price?.toString()).apply()
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

    fun update(zip: String, points: List<ThermalPoint>, now: Long) {
        val merged = (history(zip) + points).associateBy { it.time }.values
            .filter { it.time >= now - 30L * 24 * HEATING_HOUR }.sortedBy { it.time }
        val rows = JSONArray()
        merged.forEach { p -> rows.put(JSONArray().put(p.date.toString()).put(p.time).put(p.indoor)
            .put(p.outdoor).put(JSONArray(p.sensors))) }
        check(prefs.edit().putString("$namespace.$zip.points", rows.toString()).commit())
    }
}

// A delayed run can reconstruct up to seven nights from the existing collector and weather service.
class NightHeatingWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val secure = SecureSettingsStore(applicationContext)
        val owner = secure.cloudEnrollment()
        if (owner?.role == CloudDeviceRole.ViewerClient && !owner.isStandaloneClient()) return@withContext Result.success()
        val suffix = if (owner.isStandaloneClient()) "_standalone" else ""
        val preferences = AppPreferencesStore(applicationContext, suffix)
        val prefs = preferences.load()
        val sensors = CurrentSensorStore(applicationContext, suffix).load().inSavedHouses(prefs)
        if (sensors.isEmpty()) return@withContext Result.success()
        try {
            val readings = HistoryStore(applicationContext, suffix).use { it.weekReadings() }
            if (readings.isEmpty()) return@withContext Result.success()
            val weather = LiveWeatherProvider().current(prefs.zipCode)
            if (secure.cloudEnrollment() != owner || preferences.load() != prefs) return@withContext Result.success()
            val now = System.currentTimeMillis()
            for (g in prefs.greenhouses) {
                val ids = sensors.filter { it.greenhouseId == g.id }.map { it.id }.toSet()
                val points = NightHeating.observations(weather, readings, ids, now)
                HeatingStore(applicationContext, owner, g.id).update(prefs.zipCode, points, now)
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
