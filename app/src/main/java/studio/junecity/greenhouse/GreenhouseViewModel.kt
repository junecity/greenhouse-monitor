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

class GreenhouseViewModel(app:Application):AndroidViewModel(app){
    private val secure=SecureSettingsStore(app);private val preferences=AppPreferencesStore(app);private val legacyZones=ZoneAssignmentStore(app);private val currentSensors=CurrentSensorStore(app);private val govee=GoveeSensorProvider();private val weather=LiveWeatherProvider();private val history=HistoryStore(app);private val notifier=AlertNotifier(app)
    private val initialEnrollment=secure.cloudEnrollment();private val initialSensors=if(initialEnrollment?.role==CloudDeviceRole.ViewerClient)currentSensors.load() else emptyList()
    private val _state=MutableStateFlow(GreenhouseUiState(sensors=initialSensors,preferences=preferences.load(),goveeConnected=if(initialEnrollment?.role==CloudDeviceRole.ViewerClient)initialSensors.isNotEmpty() else secure.goveeApiKey()!=null));val state=_state.asStateFlow()
    private var cloudSyncing=false
    init{SensorSyncWorker.schedule(app);refreshWeather(true);if(secure.goveeApiKey()!=null&&initialEnrollment?.role!=CloudDeviceRole.ViewerClient)refreshSensors();viewModelScope.launch{while(true){delay(60_000L);if(secure.cloudEnrollment()?.role!=CloudDeviceRole.ViewerClient)refreshSensors(true)}};viewModelScope.launch{while(true){delay(_state.value.preferences.activeRefreshMinutes*60_000L);syncFromServer(true)}};viewModelScope.launch{while(true){delay(15*60_000L);refreshWeather(true)}}}
    fun navigate(s:AppScreen){_state.value=_state.value.copy(screen=s,message=null);if(s==AppScreen.History)loadHistory()}
    fun selectMetric(m:Metric){_state.value=_state.value.copy(metric=m)}
    fun completeCloudEnrollment(role:CloudDeviceRole){
        if(role==CloudDeviceRole.ViewerClient){
            secure.clearGoveeApiKey();legacyZones.clear();currentSensors.clear();history.clear();preferences.clear()
            val clean=AppPreferences(cameraMode=CameraMode.ViewerClient);preferences.save(clean)
            _state.value=GreenhouseUiState(preferences=clean,message="Pair a greenhouse server to begin monitoring")
            syncFromServer(false)
        }else updatePreferences{it.copy(cameraMode=CameraMode.CameraServer)}
    }
    fun saveApiKey(key:String){if(secure.cloudEnrollment()?.role==CloudDeviceRole.ViewerClient){_state.value=_state.value.copy(message="Configure sensors on the greenhouse server phone");return};secure.saveGoveeApiKey(key);_state.value=_state.value.copy(goveeConnected=key.isNotBlank(),message=if(key.isBlank())"Govee disconnected" else "API key saved securely");if(key.isNotBlank())refreshSensors()}
    fun testConnection()=refreshSensors()
    fun refreshSensors(silent:Boolean=false){if(secure.cloudEnrollment()?.role==CloudDeviceRole.ViewerClient){syncFromServer(silent);return};val key=secure.goveeApiKey()?:return;viewModelScope.launch{if(!silent)_state.value=_state.value.copy(refreshing=true,message=null);runCatching{val credential=SensorProviderCredential.ApiKey(key);val discovered=govee.discover(credential);val prefs=_state.value.preferences;discovered.map{device->val old=_state.value.sensors.firstOrNull{it.id==device.deviceId};val zone=old?.zone?:legacyZones.zone(device.deviceId);govee.read(credential,device).toLegacySensor(greenhouseId=prefs.sensorGreenhouseIds[device.deviceId]?:old?.greenhouseId?:prefs.activeGreenhouse.id,zone=zone)}}.onSuccess{sensors->withContext(Dispatchers.IO){currentSensors.save(sensors);sensors.forEach{if(it.isFresh())history.insert(it);notifier.evaluate(it)};val n=_state.value.preferences.notifications;HeartbeatHealthStore(getApplication()).save(sensors,n.lowTemperatureF,n.highTemperatureF)};ensurePositions(sensors);_state.value=_state.value.copy(sensors=sensors,goveeConnected=true,refreshing=false,message="Updated ${sensors.size} sensors")}.onFailure{_state.value=_state.value.copy(refreshing=false,message=it.message?:"Govee refresh failed")}}}
    fun syncFromServer(silent:Boolean=false){
        val enrollment=secure.cloudEnrollment()?:return
        if(enrollment.role!=CloudDeviceRole.ViewerClient||cloudSyncing)return
        val configuration=runCatching{CloudRuntime.configurationOrNull()}.getOrNull()?:return
        viewModelScope.launch{
            cloudSyncing=true
            if(!silent)_state.value=_state.value.copy(refreshing=true,message=null)
            runCatching{
                val gateway=gatewayForEnrollment(getApplication(),configuration,enrollment)
                val greenhouseId=enrollment.greenhouseId.ifBlank{
                    gateway.devices().firstOrNull{it.device.role==CloudDeviceRole.GreenhouseServer&&it.device.enabled}?.device?.greenhouseId.orEmpty()
                }
                if(greenhouseId.isBlank())return@runCatching null
                if(greenhouseId!=enrollment.greenhouseId)secure.saveCloudEnrollment(enrollment.copy(greenhouseId=greenhouseId))
                gateway.greenhouseBackup(greenhouseId)?.also{GreenhouseBackupStore(getApplication()).restore(it,CameraMode.ViewerClient)}
            }.onSuccess{backup->
                if(backup!=null){
                    val syncedPreferences=preferences.load().copy(cameraMode=CameraMode.ViewerClient);preferences.save(syncedPreferences)
                    val syncedSensors=currentSensors.load()
                    _state.value=_state.value.copy(preferences=syncedPreferences,sensors=syncedSensors,history=withContext(Dispatchers.IO){history.recent(2_000)},goveeConnected=syncedSensors.any{it.isFresh()},refreshing=false,message=if(silent)_state.value.message else "Synchronized from greenhouse server")
                    refreshWeather(true)
                }else _state.value=_state.value.copy(refreshing=false,message=if(silent)_state.value.message else "Waiting for greenhouse server data")
            }.onFailure{if(!silent)_state.value=_state.value.copy(refreshing=false,message=it.message?:"Server synchronization failed")}
            cloudSyncing=false
        }
    }
    private fun ensurePositions(sensors:List<GoveeSensor>){val p=_state.value.preferences;val g=p.activeGreenhouse;var positions=g.sensorPositions;sensors.filter{it.greenhouseId==g.id&&it.id !in positions}.forEachIndexed{i,s->val defaults=listOf(.2 to .18,.8 to .18,.4 to .62,.6 to .82);val n=defaults[i%defaults.size];positions=positions+(s.id to SensorPosition(n.first*g.planLengthFeet,n.second*g.planWidthFeet))};if(positions!=g.sensorPositions)savePreferences(p.copy(greenhouses=p.greenhouses.map{if(it.id==g.id)it.copy(sensorPositions=positions)else it}))}
    fun moveSensor(id:String,nx:Double,ny:Double){val p=_state.value.preferences;val g=p.activeGreenhouse;val projected=g.coercePoint(nx.coerceIn(.03,.97)*g.planLengthFeet,ny.coerceIn(.05,.95)*g.planWidthFeet);val pos=projected.copy(heightFeet=g.sensorPositions[id]?.heightFeet?:0.0);savePreferences(p.copy(greenhouses=p.greenhouses.map{if(it.id==g.id)it.copy(sensorPositions=it.sensorPositions+(id to pos)) else it}))}
    fun assignZone(sensorId:String,zone:String){legacyZones.assign(sensorId,zone);val p=_state.value.preferences;val active=p.activeGreenhouse;savePreferences(p.copy(sensorGreenhouseIds=p.sensorGreenhouseIds+(sensorId to active.id)));_state.value=_state.value.copy(sensors=_state.value.sensors.map{if(it.id==sensorId)it.copy(zone=zone,greenhouseId=active.id)else it});ensurePositions(_state.value.sensors)}
    fun updateGreenhouse(name:String,length:Double,width:Double){if(length<=0||width<=0)return;val p=_state.value.preferences;val old=p.activeGreenhouse;val geometry=old.copy(name=name.ifBlank{old.name},lengthFeet=length,widthFeet=width);val updated=geometry.copy(sensorPositions=old.sensorPositions.mapValues{(_,v)->geometry.reproject(v,old)});savePreferences(p.copy(greenhouses=p.greenhouses.map{if(it.id==old.id)updated else it}))}
    fun updatePreferences(transform:(AppPreferences)->AppPreferences)=savePreferences(transform(_state.value.preferences))
    private fun savePreferences(p:AppPreferences){preferences.save(p);_state.value=_state.value.copy(preferences=p)}
    fun saveZip(zip:String){if(!Regex("\\d{5}").matches(zip)){_state.value=_state.value.copy(message="Enter a valid five-digit ZIP code");return};savePreferences(_state.value.preferences.copy(zipCode=zip));refreshWeather()}
    fun refreshWeather(silent:Boolean=false){val zip=_state.value.preferences.zipCode;viewModelScope.launch{runCatching{weather.current(zip)}.onSuccess{_state.value=_state.value.copy(weather=it)}.onFailure{if(!silent)_state.value=_state.value.copy(message=it.message);_state.value=_state.value.copy(weather=_state.value.weather.copy(stale=true))}}}
    private fun loadHistory(){viewModelScope.launch{val rows=withContext(Dispatchers.IO){history.recent(2000)};_state.value=_state.value.copy(history=rows)}}
}
