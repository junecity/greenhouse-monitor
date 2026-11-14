package studio.junecity.greenhouse

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import studio.junecity.greenhouse.data.*
import studio.junecity.greenhouse.model.*
import studio.junecity.greenhouse.settings.*
import studio.junecity.greenhouse.sensors.withCurrentAssignments
import studio.junecity.greenhouse.sensors.collectReadings
import studio.junecity.greenhouse.sensors.GoveeSensorProvider
import studio.junecity.greenhouse.sensors.SensorProviderCredential
import studio.junecity.greenhouse.cloud.*

class GreenhouseViewModel internal constructor(app:Application, private val govee:studio.junecity.greenhouse.sensors.SensorProvider):AndroidViewModel(app){
    constructor(app:Application):this(app,GoveeSensorProvider())
    private class SensorStores(app: Application, suffix: String) {
        val preferences = AppPreferencesStore(app, suffix)
        val zones = ZoneAssignmentStore(app, suffix)
        val sensors = CurrentSensorStore(app, suffix)
        val history = HistoryStore(app, suffix)
        val notifier = AlertNotifier(app, suffix)
    }
    private val secure = SecureSettingsStore(app)
    private val paired = SensorStores(app, "")
    private val single = SensorStores(app, "_standalone")
    private var stores = if (secure.cloudEnrollment().isStandaloneClient()) single else paired
    private val preferences get() = stores.preferences
    private fun viewerArchive(enrollment: CloudEnrollment?) =
        enrollment?.takeIf { it.role == CloudDeviceRole.ViewerClient && !it.isStandaloneClient() }?.let(::viewerHistorySuffix)
    private var currentViewerArchive = viewerArchive(secure.cloudEnrollment())

    private val legacyZones get() = stores.zones
    private val currentSensors get() = stores.sensors
    private var weatherJob: kotlinx.coroutines.Job? = null
    private var requestedZip: String? = null
    private val weather = LiveWeatherProvider()
    init {
        studio.junecity.greenhouse.backup.CustomerBackup(app, secure.cloudEnrollment()).resumePendingRestore()
    }
    private val volumeOpen = MutableStateFlow(false)
    val volumeVisible = volumeOpen.asStateFlow()
    private val _state = MutableStateFlow(restoredState())
    val state = _state.asStateFlow()
    private var cloudSyncing = false
    private var sensorJob: kotlinx.coroutines.Job? = null

    private fun restoredState(): GreenhouseUiState {
        val enrollment = secure.cloudEnrollment()
        val viewer = enrollment?.role == CloudDeviceRole.ViewerClient
        val prefs = preferences.load()
        return GreenhouseUiState(standalone = enrollment.isStandaloneClient(), sensors = currentSensors.load().inSavedHouses(prefs),
            preferences = if (viewer) prefs.copy(cameraMode = CameraMode.ViewerClient) else prefs,
            goveeConnected = if (viewer && !enrollment.isStandaloneClient()) currentSensors.load().any { it.isFresh() }
                else secure.sensorKey(enrollment) != null)
    }

    fun reloadAfterRestore() {
        sensorJob?.cancel()
        weatherJob?.cancel()
        _state.value = restoredState().copy(screen = _state.value.screen)
        loadHistory()
        refreshWeather(true)
    }

    fun selectStores() {
        val enrollment = secure.cloudEnrollment()
        val selected = if (enrollment.isStandaloneClient()) single else paired
        val archive = viewerArchive(enrollment)
        if (stores != selected || archive != currentViewerArchive) {
            currentViewerArchive = archive
            sensorJob?.cancel()
            stores = selected
            _state.value = restoredState().copy(screen = _state.value.screen)
        }
    }

    init {
        loadHistory()
        SensorSyncWorker.schedule(app)
        NightHeatingWorker.schedule(app)
        ForecastAlerts(app).schedule()
        refreshWeather(true)

        viewModelScope.launch {
            while (true) {
                delay(60_000L)
                if (secure.sensorKey(secure.cloudEnrollment()) != null) refreshSensors(true)
            }
        }
        viewModelScope.launch { while (true) { delay(15*60_000L); refreshWeather(true) } }
    }
    fun navigate(s:AppScreen){_state.value=_state.value.copy(screen=s,message=null);if(s==AppScreen.History || s==AppScreen.Zones || s==AppScreen.Energy)loadHistory();if(s==AppScreen.Energy)refreshWeather(true)}
    fun selectMetric(m:Metric){_state.value=_state.value.copy(metric=m)}
    fun completeCloudEnrollment(role:CloudDeviceRole){
        selectStores()
        if (role == CloudDeviceRole.ViewerClient) {
            secure.clearGoveeApiKey()
            secure.clearStandaloneKey()
            for (storage in listOf(single, paired)) {
                storage.zones.clear()
                storage.sensors.clear()
                storage.history.clear()
                storage.preferences.clear()
            }
            _state.value = restoredState()
        } else {
            // Enrollment can restore a saved server backup before handing control to the monitor.
            _state.value = restoredState()
            updatePreferences { it.copy(cameraMode = CameraMode.CameraServer) }
        }
    }

