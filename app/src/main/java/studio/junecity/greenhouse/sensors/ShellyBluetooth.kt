package studio.junecity.greenhouse.sensors

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.buffer

internal object ShellyBluetooth {
    private val uuid = ParcelUuid.fromString("0000fcd2-0000-1000-8000-00805f9b34fb")
    val permissions: Array<String> get() = if (Build.VERSION.SDK_INT >= 31)
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    fun allowed(context: Context) = permissions.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission")
    fun advertisements(context: Context, discovery: Boolean) = callbackFlow {
        check(allowed(context)) { "Allow Bluetooth scanning to find sensors." }
        if (Build.VERSION.SDK_INT < 31) {
            check(context.getSystemService(android.location.LocationManager::class.java).isLocationEnabled) {
                "Turn on Location in Android settings to scan for Bluetooth sensors."
            }
        }
        val scanner = context.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner
            ?: error("Turn on Bluetooth on this phone to find sensors.")
        val known = mutableMapOf<String, ShellyModel>()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val record = result.scanRecord ?: return
                val raw = record.getServiceData(uuid) ?: return
                val beacon = ShellyBeacon.parse(raw) ?: return
                val id = "shelly:" + result.device.address
                val model = ShellyModel.identify(record.deviceName, record.getManufacturerSpecificData(0x0ba9), beacon.type)
                if (model != null) known[id] = model
                val received = System.currentTimeMillis() - (SystemClock.elapsedRealtime() - result.timestampNanos / 1_000_000).coerceAtLeast(0)
                trySend(ShellyAdvertisement(id, model ?: known[id], beacon, received))
            }
            override fun onScanFailed(code: Int) { close(IllegalStateException("Bluetooth scan stopped ($code). Try again.")) }
        }
        val filter = ScanFilter.Builder().setServiceData(uuid, byteArrayOf()).build()
        val settings = ScanSettings.Builder().setScanMode(if (discovery) ScanSettings.SCAN_MODE_LOW_LATENCY else ScanSettings.SCAN_MODE_BALANCED).build()
        scanner.startScan(listOf(filter), settings, callback)
        awaitClose { runCatching { scanner.stopScan(callback) } }
    }.buffer(256)
}
