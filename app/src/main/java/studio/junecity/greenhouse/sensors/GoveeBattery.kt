package studio.junecity.greenhouse.sensors

import android.annotation.SuppressLint
import android.bluetooth.le.ScanFilter
import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.*
import studio.junecity.greenhouse.model.GoveeSensor
import studio.junecity.greenhouse.settings.*

internal data class GoveeBatteryPacket(val address: String, val percent: Int, val receivedAt: Long)

internal object GoveeBattery {
    private const val CLASSIC = 0x8801
    private const val PACKED = 0x0001
    private val mac = Regex("[0-9A-F]{12}")

    fun addressFor(sensor: GoveeSensor): String? {
        if (!sensor.sku.equals("H5179", true)) return null
        val id = (sensor.batteryRadioAddress ?: sensor.id).uppercase().replace(":", "").replace("-", "")
        val address = if (id.length == 16 && id.all { it in "0123456789ABCDEF" }) id.takeLast(12) else id
        return address.takeIf(mac::matches)?.chunked(2)?.joinToString(":")
    }

    // Android has already stripped the two-byte manufacturer identifier from data.
    fun decode(company: Int, name: String?, data: ByteArray): Int? {
        val percent = when {
            company == CLASSIC && data.size == 9 -> data[8].toInt() and 0xff
            company == PACKED && (data.size == 6 || data.size == 8) && name?.startsWith("GV5179", true) == true ->
                data[5].toInt() and 0x7f
            else -> return null
        }
        return percent.takeIf { it in 0..100 }
    }

    fun apply(rows: List<GoveeSensor>, packet: GoveeBatteryPacket): List<GoveeSensor> {
        val target = rows.singleOrNull { addressFor(it) == packet.address } ?: return rows
        val age = packet.receivedAt - (target.batteryObservedAt ?: 0L)
        if (age < 0 || (target.batteryPercent == packet.percent && age < 60_000L)) return rows
        return rows.map { if (it.id == target.id) it.copy(batteryPercent = packet.percent, batteryObservedAt = packet.receivedAt) else it }
    }

    @SuppressLint("MissingPermission")
    fun packets(context: Context, discovery: Boolean) = SensorBluetooth.scan(context,
        listOf(CLASSIC, PACKED).map { ScanFilter.Builder().setManufacturerData(it, byteArrayOf()).build() }, discovery
    ) { result ->
        val record = result.scanRecord ?: return@scan null
        val percent = record.getManufacturerSpecificData(CLASSIC)?.let { decode(CLASSIC, record.deviceName, it) }
            ?: record.getManufacturerSpecificData(PACKED)?.let { decode(PACKED, record.deviceName, it) }
            ?: return@scan null
        val lag = (SystemClock.elapsedRealtime() - result.timestampNanos / 1_000_000L).coerceAtLeast(0)
        GoveeBatteryPacket(result.device.address, percent, System.currentTimeMillis() - lag)
    }
}

internal suspend fun collectGoveeBatteries(context: Context, access: CollectorEnrollment, suffix: String) =
    withContext(Dispatchers.IO) {
        val store = CurrentSensorStore(context, suffix)
        while (isActive && access.isCurrent()) {
            if (!SensorBluetooth.allowed(context) || store.load().none { it.sku.equals("H5179", true) }) {
                delay(5_000)
                continue
            }
            try {
                withTimeoutOrNull(45_000) {
                    val heard = mutableMapOf<String, Int>()
                    GoveeBattery.packets(context, false).collect { packet ->
                        if (heard.put(packet.address, packet.percent) != packet.percent)
                            access.whileCurrent { store.update { GoveeBattery.apply(it, packet) } }
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { delay(15_000); continue }
            delay(4 * 60_000L)
        }
    }