    internal fun applyQuickStart(setup: QuickStartData) {
        val owner = secure.cloudEnrollment() ?: return
        val active = if (owner.role == CloudDeviceRole.ViewerClient) owner.copy(localSensors = true) else owner
        if (active != owner) secure.saveCloudEnrollment(active)
        selectStores()
        val house = _state.value.preferences.activeGreenhouse
        updateGreenhouse(house.name, setup.length, setup.width)
        updatePreferences { prefs ->
            prefs.copy(greenhouses = prefs.greenhouses.map { if (it.id == house.id) it.copy(heightFeet = setup.height) else it })
        }
        saveZip(setup.zip)
        HeatingStore(getApplication(), active, house.id).save(HeatingSettings(setup.unit))
        if (setup.key.isNotBlank()) saveApiKey(setup.key)
    }

    fun saveApiKey(key: String) {
        val enrollment = secure.cloudEnrollment()
        if (enrollment?.role == CloudDeviceRole.ViewerClient && !enrollment.isStandaloneClient()) return
        val source = if(enrollment.isStandaloneClient() && key.isNotBlank()) enrollment!!.copy(localSensors=true) else enrollment
        if(source!=enrollment)secure.saveCloudEnrollment(requireNotNull(source))
        sensorJob?.cancel()
        sensorJob = null
        secure.saveSensorKey(source, key)
        _state.value = _state.value.copy(goveeConnected = key.isNotBlank())
        if (key.isNotBlank()) refreshSensors()
    }
    fun testConnection() = refreshSensors()

