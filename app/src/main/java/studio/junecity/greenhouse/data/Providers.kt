package studio.junecity.greenhouse.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import studio.junecity.greenhouse.model.*

interface GreenhouseSensorDataProvider { fun current(): GreenhouseReading }
interface WeatherDataProvider { fun current(): WeatherReading }
interface DeviceStatusProvider { fun current(): DeviceStatus }

class DemoGreenhouseSensorDataProvider : GreenhouseSensorDataProvider {
    override fun current() = GreenhouseReading(72.4, 58, isDemo = true)
}
class DemoWeatherDataProvider : WeatherDataProvider {
    override fun current() = WeatherReading("Waterbury, CT", 46, "Partly Cloudy", 34, 10, isDemo = true)
}

class AndroidDeviceStatusProvider(private val context: Context) : DeviceStatusProvider {
    override fun current(): DeviceStatus {
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val caps = connectivity.getNetworkCapabilities(connectivity.activeNetwork)
        val wifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        return DeviceStatus(if (level < 0) 0 else level * 100 / scale, charging, wifi)
    }
}
