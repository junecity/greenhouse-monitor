package studio.junecity.greenhouse

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import studio.junecity.greenhouse.data.*
import studio.junecity.greenhouse.model.*
import studio.junecity.greenhouse.settings.SecureSettingsStore
import studio.junecity.greenhouse.settings.ZoneAssignmentStore

class GreenhouseViewModel(application:Application):AndroidViewModel(application){
    private val settings=SecureSettingsStore(application)
    private val govee=GoveeApiClient()
    private val weatherProvider=LiveWeatherProvider()
    private val historyStore=HistoryStore(application)
    private val notifier=AlertNotifier(application)
    private val zones=ZoneAssignmentStore(application)
    private val _state=MutableStateFlow(GreenhouseUiState(goveeConnected=settings.goveeApiKey()!=null))
    val state:StateFlow<GreenhouseUiState> = _state.asStateFlow()

    init {
        SensorSyncWorker.schedule(application)
        settings.location()?.let{refreshWeather(it.first,it.second)}
        if(settings.goveeApiKey()!=null) refreshSensors()
        viewModelScope.launch { while(true){ delay(120_000); refreshSensors(silent=true); settings.location()?.let{refreshWeather(it.first,it.second,true)} } }
    }

    fun navigate(screen:AppScreen){_state.value=_state.value.copy(screen=screen,message=null);if(screen==AppScreen.History)loadHistory()}
    fun saveApiKey(key:String){
        settings.saveGoveeApiKey(key);_state.value=_state.value.copy(goveeConnected=key.isNotBlank(),message=if(key.isBlank())"Govee disconnected" else "API key saved securely")
        if(key.isNotBlank())refreshSensors()
    }
    fun updateLocation(latitude:Double,longitude:Double){settings.saveLocation(latitude,longitude);refreshWeather(latitude,longitude)}

    fun refreshSensors(silent:Boolean=false){
        val key=settings.goveeApiKey() ?: return
        viewModelScope.launch {
            if(!silent)_state.value=_state.value.copy(refreshing=true,message=null)
            runCatching{
                val discovered=govee.discoverH5179(key)
                discovered.map{govee.state(key,it).copy(zone=zones.zone(it.id))}
            }.onSuccess{sensors->
                withContext(Dispatchers.IO){sensors.filter{it.online}.forEach{historyStore.insert(it);notifier.evaluate(it)}}
                _state.value=_state.value.copy(sensors=sensors,goveeConnected=true,refreshing=false,message=if(sensors.isEmpty())"No H5179 sensors were found in this Govee account" else "Updated ${sensors.size} sensor${if(sensors.size==1)"" else "s"}")
            }.onFailure{_state.value=_state.value.copy(refreshing=false,message=it.message ?: "Govee refresh failed")}
        }
    }
    fun assignSensor(sensorId:String,zone:String){zones.assign(sensorId,zone);_state.value=_state.value.copy(sensors=_state.value.sensors.map{if(it.id==sensorId)it.copy(zone=zone) else it},message="Sensor assigned to $zone")}
    private fun refreshWeather(latitude:Double,longitude:Double,silent:Boolean=false){viewModelScope.launch{runCatching{weatherProvider.current(latitude,longitude)}.onSuccess{_state.value=_state.value.copy(weather=it)}.onFailure{if(!silent)_state.value=_state.value.copy(message="Local weather: ${it.message}")}}}
    private fun loadHistory(){viewModelScope.launch{val records=withContext(Dispatchers.IO){historyStore.recent()};_state.value=_state.value.copy(history=records)}}
}
