package studio.junecity.greenhouse

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import studio.junecity.greenhouse.data.AndroidDeviceStatusProvider
import studio.junecity.greenhouse.model.AppScreen
import studio.junecity.greenhouse.ui.DashboardScreen
import studio.junecity.greenhouse.ui.StageScreen
import java.time.LocalDateTime

class MainActivity:ComponentActivity(){
    private val viewModel:GreenhouseViewModel by viewModels();private val notificationPermission=registerForActivityResult(ActivityResultContracts.RequestPermission()){}
    override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);WindowCompat.setDecorFitsSystemWindows(window,false);enterImmersiveMode();if(Build.VERSION.SDK_INT>=33&&ContextCompat.checkSelfPermission(this,Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS);val device=AndroidDeviceStatusProvider(this)
        setContent{val state by viewModel.state.collectAsStateWithLifecycle();var now by remember{mutableStateOf(LocalDateTime.now())};var status by remember{mutableStateOf(device.current())};LaunchedEffect(Unit){while(true){delay(30_000);now=LocalDateTime.now();status=device.current()}};SideEffect{if(state.preferences.keepScreenOn)window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)};if(state.screen==AppScreen.Dashboard)DashboardScreen(state,status,now,viewModel::navigate,Modifier.fillMaxSize())else StageScreen(state,status,now,viewModel::navigate,viewModel::selectMetric,viewModel::refreshSensors,viewModel::saveApiKey,viewModel::moveSensor,viewModel::assignZone,viewModel::saveZip,viewModel::refreshWeather,viewModel::updateGreenhouse,viewModel::updatePreferences,Modifier.fillMaxSize())} }
    override fun onResume(){super.onResume();enterImmersiveMode()}
    @Suppress("DEPRECATION")
    private fun enterImmersiveMode(){WindowInsetsControllerCompat(window,window.decorView).apply{hide(WindowInsetsCompat.Type.systemBars());systemBarsBehavior=WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE};window.decorView.systemUiVisibility=View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE}
}
