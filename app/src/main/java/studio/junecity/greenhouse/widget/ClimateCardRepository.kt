package studio.junecity.greenhouse.widget

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.launch
import studio.junecity.greenhouse.cloud.CloudDeviceRole
import studio.junecity.greenhouse.data.HeatingStore
import studio.junecity.greenhouse.data.HistoryStore
import studio.junecity.greenhouse.data.viewerHistorySuffix
import studio.junecity.greenhouse.model.*
import studio.junecity.greenhouse.sensors.SensorBluetooth
import studio.junecity.greenhouse.sensors.isLocalRadio
import studio.junecity.greenhouse.settings.*

/** Local projection of the existing collector and authenticated viewer stores. No network calls. */
internal class ClimateCardRepository(context: Context) {
    private val app = context.applicationContext
    private val secure = SecureSettingsStore(app)
    private val metadata = app.getSharedPreferences(STORE, Context.MODE_PRIVATE)

    fun load(owner: CloudEnrollment, houseId: String? = null, sensorId: String? = null,
             celsius: Boolean? = null, now: Long = System.currentTimeMillis()): GreenhouseWidgetUiState {
        fun unavailable(status: WidgetFeedStatus, message: String) =
            GreenhouseWidgetUiState(status = status, statusMessage = message, now = now, celsius = celsius ?: false)
        fun signedOut() = unavailable(WidgetFeedStatus.Empty, "Open Greenhouse Studio to set up")
        if (secure.cloudEnrollment() != owner) return signedOut()
        return try {
            val paired = owner.role == CloudDeviceRole.ViewerClient && !owner.isStandaloneClient()
            if (paired && !ClimateWidget.viewerReady(app, owner)) {
                val failed = metadata.getBoolean(failureKey(owner), false)
                return if (failed) unavailable(WidgetFeedStatus.Error, "Server readings could not refresh")
                    else unavailable(WidgetFeedStatus.Loading, "Waiting for server readings")
            }
            val suffix = if (owner.isStandaloneClient()) "_standalone" else ""
            val prefs = AppPreferencesStore(app, suffix).loadForMonitoring(owner)
            val house = if (houseId.isNullOrBlank()) prefs.activeGreenhouse else prefs.greenhouses.find { it.id == houseId }
            if (house == null) return unavailable(WidgetFeedStatus.Empty, "Choose a greenhouse")
            if (!house.isMonitoring(now)) return unavailable(WidgetFeedStatus.Empty, "Monitoring paused · ${house.name}")
            val all = CurrentSensorStore(app, suffix).load().inSavedHouses(prefs).filter { it.greenhouseId == house.id }
            val selected = all.filter { sensorId.isNullOrBlank() || it.id == sensorId }
            val internet = app.getSystemService(ConnectivityManager::class.java).let {
                it.getNetworkCapabilities(it.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
            }
            val sources = cardSources(selected, paired, internet,
                paired || selected.none { it.isLocalRadio() } || SensorBluetooth.allowed(app),
                metadata.getBoolean(failureKey(owner), false))
            val archive = if (paired) viewerHistorySuffix(owner) else suffix
            val trend = if (selected.isEmpty()) emptyList() else HistoryStore(app, archive).use {
                it.widgetTrend(selected.map { sensor -> sensor.id }.toSet(), now)
            }
            val training = HeatingStore(app, owner, house.id)
            val target = training.widgetTarget(prefs.zipCode, all.map { it.id }.toSet(), now)
            val cadence = if (paired) house.refreshMinutes else 1
            val value = climateCardState(
                selected.singleOrNull()?.name?.takeUnless { sensorId.isNullOrBlank() } ?: house.name, selected,
                trend, now, cadence, celsius ?: !prefs.temperatureFahrenheit, target, readWeather(prefs.zipCode),
                prefs.forAlertsIn(house.id), sources, sensorId?.takeIf(String::isNotBlank), house
            )
            if (secure.cloudEnrollment() != owner || (paired && !ClimateWidget.viewerReady(app, owner))) signedOut() else value
        } catch (_: Exception) {
            unavailable(WidgetFeedStatus.Error, "Could not read greenhouse data")
        }
    }

    internal fun weatherDue(zip: String, now: Long = System.currentTimeMillis()): Boolean =
        now - metadata.getLong("weather.$zip.checked", 0L) !in 0 until 20 * 60_000L &&
            now - metadata.getLong("weather.$zip.at", 0L) !in 0 until 20 * 60_000L

    private fun readWeather(zip: String): WidgetOutdoor? {
        val at = metadata.getLong("weather.$zip.at", 0L)
        if (at == 0L) return null
        return WidgetOutdoor(metadata.getString("weather.$zip.value", null)?.toDoubleOrNull(), at,
            metadata.getBoolean("weather.$zip.failed", false))
    }

    /** Invalidate on real changes. A minute tick only re-evaluates timestamps while observed. */
    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    fun observe(owner: CloudEnrollment, houseId: String? = null, sensorId: String? = null, celsius: Boolean? = null):
        Flow<GreenhouseWidgetUiState> = callbackFlow {
        val suffix = if (owner.isStandaloneClient()) "_standalone" else ""
        val archive = if (owner.role == CloudDeviceRole.ViewerClient && !owner.isStandaloneClient()) viewerHistorySuffix(owner) else suffix
        val writes = launch { HistoryStore.changes.filter { it == archive }.debounce(150L).collect { trySend(Unit) } }
        val stores = listOf("greenhouse_preferences_v2$suffix", "greenhouse_current_sensors$suffix",
            STORE, "widget_sync", "night_heating").map { app.getSharedPreferences(it, Context.MODE_PRIVATE) }
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> trySend(Unit) }
        stores.forEach { it.registerOnSharedPreferenceChangeListener(listener) }
        val network = app.getSystemService(ConnectivityManager::class.java)
        val networkChange = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) { trySend(Unit) }
            override fun onLost(network: Network) { trySend(Unit) }
        }
        network.registerDefaultNetworkCallback(networkChange)
        val unwatch = secure.watchEnrollment { trySend(Unit) }
        val clock = launch { while (true) { delay(60_000L); trySend(Unit) } }
        trySend(Unit)
        awaitClose {
            clock.cancel()
            writes.cancel()
            unwatch()
            network.unregisterNetworkCallback(networkChange)
            stores.forEach { it.unregisterOnSharedPreferenceChangeListener(listener) }
        }
    }.conflate().mapLatest { load(owner, houseId, sensorId, celsius) }.flowOn(Dispatchers.IO)

    companion object {
        private const val STORE = "climate_card_sources"
        private fun failureKey(owner: CloudEnrollment) = "failed.${owner.widgetScope()}"

        internal fun feedFailed(context: Context, owner: CloudEnrollment) =
            context.getSharedPreferences(STORE, Context.MODE_PRIVATE).getBoolean(failureKey(owner), false)

        fun feedResult(context: Context, owner: CloudEnrollment?, failed: Boolean) {
            if (owner == null || SecureSettingsStore(context).cloudEnrollment() != owner) return
            val store = context.getSharedPreferences(STORE, Context.MODE_PRIVATE)
            val key = failureKey(owner)
            if (store.getBoolean(key, false) == failed) return
            store.edit().putBoolean(key, failed).apply()
            ClimateWidget.changed(context)
        }

        // Weather is public and keyed only by the exact ZIP. The phone never reads another
        // greenhouse's location to fill a missing observation.
        fun weatherResult(context: Context, zip: String, reading: WeatherReading?) {
            val store = context.getSharedPreferences(STORE, Context.MODE_PRIVATE)
            val prefix = "weather.$zip"
            if (reading != null && reading.zipCode != zip) return
            val temperature = reading?.temperatureF?.takeIf(Double::isFinite)
            val at = reading?.currentTimeMs
            val edit = store.edit().putLong("$prefix.checked", System.currentTimeMillis())
            if (temperature != null && at != null && at > 0L) {
                if (at < store.getLong("$prefix.at", 0L)) return
                edit.putString("$prefix.value", temperature.toString()).putLong("$prefix.at", at)
                    .putBoolean("$prefix.failed", false)
            } else {
                if (store.getBoolean("$prefix.failed", false)) { edit.apply(); return }
                edit.putBoolean("$prefix.failed", true)
            }
            edit.apply()
            ClimateWidget.changed(context)
        }
    }
}
