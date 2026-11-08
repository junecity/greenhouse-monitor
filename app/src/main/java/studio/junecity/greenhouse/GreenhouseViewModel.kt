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

class GreenhouseViewModel(app:Application):AndroidViewModel(app){
    private val secure=SecureSettingsStore(app);private val preferences=AppPreferencesStore(app);private val legacyZones=ZoneAssignmentStore(app);private val govee=GoveeSensorProvider();private val weather=LiveWeatherProvider();private val history=HistoryStore(app);private val notifier=AlertNotifier(app)
    private val _state=MutableStateFlow(GreenhouseUiState(preferences=preferences.load(),goveeConnected=secure.goveeApiKey()!=null));val state=_state.asStateFlow()
    init{SensorSyncWorker.schedule(app);refreshWeather(true);if(secure.goveeApiKey()!=null)refreshSensors();viewModelScope.launch{while(true){delay(_state.value.preferences.refreshMinutes.coerceIn(1,30)*60_000L);refreshSensors(true)}};viewModelScope.launch{while(true){delay(15*60_000L);refreshWeather(true)}}}
    fun navigate(s:AppScreen){_state.value=_state.value.copy(screen=s,message=null);if(s==AppScreen.History)loadHistory()}
    fun selectMetric(m:Metric){_state.value=_state.value.copy(metric=m)}
    fun saveApiKey(key:String){secure.saveGoveeApiKey(key);_state.value=_state.value.copy(goveeConnected=key.isNotBlank(),message=if(key.isBlank())"Govee disconnected" else "API key saved securely");if(key.isNotBlank())refreshSensors()}
    fun testConnection()=refreshSensors()
    fun refreshSensors(silent:Boolean=false){val key=secure.goveeApiKey()?:return;viewModelScope.launch{if(!silent)_state.value=_state.value.copy(refreshing=true,message=null);runCatching{val credential=SensorProviderCredential.ApiKey(key);val discovered=govee.discover(credential);val prefs=_state.value.preferences;discovered.map{device->val old=_state.value.sensors.firstOrNull{it.id==device.deviceId};val zone=old?.zone?:legacyZones.zone(device.deviceId);govee.read(credential,device).toLegacySensor(greenhouseId=prefs.sensorGreenhouseIds[device.deviceId]?:old?.greenhouseId?:prefs.activeGreenhouse.id,zone=zone)}}.onSuccess{sensors->withContext(Dispatchers.IO){sensors.forEach{if(it.isFresh())history.insert(it);notifier.evaluate(it)}};ensurePositions(sensors);_state.value=_state.value.copy(sensors=sensors,goveeConnected=true,refreshing=false,message="Updated ${sensors.size} sensors")}.onFailure{_state.value=_state.value.copy(refreshing=false,message=it.message?:"Govee refresh failed")}}}
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
