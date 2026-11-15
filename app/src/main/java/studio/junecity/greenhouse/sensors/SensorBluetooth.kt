package studio.junecity.greenhouse.sensors

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.buffer

internal object SensorBluetooth {
    val permissions: Array<String> get() = if (Build.VERSION.SDK_INT >= 31)
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    fun allowed(context: Context) = permissions.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission")
    fun <T : Any> scan(context: Context, filters: List<ScanFilter>, discovery: Boolean, decode: (ScanResult) -> T?) = callbackFlow {
        check(allowed(context)) { "Allow Bluetooth scanning to find sensors." }
        if (Build.VERSION.SDK_INT < 31) {
            check(context.getSystemService(android.location.LocationManager::class.java).isLocationEnabled) {
                "Turn on Location in Android settings to scan for Bluetooth sensors."
            }
        }
        val scanner = context.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner
            ?: error("Turn on Bluetooth on this phone to find sensors.")
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                decode(result)?.let { trySend(it) }
            }
            override fun onScanFailed(code: Int) { close(IllegalStateException("Bluetooth scan stopped ($code). Try again.")) }
        }
        val settings = ScanSettings.Builder().setScanMode(if (discovery) ScanSettings.SCAN_MODE_LOW_LATENCY else ScanSettings.SCAN_MODE_BALANCED).build()
        scanner.startScan(filters, settings, callback)
        awaitClose { runCatching { scanner.stopScan(callback) } }
    }.buffer(256)
}