    fun refreshSensors(silent: Boolean = false) {
        selectStores()
        val owner = secure.cloudEnrollment()
        if (owner?.role == CloudDeviceRole.ViewerClient && !owner.isStandaloneClient()) {
            syncFromServer(silent)
            return
        }
        val key = secure.sensorKey(owner) ?: return
        if (sensorJob?.isActive == true) return
        val storage = stores
        sensorJob = viewModelScope.launch {
            if (!silent) _state.value = _state.value.copy(refreshing = true, message = null)
            try {
                val credential = SensorProviderCredential.ApiKey(key)
                val prefs = _state.value.preferences
                val collected = govee.collectReadings(credential, prefs, storage.sensors.load(), storage.zones::zone)
                if (secure.cloudEnrollment() != owner || secure.sensorKey(owner) != key) return@launch
                val latest = _state.value.preferences
                val sensors = collected.withCurrentAssignments(latest, storage.zones::zone)
                storage.sensors.save(sensors)
                ensurePositions(sensors)
                _state.value = _state.value.copy(sensors = sensors, goveeConnected = true, message = "Updated ${sensors.size} sensors")
                withContext(Dispatchers.IO) {
                    sensors.forEach { if (it.isFresh()) storage.history.insert(it); storage.notifier.evaluate(it) }
                    if (!owner.isStandaloneClient()) HeartbeatHealthStore(getApplication()).save(sensors,
                        prefs.notifications.lowTemperatureF, prefs.notifications.highTemperatureF)
                }
                if (stores != storage) return@launch
                loadHistory()
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (error: Exception) { _state.value = _state.value.copy(message = error.message ?: "Sensor refresh failed") }
            finally { if (stores == storage) _state.value = _state.value.copy(refreshing = false) }
        }
    }
    suspend fun refreshWhileVisible() {
        val owner = secure.cloudEnrollment()
        if (owner?.role != CloudDeviceRole.ViewerClient || owner.localSensors) {
            refreshSensors(true)
            return
        }
        selectStores()
        pollClientReadings(
            refresh = { fetchServerSnapshot(silent = true) },
            intervalMinutes = { _state.value.preferences.activeRefreshMinutes }
        )
    }

    fun syncFromServer(silent: Boolean = false) {
        viewModelScope.launch { fetchServerSnapshot(silent) }
    }

    private suspend fun fetchServerSnapshot(silent: Boolean): Boolean {
        selectStores()
        val enrollment = secure.cloudEnrollment() ?: return true
        if (enrollment.role != CloudDeviceRole.ViewerClient) return true
        if (enrollment.localSensors) return true
        if (cloudSyncing) return false
        val configuration = CloudRuntime.configurationOrNull() ?: return true
        cloudSyncing = true
        try {
            if (!silent) _state.value = _state.value.copy(refreshing = true, message = null)
            val gateway = gatewayForEnrollment(getApplication(), configuration, enrollment)
            val greenhouseId = resolveClientGreenhouse(enrollment.greenhouseId, null, gateway::devices)
            val snapshot = if (greenhouseId.isBlank()) null else gateway.greenhouseBackup(greenhouseId)
            if (secure.cloudEnrollment() != enrollment) return false
            if (greenhouseId != enrollment.greenhouseId) {
                secure.saveCloudEnrollment(enrollment.copy(greenhouseId = greenhouseId))
                selectStores()
            }
            if (snapshot == null) {
                if (!silent) _state.value = _state.value.copy(message = "Waiting for greenhouse server data")
                return greenhouseId.isBlank()
            }
            val suffix = viewerHistorySuffix(enrollment.copy(greenhouseId = greenhouseId))
            withContext(Dispatchers.IO) {
                GreenhouseBackupStore(getApplication(), suffix).restore(snapshot, CameraMode.ViewerClient)
            }
            if (secure.cloudEnrollment() != enrollment.copy(greenhouseId=greenhouseId) || stores != paired) return false
            val syncedSensors = paired.sensors.load()
            _state.value = _state.value.copy(
                preferences = paired.preferences.load().copy(cameraMode = CameraMode.ViewerClient),
                sensors = syncedSensors,
                goveeConnected = syncedSensors.any { it.isFresh() },
                message = if (silent) _state.value.message else "Synchronized from greenhouse server"
            )
            loadHistory()
            refreshWeather(true)
            return true
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (!silent) _state.value = _state.value.copy(message = error.message ?: "Server synchronization failed")
            return false
        } finally {
            cloudSyncing = false
            if (!silent) _state.value = _state.value.copy(refreshing = false)
        }
    }
    private fun ensurePositions(sensors:List<GoveeSensor>){val p=_state.value.preferences;val g=p.activeGreenhouse;var positions=g.sensorPositions;sensors.filter{it.greenhouseId==g.id&&it.id !in positions}.forEachIndexed{i,s->val defaults=listOf(.2 to .18,.8 to .18,.4 to .62,.6 to .82);val n=defaults[i%defaults.size];positions=positions+(s.id to g.coercePoint(n.first*g.planLengthFeet,n.second*g.planWidthFeet))};if(positions!=g.sensorPositions)savePreferences(p.copy(greenhouses=p.greenhouses.map{if(it.id==g.id)it.copy(sensorPositions=positions)else it}))}
    fun moveSensor(id:String,nx:Double,ny:Double){
        val owner = secure.cloudEnrollment()
        if (owner?.role == CloudDeviceRole.ViewerClient && !owner.isStandaloneClient()) return
        val p=_state.value.preferences;val g=p.activeGreenhouse;val projected=g.coercePoint(nx.coerceIn(.03,.97)*g.planLengthFeet,ny.coerceIn(.05,.95)*g.planWidthFeet);val pos=projected.copy(heightFeet=g.sensorPositions[id]?.heightFeet);savePreferences(p.copy(greenhouses=p.greenhouses.map{if(it.id==g.id)it.copy(sensorPositions=it.sensorPositions+(id to pos)) else it}))}
    fun assignZone(sensorId: String, zone: String) {
        val owner = secure.cloudEnrollment()
        if (owner?.role == CloudDeviceRole.ViewerClient && !owner.isStandaloneClient()) return
        val prefs = _state.value.preferences
        val house = prefs.activeGreenhouse
        legacyZones.assign(sensorId, zone)
        savePreferences(prefs.copy(sensorGreenhouseIds = prefs.sensorGreenhouseIds + (sensorId to house.id)))
        val updated = _state.value.sensors.map { if (it.id == sensorId) it.copy(zone = zone, greenhouseId = house.id) else it }
        currentSensors.save(updated)
        _state.value = _state.value.copy(sensors = updated)
        ensurePositions(updated)
    }
    fun updateGreenhouse(name:String,length:Double,width:Double){if(length !in 0.1..480.0 || width !in 0.1..480.0)return;val p=_state.value.preferences;val old=p.activeGreenhouse;val geometry=old.copy(name=name.ifBlank{old.name},lengthFeet=length,widthFeet=width);val updated=geometry.copy(sensorPositions=old.sensorPositions.mapValues{(_,v)->geometry.reproject(v,old)});savePreferences(p.copy(greenhouses=p.greenhouses.map{if(it.id==old.id)updated else it}))}
    fun updatePreferences(transform:(AppPreferences)->AppPreferences)=savePreferences(transform(_state.value.preferences))
    private fun savePreferences(p: AppPreferences) {
        val previous = _state.value
        preferences.save(p)
        val removed = previous.preferences.greenhouses.any { old -> p.greenhouses.none { it.id == old.id } }
        if (removed) {
            val sensors = currentSensors.load().inSavedHouses(p)
            currentSensors.save(sensors)
            _state.value = previous.copy(preferences = p, sensors = sensors)
        } else {
            _state.value = previous.copy(preferences = p)
        }
    }
    fun saveZip(zip:String){if(!Regex("\\d{5}").matches(zip)){_state.value=_state.value.copy(message="Enter a valid five-digit ZIP code");return};savePreferences(_state.value.preferences.copy(zipCode=zip));refreshWeather()}
    fun refreshWeather(silent: Boolean = false) {
        val zip = _state.value.preferences.zipCode
        val last = _state.value.weather
        if (zip == requestedZip && weatherJob?.isActive == true) return
        if (silent && last.zipCode == zip && !last.stale && last.days.isNotEmpty() &&
            System.currentTimeMillis() - last.updatedAtEpochMs in 0 until 15 * 60_000L) return
        weatherJob?.cancel()
        requestedZip = zip
        if (last.zipCode != zip) _state.value = _state.value.copy(weather = WeatherReading(zip, "Outside ($zip)", null, "Unavailable"))
        weatherJob = viewModelScope.launch {
            try {
                val reading = weather.current(zip)
                if (_state.value.preferences.zipCode != zip) return@launch
                _state.value = _state.value.copy(weather = reading)
                ForecastAlerts(getApplication()).evaluate(reading)
                val revised = StationWeather(getApplication()).update(reading)
                if (_state.value.weather == reading && _state.value.preferences.zipCode == zip)
                    _state.value = _state.value.copy(weather = revised)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (_state.value.preferences.zipCode == zip) {
                    _state.value = _state.value.copy(weather = _state.value.weather.copy(stale = true),
                        message = if (silent) _state.value.message else "Outdoor weather is unavailable. Try again.")
                }
            }
        }
    }
    internal suspend fun historyDay(day: Long): List<HistoryReading> {
        val owner = secure.cloudEnrollment()
        val storage = stores
        val rows = withContext(Dispatchers.IO) {
            if (owner?.role == CloudDeviceRole.ViewerClient && !owner.isStandaloneClient()) {
                HistoryStore(getApplication(), viewerHistorySuffix(owner)).use { it.dayReadings(day) }
            } else storage.history.dayReadings(day)
        }
        return if (storage == stores && secure.cloudEnrollment() == owner) rows else emptyList()
    }

    private fun needsWeekHistory() = _state.value.screen == AppScreen.Dashboard || _state.value.screen == AppScreen.Energy || _state.value.screen == AppScreen.History || (volumeOpen.value && _state.value.screen == AppScreen.Zones)
    fun setVolumeOpen(open: Boolean) {
        if (volumeOpen.value == open) return
        volumeOpen.value = open
        loadHistory()
    }

    private fun loadHistory() {
        val storage = stores
        val owner = secure.cloudEnrollment()
        viewModelScope.launch {
            val forHistory = needsWeekHistory()
            val showDaily = _state.value.screen == AppScreen.History
            fun read(db: HistoryStore) = Pair(
                if (forHistory) db.weekReadings() else db.dashboardReadings(),
                if (showDaily) db.dailyReadings() else emptyList()
            )
            val (rows, daily) = withContext(Dispatchers.IO) {
                if (owner?.role == CloudDeviceRole.ViewerClient && !owner.isStandaloneClient()) {
                    HistoryStore(getApplication(), viewerHistorySuffix(owner)).use(::read)
                } else read(storage.history)
            }
            if (forHistory != needsWeekHistory() || showDaily != (_state.value.screen == AppScreen.History)) return@launch
            if (storage == stores && owner == secure.cloudEnrollment()) {
                _state.value = _state.value.copy(history = rows, dailyHistory = daily)
            }
        }
    }
}


/** Called afresh for every foreground visit after the client device has been authorized. */
internal suspend fun pollClientReadings(
    refresh: suspend () -> Boolean,
    intervalMinutes: () -> Int,
    wait: suspend (Long) -> Unit = { delay(it) }
) {
    var retryDelay = 3_000L
    while (true) {
        if (refresh()) {
            retryDelay = 3_000L
            wait(intervalMinutes() * 60_000L)
        } else {
            wait(retryDelay)
            retryDelay = (retryDelay * 2).coerceAtMost(30_000L)
        }
    }
}
