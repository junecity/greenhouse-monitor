package studio.junecity.greenhouse

import android.Manifest
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import studio.junecity.greenhouse.data.AndroidDeviceStatusProvider
import studio.junecity.greenhouse.model.*
import studio.junecity.greenhouse.settings.ApplicationSettingsProvider
import studio.junecity.greenhouse.ui.DashboardScreen
import studio.junecity.greenhouse.ui.StageScreen
import java.time.LocalDateTime

class MainActivity : ComponentActivity() {
    private val viewModel:GreenhouseViewModel by viewModels()
    private val locationPermission=registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()){grants->if(grants.values.any{it})readLocation()}
    private val notificationPermission=registerForActivityResult(ActivityResultContracts.RequestPermission()){}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.apply { screenBrightness = .75f }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        enterImmersiveMode()
        requestRuntimePermissions()
        val device = AndroidDeviceStatusProvider(this)
        val settings = ApplicationSettingsProvider(this).load()
        setContent {
            val ui by viewModel.state.collectAsStateWithLifecycle()
            var now by remember { mutableStateOf(LocalDateTime.now()) }
            var status by remember { mutableStateOf(device.current()) }
            LaunchedEffect(Unit) { while (true) { delay(30_000); now = LocalDateTime.now(); status = device.current() } }
            val online=ui.sensors.filter{it.online&&it.temperatureF!=null&&it.humidityPercent!=null}
            val greenhouse=if(online.isNotEmpty()) GreenhouseReading(online.mapNotNull{it.temperatureF}.average(),online.mapNotNull{it.humidityPercent}.average().toInt(),false) else GreenhouseReading(72.4,58,true)
            Box(Modifier.fillMaxSize()) {
                if(ui.screen==AppScreen.Dashboard) DashboardScreen(DashboardData(greenhouse,ui.weather),status,now,Modifier.fillMaxSize(),viewModel::navigate)
                else StageScreen(ui,viewModel::navigate,viewModel::refreshSensors,viewModel::saveApiKey,viewModel::assignSensor,Modifier.fillMaxSize())
                if (settings.nightDimmingEnabled && (now.hour >= 22 || now.hour < 6)) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha=settings.nightDimAlpha)))
            }
        }
    }

    private fun requestRuntimePermissions(){
        if(ContextCompat.checkSelfPermission(this,Manifest.permission.ACCESS_COARSE_LOCATION)==PackageManager.PERMISSION_GRANTED)readLocation()
        else locationPermission.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION,Manifest.permission.ACCESS_FINE_LOCATION))
        if(Build.VERSION.SDK_INT>=33&&ContextCompat.checkSelfPermission(this,Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    @Suppress("MissingPermission", "DEPRECATION")
    private fun readLocation(){
        val manager=getSystemService(LocationManager::class.java)
        val last=listOf(LocationManager.NETWORK_PROVIDER,LocationManager.GPS_PROVIDER).mapNotNull{runCatching{manager.getLastKnownLocation(it)}.getOrNull()}.maxByOrNull{it.time}
        if(last!=null){viewModel.updateLocation(last.latitude,last.longitude);return}
        if(Build.VERSION.SDK_INT>=30) manager.getCurrentLocation(LocationManager.NETWORK_PROVIDER,CancellationSignal(),mainExecutor){it?.let(::acceptLocation)}
        else manager.requestSingleUpdate(LocationManager.NETWORK_PROVIDER,object:LocationListener{override fun onLocationChanged(location:Location)=acceptLocation(location)},mainLooper)
    }
    private fun acceptLocation(location:Location){viewModel.updateLocation(location.latitude,location.longitude)}

    override fun onResume() { super.onResume(); enterImmersiveMode() }
    private fun enterImmersiveMode() {
        WindowInsetsControllerCompat(window, window.decorView).apply { hide(WindowInsetsCompat.Type.systemBars());systemBarsBehavior=WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE }
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility=View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    }
}
