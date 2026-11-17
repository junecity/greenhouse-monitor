package studio.junecity.greenhouse.ui

import androidx.compose.runtime.staticCompositionLocalOf

internal typealias CameraSamples = studio.junecity.greenhouse.camera.CameraSamples

internal val LocalCameraSamples = staticCompositionLocalOf<CameraSamples?> { null }
