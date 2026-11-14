package studio.junecity.greenhouse

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.*
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import studio.junecity.greenhouse.data.AndroidDeviceStatusProvider
import studio.junecity.greenhouse.model.AppScreen
import studio.junecity.greenhouse.ui.DashboardScreen
import studio.junecity.greenhouse.ui.StageScreen
import studio.junecity.greenhouse.ui.AppMotion
import studio.junecity.greenhouse.ui.accessibleMotionDuration
import studio.junecity.greenhouse.camera.CameraStreamServer
import studio.junecity.greenhouse.model.CameraMode
import studio.junecity.greenhouse.cloud.CloudDeviceRole
import studio.junecity.greenhouse.cloud.CloudCameraServerService
import studio.junecity.greenhouse.cloud.CloudAlertService
import studio.junecity.greenhouse.cloud.CloudHeartbeatService
import studio.junecity.greenhouse.cloud.CloudOnboardingGate
import studio.junecity.greenhouse.cloud.CloudRuntime
import studio.junecity.greenhouse.settings.SecureSettingsStore
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDateTime

class MainActivity : ComponentActivity() {
    private var forecastOpen by mutableStateOf(false)
    private var alert by mutableStateOf<studio.junecity.greenhouse.data.AlertDestination?>(null)
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        forecastOpen = intent.action == studio.junecity.greenhouse.data.ForecastAlerts.OPEN_FORECAST
        alert = studio.junecity.greenhouse.data.AlertDestination.from(intent)
    }

    private val viewModel: GreenhouseViewModel by viewModels()
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val enrollment = SecureSettingsStore(applicationContext).cloudEnrollment()
        val configured = runCatching { CloudRuntime.configurationOrNull() != null }.getOrDefault(false)
        CloudCameraServerService.sync(applicationContext, granted && configured && enrollment?.role == CloudDeviceRole.GreenhouseServer)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        forecastOpen = intent.action == studio.junecity.greenhouse.data.ForecastAlerts.OPEN_FORECAST
        alert = studio.junecity.greenhouse.data.AlertDestination.from(intent)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= 29) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= 30) {
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
        enterImmersiveMode()
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        val existingEnrollment = SecureSettingsStore(applicationContext).cloudEnrollment()
        if (existingEnrollment?.role == CloudDeviceRole.GreenhouseServer && ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
        val device = AndroidDeviceStatusProvider(this)

        setContent {
            val state by viewModel.state.collectAsStateWithLifecycle()
            val navigationDuration = accessibleMotionDuration(AppMotion.NavigationFadeMs)
            val cloudConfiguration = remember { runCatching { CloudRuntime.configurationOrNull() } }
            var cloudEnrollmentRevision by remember { mutableIntStateOf(0) }
            DisposableEffect(Unit) {
                val stopWatching = SecureSettingsStore(applicationContext).watchEnrollment {
                    viewModel.selectStores()
                    cloudEnrollmentRevision++
                }
                onDispose { stopWatching() }
            }
            var now by remember { mutableStateOf(LocalDateTime.now()) }
            var status by remember { mutableStateOf(device.current()) }

            LaunchedEffect(Unit) {
                while (true) {
                    delay(30_000)
                    now = LocalDateTime.now()
                    status = device.current()
                }
            }
            LaunchedEffect(state.preferences.cameraMode, cloudEnrollmentRevision) {
                val enrollment = SecureSettingsStore(applicationContext).cloudEnrollment()
                if (state.preferences.cameraMode == CameraMode.CameraServer && enrollment == null && cloudConfiguration.getOrNull() == null && cloudConfiguration.isSuccess) {
                    CameraStreamServer.start(applicationContext, allowPublicMapping = true)
                } else CameraStreamServer.stop()
                CloudAlertService.sync(applicationContext, enrollment != null && cloudConfiguration.getOrNull() != null)
                CloudHeartbeatService.sync(
                    applicationContext,
                    enrollment?.role == CloudDeviceRole.GreenhouseServer
                )
                CloudCameraServerService.sync(
                    applicationContext,
                    enrollment?.role == CloudDeviceRole.GreenhouseServer &&
                        ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                )
            }
            SideEffect {
                if (state.preferences.keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }

            val volume by viewModel.volumeVisible.collectAsStateWithLifecycle()
            val showingVolume = volume && state.screen == AppScreen.Zones
            CloudOnboardingGate(
                configuration = cloudConfiguration.getOrNull(),
                configurationError = cloudConfiguration.exceptionOrNull()?.message,
                portraitMonitoringScreen = state.screen == AppScreen.Settings,
                rotateMonitoringScreen = showingVolume,
                onSetup = viewModel::applyQuickStart,
                onEnrolled = { role ->
                    viewModel.completeCloudEnrollment(role)
                    if (role == CloudDeviceRole.GreenhouseServer && ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                        cameraPermission.launch(Manifest.permission.CAMERA)
                    }
                    cloudEnrollmentRevision++
                }
            ) {
                LaunchedEffect(cloudEnrollmentRevision) {
                    studio.junecity.greenhouse.backup.LocalBackupWorker.schedule(applicationContext, SecureSettingsStore(applicationContext).cloudEnrollment())
                    lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                        viewModel.refreshWhileVisible()
                    }
                }
                alert?.let { target ->
                    val active = state.preferences.activeGreenhouse
                    val enrollment = SecureSettingsStore(applicationContext).cloudEnrollment()
                    val matches = target.device == enrollment?.deviceId &&
                        target.greenhouse == (if (target.cloud) enrollment?.greenhouseId else active.id)
                    val dismiss = { alert = null; intent.action = null }
                    if (matches) {
                        studio.junecity.greenhouse.ui.InsightsDialog(studio.junecity.greenhouse.ui.Insight.Alert, state,
                            { (it * .5f).dp },
                            { it.sp }, target.sensor,
                            !state.standalone && enrollment?.role == CloudDeviceRole.ViewerClient,
                            target.time, dismiss)
                    } else {
                        androidx.compose.material3.AlertDialog(onDismissRequest = dismiss,
                            text = { androidx.compose.material3.Text("This alert belongs to another greenhouse or device registration.") },
                            confirmButton = { androidx.compose.material3.TextButton(onClick = dismiss) { androidx.compose.material3.Text("Close") } })
                    }
                }
                var energyReturn by remember { mutableStateOf(AppScreen.Dashboard) }
                var returnToForecast by remember { mutableStateOf(false) }
                val heatingStore = remember(cloudEnrollmentRevision, state.preferences.activeGreenhouseId) {
                    studio.junecity.greenhouse.data.HeatingStore(applicationContext,
                        SecureSettingsStore(applicationContext).cloudEnrollment(), state.preferences.activeGreenhouseId)
                }
                val heatingPoints = if (state.screen == AppScreen.Dashboard || state.screen == AppScreen.Energy) studio.junecity.greenhouse.ui.heatingPoints(state, heatingStore) else emptyList()
                val learned = remember(heatingPoints) { studio.junecity.greenhouse.data.learnedTemperature(heatingPoints) }
                val heatingSummary = remember(learned, heatingStore, state.weather, state.preferences.activeGreenhouse, state.screen, now.toLocalDate(), now.hour) {
                    val settings = heatingStore.settings()
                    if (learned == null || state.weather.zipCode != state.preferences.zipCode || state.weather.stale || settings.price == null) null else {
                        val forecast = studio.junecity.greenhouse.data.NightHeating.forecast(state.weather, learned.fahrenheit, System.currentTimeMillis())
                        forecast.first().degreeHours?.let {
                            val coefficient = studio.junecity.greenhouse.data.heatingCoefficient(state.preferences.activeGreenhouse, settings.unit)
                            studio.junecity.greenhouse.ui.money(it * coefficient.nominal * settings.price)
                        }
                    }
                }
                var openSensors by remember { mutableStateOf(false) }
                val navigate: (AppScreen) -> Unit = { next ->
                    if (next == AppScreen.Energy && state.screen != next) energyReturn = state.screen
                    openSensors = false
                    returnToForecast = false
                    viewModel.navigate(next)
                }
                androidx.activity.compose.BackHandler(enabled = state.screen == AppScreen.Energy && !forecastOpen) {
                    viewModel.navigate(energyReturn)
                    forecastOpen = returnToForecast
                    returnToForecast = false
                }
                if (forecastOpen) studio.junecity.greenhouse.ui.ForecastDialog(state.weather, state.preferences.zipCode,
                    refresh = { viewModel.refreshWeather() }, heating = {
                        forecastOpen = false
                        navigate(AppScreen.Energy)
                        returnToForecast = true
                    }, dismiss = { forecastOpen = false; intent.action = null })
                if (showingVolume) {
                    androidx.activity.compose.BackHandler { viewModel.setVolumeOpen(false) }
                    studio.junecity.greenhouse.ui.ZoneVolumeScreen(state, viewModel::updatePreferences) { viewModel.setVolumeOpen(false) }
                } else Crossfade(state.screen, animationSpec = tween(navigationDuration), label = "screen navigation") { screen ->
                    if (screen == AppScreen.Dashboard) {
                        DashboardScreen(state, status, now, navigate, Modifier.fillMaxSize(),
                            onManageSensors = { openSensors = true; viewModel.navigate(AppScreen.Settings) },
                            heatingSummary = heatingSummary, onHeating = { navigate(AppScreen.Energy) },
                            onRefresh = { viewModel.syncFromServer() }, onForecast = { forecastOpen = true; viewModel.refreshWeather(true) })
                    } else if (screen == AppScreen.Energy) {
                        studio.junecity.greenhouse.ui.ApprovedFrame(screen, status, now, navigate, Modifier.fillMaxSize(), standalone = state.standalone) { _, _ ->
                            studio.junecity.greenhouse.ui.NightHeatingContent(state, heatingStore, { viewModel.refreshWeather() }, heatingPoints)
                        }
                    } else {
                        StageScreen(
                            state.copy(screen = screen), status, now, navigate, viewModel::selectMetric,
                            viewModel::refreshSensors, viewModel::saveApiKey, viewModel::moveSensor, viewModel::assignZone,
                            viewModel::saveZip, viewModel::refreshWeather, viewModel::updateGreenhouse,
                            viewModel::updatePreferences, Modifier.fillMaxSize(), openSensors, viewModel::reloadAfterRestore, onVolume = { viewModel.setVolumeOpen(true) }, onHistoryDay = viewModel::historyDay
                        )
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        if (isFinishing) CameraStreamServer.stop()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        enterImmersiveMode()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersiveMode()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        window.decorView.post { if (hasWindowFocus()) enterImmersiveMode() }
    }

    private fun enterImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }
}
