package studio.junecity.greenhouse.sensors

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import studio.junecity.greenhouse.cloud.CloudDeviceRole
import studio.junecity.greenhouse.data.AlertNotifier
import studio.junecity.greenhouse.data.HistoryStore
import studio.junecity.greenhouse.model.GoveeSensor
import studio.junecity.greenhouse.settings.*
import studio.junecity.greenhouse.widget.ClimateCardRepository

/** The app, collecting service and background work share a single request and commit path. */
internal object CurrentGoveeReadings {
    private val gate = Mutex()
    suspend fun refresh(context: Context, owner: CloudEnrollment?, provider: SensorProvider = GoveeSensorProvider(),
                        force: Boolean = false): List<GoveeSensor>? = withContext(Dispatchers.IO) {
        gate.withLock {
            val secure = SecureSettingsStore(context)
            if (secure.cloudEnrollment() != owner || (owner?.role == CloudDeviceRole.ViewerClient && !owner.isStandaloneClient())) return@withLock null
            val key = secure.sensorKey(owner) ?: return@withLock null
            val suffix = if (owner.isStandaloneClient()) "_standalone" else ""
            val store = CurrentSensorStore(context, suffix)
            val current = store.load()
            val cloud = current.filterNot { it.isLocalRadio() }
            val now = System.currentTimeMillis()
            if (!force && cloud.isNotEmpty() && cloud.all { it.online && now - it.updatedAtEpochMs in 0 until 50_000L }) return@withLock current

            val prefs = AppPreferencesStore(context, suffix)
            val zones = ZoneAssignmentStore(context, suffix)
            val readings = try {
                provider.collectReadings(SensorProviderCredential.ApiKey(key), prefs.load(), current, store.excludedIds(), zones::zone)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                ClimateCardRepository.feedResult(context, owner, failed = true)
                throw e
            }
            fun commit(): List<GoveeSensor>? {
                if (secure.cloudEnrollment() != owner || secure.sensorKey(owner) != key) return null
                val active = store.replaceGovee(readings.withCurrentAssignments(prefs.load(), zones::zone))
                val alerts = AlertNotifier(context, suffix)
                HistoryStore(context, suffix).use { history ->
                    active.filterNot { it.isLocalRadio() }.forEach {
                        if (it.isFresh()) history.insert(it)
                        alerts.evaluate(it, owner)
                    }
                }
                ClimateCardRepository.feedResult(context, owner, failed = false)
                return active
            }
            if (owner == null) commit() else secure.withCurrentEnrollment(owner, ::commit)
        }
    }
}
