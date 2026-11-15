package studio.junecity.greenhouse.sensors

import android.annotation.SuppressLint
import android.bluetooth.le.ScanFilter
import android.content.Context
import android.os.ParcelUuid
import android.os.SystemClock

internal object ShellyBluetooth {
    private val uuid = ParcelUuid.fromString("0000fcd2-0000-1000-8000-00805f9b34fb")

    @SuppressLint("MissingPermission")
    fun advertisements(context: Context, discovery: Boolean): kotlinx.coroutines.flow.Flow<ShellyAdvertisement> {
        val filter = ScanFilter.Builder().setServiceData(uuid, byteArrayOf()).build()
        val known = mutableMapOf<String, ShellyModel>()
        return SensorBluetooth.scan(context, listOf(filter), discovery) { result ->
            val record = result.scanRecord ?: return@scan null
            val raw = record.getServiceData(uuid) ?: return@scan null
            val beacon = ShellyBeacon.parse(raw) ?: return@scan null
            val id = "shelly:" + result.device.address
            val model = ShellyModel.identify(record.deviceName, record.getManufacturerSpecificData(0x0ba9), beacon.type)
            if (model != null) known[id] = model
            val received = System.currentTimeMillis() - (SystemClock.elapsedRealtime() - result.timestampNanos / 1_000_000).coerceAtLeast(0)
            ShellyAdvertisement(id, model ?: known[id], beacon, received)
        }
    }
}
