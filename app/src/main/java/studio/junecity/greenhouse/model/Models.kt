package studio.junecity.greenhouse.model

data class GreenhouseReading(val temperatureF: Double, val humidityPercent: Int, val isDemo: Boolean)
enum class AppScreen { Dashboard, Zones, History, Cameras, Settings }
data class GoveeSensor(
    val id: String,
    val sku: String,
    val name: String,
    val temperatureF: Double? = null,
    val humidityPercent: Double? = null,
    val online: Boolean = false,
    val updatedAtEpochMs: Long = 0,
    val zone: String = "Unassigned",
)
data class HistoryReading(
    val sensorId: String,
    val sensorName: String,
    val temperatureF: Double,
    val humidityPercent: Double,
    val timestampEpochMs: Long,
)
data class WeatherReading(
    val location: String,
    val temperatureF: Int,
    val condition: String,
    val tonightF: Int,
    val rainChancePercent: Int,
    val isDemo: Boolean,
)
data class DeviceStatus(val batteryPercent: Int, val charging: Boolean, val wifiConnected: Boolean)
data class DashboardData(val greenhouse: GreenhouseReading, val weather: WeatherReading)
data class GreenhouseUiState(
    val screen: AppScreen = AppScreen.Dashboard,
    val sensors: List<GoveeSensor> = emptyList(),
    val weather: WeatherReading = WeatherReading("Location needed", 0, "Weather unavailable", 0, 0, true),
    val history: List<HistoryReading> = emptyList(),
    val goveeConnected: Boolean = false,
    val refreshing: Boolean = false,
    val message: String? = null,
)
