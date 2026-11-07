package studio.junecity.greenhouse.settings

import android.content.Context

data class ApplicationSettings(val nightDimmingEnabled: Boolean = false, val nightDimAlpha: Float = .28f)

class ApplicationSettingsProvider(context: Context) {
    private val preferences = context.getSharedPreferences("dashboard_settings", Context.MODE_PRIVATE)
    fun load() = ApplicationSettings(preferences.getBoolean("night_dimming", false))
}
