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
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
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
import java.time.LocalDateTime

class MainActivity : ComponentActivity() {
    private val viewModel: GreenhouseViewModel by viewModels()
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val enrollment = SecureSettingsStore(applicationContext).cloudEnrollment()
        val configured = runCatching { CloudRuntime.configurationOrNull() != null }.getOrDefault(false)
        CloudCameraServerService.sync(applicationContext, granted && configured && enrollment?.role == CloudDeviceRole.GreenhouseServer)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
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
                if (state.preferences.cameraMode == CameraMode.CameraServer) {
                    CameraStreamServer.start(applicationContext, allowPublicMapping = cloudConfiguration.getOrNull() == null)
                }
                else CameraStreamServer.stop()
                val enrollment = SecureSettingsStore(applicationContext).cloudEnrollment()
                CloudAlertService.sync(applicationContext, enrollment != null && cloudConfiguration.getOrNull() != null)
                CloudHeartbeatService.sync(
                    applicationContext,
                    state.preferences.cameraMode == CameraMode.CameraServer && enrollment?.role == CloudDeviceRole.GreenhouseServer
                )
                CloudCameraServerService.sync(
                    applicationContext,
                    state.preferences.cameraMode == CameraMode.CameraServer && enrollment?.role == CloudDeviceRole.GreenhouseServer &&
                        ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                )
            }
            SideEffect {
                if (state.preferences.keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }

            CloudOnboardingGate(
                configuration = cloudConfiguration.getOrNull(),
                configurationError = cloudConfiguration.exceptionOrNull()?.message,
                onEnrolled = { role ->
                    viewModel.completeCloudEnrollment(role)
                    if (role == CloudDeviceRole.GreenhouseServer && ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                        cameraPermission.launch(Manifest.permission.CAMERA)
                    }
                    cloudEnrollmentRevision++
                }
            ) {
                Crossfade(state.screen, animationSpec = tween(navigationDuration), label = "screen navigation") { screen ->
                    if (screen == AppScreen.Dashboard) {
                        DashboardScreen(state, status, now, viewModel::navigate, Modifier.fillMaxSize())
                    } else {
                        StageScreen(
                            state.copy(screen = screen), status, now, viewModel::navigate, viewModel::selectMetric,
                            viewModel::refreshSensors, viewModel::saveApiKey, viewModel::moveSensor, viewModel::assignZone,
                            viewModel::saveZip, viewModel::refreshWeather, viewModel::updateGreenhouse,
                            viewModel::updatePreferences, Modifier.fillMaxSize()
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

    @Suppress("DEPRECATION")
    private fun enterImmersiveMode() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    }
}
