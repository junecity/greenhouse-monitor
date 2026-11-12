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
    private val legacyZones get() = stores.zones
    private val currentSensors get() = stores.sensors
    private val history get() = stores.history
    private val weather = LiveWeatherProvider()
    private val _state = MutableStateFlow(restoredState())
    val state = _state.asStateFlow()
    private var cloudSyncing = false
    private var sensorJob: kotlinx.coroutines.Job? = null

    private fun restoredState(): GreenhouseUiState {
        val enrollment = secure.cloudEnrollment()
        val viewer = enrollment?.role == CloudDeviceRole.ViewerClient
        val prefs = preferences.load()
        return GreenhouseUiState(standalone = enrollment.isStandaloneClient(), sensors = currentSensors.load(),
            preferences = if (viewer) prefs.copy(cameraMode = CameraMode.ViewerClient) else prefs,
            goveeConnected = if (viewer && !enrollment.isStandaloneClient()) currentSensors.load().any { it.isFresh() }
                else secure.sensorKey(enrollment) != null)
    }

    fun selectStores() {
        val selected = if (secure.cloudEnrollment().isStandaloneClient()) single else paired
        if (stores != selected) {
            sensorJob?.cancel()
            stores = selected
            _state.value = restoredState().copy(screen = _state.value.screen)
        }
    }

    init {
        loadHistory()
        SensorSyncWorker.schedule(app)
        refreshWeather(true)
        if (secure.sensorKey(secure.cloudEnrollment()) != null) refreshSensors()
        viewModelScope.launch { while (true) {
            delay(60_000L)
            selectStores()
            if (secure.sensorKey(secure.cloudEnrollment()) != null) refreshSensors(true)
        } }
        viewModelScope.launch { while (true) { delay(15*60_000L); refreshWeather(true) } }
    }
    fun navigate(s:AppScreen){_state.value=_state.value.copy(screen=s,message=null);if(s==AppScreen.History || s==AppScreen.Zones)loadHistory()}
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
                val sensors = govee.discover(credential).map { device ->
                    val old = _state.value.sensors.find { it.id == device.deviceId }
                    govee.read(credential, device).toLegacySensor(
                        greenhouseId = prefs.sensorGreenhouseIds[device.deviceId] ?: old?.greenhouseId ?: prefs.activeGreenhouse.id,
                        zone = storage.zones.zone(device.deviceId).takeUnless { it == "Unassigned" } ?: old?.zone ?: "Unassigned")
                }
                if (secure.cloudEnrollment() != owner || secure.sensorKey(owner) != key) return@launch
                withContext(Dispatchers.IO) {
                    storage.sensors.save(sensors)
                    sensors.forEach { if (it.isFresh()) storage.history.insert(it); storage.notifier.evaluate(it) }
                    if (!owner.isStandaloneClient()) HeartbeatHealthStore(getApplication()).save(sensors,
                        prefs.notifications.lowTemperatureF, prefs.notifications.highTemperatureF)
                }
                if (stores != storage) return@launch
                loadHistory()
                ensurePositions(sensors)
                _state.value = _state.value.copy(sensors = sensors, goveeConnected = true, message = "Updated ${sensors.size} sensors")
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (error: Exception) { _state.value = _state.value.copy(message = error.message ?: "Sensor refresh failed") }
            finally { if (stores == storage) _state.value = _state.value.copy(refreshing = false) }
        }
    }
    suspend fun refreshClientWhileVisible() {
        if (secure.cloudEnrollment()?.role != CloudDeviceRole.ViewerClient) return
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
                return true
            }
            val readings = withContext(Dispatchers.IO) {
                GreenhouseBackupStore(getApplication()).restore(snapshot, CameraMode.ViewerClient)
                paired.history.dashboardReadings()
            }
            if (secure.cloudEnrollment() != enrollment.copy(greenhouseId=greenhouseId) || stores != paired) return false
            val syncedSensors = paired.sensors.load()
            _state.value = _state.value.copy(
                preferences = paired.preferences.load().copy(cameraMode = CameraMode.ViewerClient),
                sensors = syncedSensors, history = readings,
                goveeConnected = syncedSensors.any { it.isFresh() },
                message = if (silent) _state.value.message else "Synchronized from greenhouse server"
            )
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
    fun moveSensor(id:String,nx:Double,ny:Double){val p=_state.value.preferences;val g=p.activeGreenhouse;val projected=g.coercePoint(nx.coerceIn(.03,.97)*g.planLengthFeet,ny.coerceIn(.05,.95)*g.planWidthFeet);val pos=projected.copy(heightFeet=g.sensorPositions[id]?.heightFeet?:0.0);savePreferences(p.copy(greenhouses=p.greenhouses.map{if(it.id==g.id)it.copy(sensorPositions=it.sensorPositions+(id to pos)) else it}))}
    fun assignZone(sensorId:String,zone:String){legacyZones.assign(sensorId,zone);val p=_state.value.preferences;val active=p.activeGreenhouse;savePreferences(p.copy(sensorGreenhouseIds=p.sensorGreenhouseIds+(sensorId to active.id)));_state.value=_state.value.copy(sensors=_state.value.sensors.map{if(it.id==sensorId)it.copy(zone=zone,greenhouseId=active.id)else it});ensurePositions(_state.value.sensors)}
    fun updateGreenhouse(name:String,length:Double,width:Double){if(length<=0||width<=0)return;val p=_state.value.preferences;val old=p.activeGreenhouse;val geometry=old.copy(name=name.ifBlank{old.name},lengthFeet=length,widthFeet=width);val updated=geometry.copy(sensorPositions=old.sensorPositions.mapValues{(_,v)->geometry.reproject(v,old)});savePreferences(p.copy(greenhouses=p.greenhouses.map{if(it.id==old.id)updated else it}))}
    fun updatePreferences(transform:(AppPreferences)->AppPreferences)=savePreferences(transform(_state.value.preferences))
    private fun savePreferences(p:AppPreferences){preferences.save(p);_state.value=_state.value.copy(preferences=p)}
    fun saveZip(zip:String){if(!Regex("\\d{5}").matches(zip)){_state.value=_state.value.copy(message="Enter a valid five-digit ZIP code");return};savePreferences(_state.value.preferences.copy(zipCode=zip));refreshWeather()}
    fun refreshWeather(silent:Boolean=false){val zip=_state.value.preferences.zipCode;viewModelScope.launch{runCatching{weather.current(zip)}.onSuccess{_state.value=_state.value.copy(weather=it)}.onFailure{if(!silent)_state.value=_state.value.copy(message=it.message);_state.value=_state.value.copy(weather=_state.value.weather.copy(stale=true))}}}
    private fun loadHistory() {
        val storage = stores
        val owner = secure.cloudEnrollment()
        viewModelScope.launch {
            val rows = withContext(Dispatchers.IO) { storage.history.dashboardReadings() }
            if (storage == stores && owner == secure.cloudEnrollment()) _state.value = _state.value.copy(history = rows)
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
