package studio.junecity.greenhouse.sensors

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import studio.junecity.greenhouse.data.AlertNotifier
import studio.junecity.greenhouse.data.HistoryStore
import studio.junecity.greenhouse.model.GoveeSensor
import studio.junecity.greenhouse.model.monitorsSensor
import studio.junecity.greenhouse.settings.*
import studio.junecity.greenhouse.widget.ClimateCardRepository

/** The app, collecting service and background work share a single request and commit path. */
internal object CurrentGoveeReadings {
    private val gate = Mutex()
    suspend fun refresh(context: Context, owner: CloudEnrollment?, provider: SensorProvider = GoveeSensorProvider(),
                        force: Boolean = false, authenticatedAs: CloudEnrollment? = owner): List<GoveeSensor>? = withContext(Dispatchers.IO) {
        gate.withLock {
            val secure = SecureSettingsStore(context)
            val access = CollectorEnrollment(context, owner, authenticatedAs)
            val key = access.whileCurrent { secure.sensorKey(owner) } ?: return@withLock null
            val suffix = if (owner.isStandaloneClient()) "_standalone" else ""
            val store = CurrentSensorStore(context, suffix)
            val current = store.load()
            val cloud = current.filterNot { it.isLocalRadio() }
            val knownIds = if (authenticatedAs != owner) cloud.mapTo(mutableSetOf()) { it.id } else null
            if (knownIds != null && knownIds.isEmpty()) return@withLock current
            val now = System.currentTimeMillis()
            if (!force && cloud.isNotEmpty() && cloud.all { it.online && now - it.updatedAtEpochMs in 0 until 50_000L }) return@withLock current

            val prefs = AppPreferencesStore(context, suffix)
            val zones = ZoneAssignmentStore(context, suffix)
            val readings = try {
                provider.collectReadings(SensorProviderCredential.ApiKey(key), prefs.loadForMonitoring(owner), current, store.excludedIds(), knownIds, zones::zone)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                ClimateCardRepository.feedResult(context, owner, failed = true)
                throw e
            }
            fun commit(): List<GoveeSensor>? {
                if (secure.sensorKey(owner) != key) return null
                val latest = prefs.loadForMonitoring(owner)
                val accepted = readings.withCurrentAssignments(latest, zones::zone).map { sensor ->
                    if (latest.monitorsSensor(sensor)) sensor
                    else (current.find { it.id == sensor.id } ?: sensor.copy(temperatureF = null, humidityPercent = null, updatedAtEpochMs = 0))
                        .copy(online = false, greenhouseId = sensor.greenhouseId)
                }
                val active = store.replaceGovee(accepted)
                val alerts = AlertNotifier(context, suffix)
                HistoryStore(context, suffix).use { history ->
                    active.filter { !it.isLocalRadio() && latest.monitorsSensor(it) }.forEach {
                        if (it.isFresh()) history.insert(it)
                        alerts.evaluate(it, owner)
                    }
                }
                ClimateCardRepository.feedResult(context, owner, failed = false)
                return active
            }
            access.whileCurrent(::commit)
        }
    }
}
