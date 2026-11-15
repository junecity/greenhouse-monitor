package studio.junecity.greenhouse.data

import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.telephony.TelephonyManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import studio.junecity.greenhouse.model.*

interface DeviceStatusProvider { fun current(): DeviceStatus }

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
        val cellular = caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
        val network = when {
            wifi -> "Wi-Fi"
            cellular -> "Cellular"
            caps == null -> "Offline"
            else -> "Network"
        }
        val signal = runCatching {
            when {
                wifi -> {
                    @Suppress("DEPRECATION")
                    val info = (caps?.transportInfo as? WifiInfo)
                        ?: context.applicationContext.getSystemService(WifiManager::class.java)?.connectionInfo
                    info?.rssi?.takeIf { it in -126..-1 }
                }
                cellular -> context.getSystemService(TelephonyManager::class.java)?.signalStrength
                    ?.cellSignalStrengths?.firstOrNull { it.dbm in -160..-1 }?.dbm
                else -> null
            }
        }.getOrNull()
        return DeviceStatus(if (level >= 0 && scale > 0) (level * 100 / scale).takeIf { it in 0..100 } else null,
            charging.takeIf { status > 0 }, wifi, network, signal, System.currentTimeMillis())
    }
}
