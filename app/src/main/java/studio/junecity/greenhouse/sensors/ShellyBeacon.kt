package studio.junecity.greenhouse.sensors

import studio.junecity.greenhouse.model.GoveeSensor

internal enum class ShellyModel(val sku: String, val type: Int, val title: String) {
    HT("SBHT-203C", 0x11, "Shelly H&T ZB"),
    Display("SBHT-103C", 0x0c, "Shelly H&T Display ZB");

    companion object {
        fun identify(name: String?, manufacturer: ByteArray?, type: Int?): ShellyModel? {
            var offset = 0
            while (manufacturer != null && offset < manufacturer.size) {
                val tag = manufacturer[offset++].toInt() and 255
                val size = when (tag) { 1, 11 -> 2; 10 -> 6; else -> break }
                if (offset + size > manufacturer.size) break
                if (tag == 11) return entries.find { it.type == manufacturer.uint(offset, 2) }
                offset += size
            }
            return entries.find { it.type == type || name?.startsWith(it.sku, ignoreCase = true) == true }
        }
    }
}

/** Accept the printed/pasted Bluetooth address; normalize it to Android's scan identity. */
internal fun normalizedBluetoothAddress(text: String): String? {
    val compact = text.trim().replace(":", "").replace("-", "")
    if (!compact.matches(Regex("[0-9A-Fa-f]{12}")) || compact.all { it == '0' }) return null
    return compact.uppercase(java.util.Locale.ROOT).chunked(2).joinToString(":")
}

internal fun shellyAddressId(text: String) = normalizedBluetoothAddress(text)?.let { "shelly:$it" }

private fun ByteArray.uint(offset: Int, count: Int): Int {
    var value = 0
    for (i in 0 until count) value = value or ((this[offset + i].toInt() and 255) shl (8 * i))
    return value
}

internal data class ShellyBeacon(val temperatureF: Double?, val humidity: Double?, val light: String?, val type: Int?, val encrypted: Boolean,
    val batteryPercent: Int? = null, val batteryLow: Boolean? = null) {
    companion object {
        fun parse(data: ByteArray): ShellyBeacon? {
            val flags = data.firstOrNull()?.toInt()?.and(255) ?: return null
            if (flags ushr 5 != 2) return null
            if (flags and 1 != 0) return ShellyBeacon(null, null, null, null, true)
            var batteryPercent: Int? = null
            var batteryLow: Boolean? = null
            var temp: Double? = null
            var rh: Double? = null
            var light: String? = null
            var type: Int? = null
            var offset = 1
            while (offset < data.size) {
                val tag = data.uint(offset++, 1)
                val size = when (tag) {
                    0, 1, 0x15, 0x1e, 0x2e, 0x3a, 0x64 -> 1
                    2, 3, 0x45, 0xf0 -> 2
                    5, 0xf2 -> 3
                    0xf1 -> 4
                    else -> break // Unknown objects have no length field.
                }
                if (offset + size > data.size) return null
                val value = data.uint(offset, size)
                when (tag) {
                    0x01 -> batteryPercent = value.takeIf { it in 0..100 }
                    0x15 -> batteryLow = when (value) { 0 -> false; 1 -> true; else -> null }
                    2, 0x45 -> temp = value.toShort() / (if (tag == 2) 100.0 else 10.0) * 1.8 + 32
                    3, 0x2e -> rh = (value / (if (tag == 3) 100.0 else 1.0)).takeIf { it in 0.0..100.0 }
                    0x64 -> light = listOf("Dark", "Twilight", "Bright").getOrNull(value)
                    0xf0 -> type = value
                }
                offset += size
            }
            return ShellyBeacon(temp, rh, light, type, false, batteryPercent, batteryLow)
        }
    }
}

internal data class ShellyAdvertisement(val id: String, val model: ShellyModel?, val beacon: ShellyBeacon, val received: Long) {
    fun update(sensor: GoveeSensor): GoveeSensor? {
        if (sensor.id != id || beacon.temperatureF == null || beacon.humidity == null || beacon.encrypted) return null
        // A single measurement is broadcast repeatedly in a short burst.
        if (received - sensor.updatedAtEpochMs < 5_000) return null
        return sensor.copy(temperatureF = beacon.temperatureF, humidityPercent = beacon.humidity,
            lightLevel = if (sensor.sku == ShellyModel.Display.sku) beacon.light else null,
            batteryPercent = beacon.batteryPercent, batteryLow = beacon.batteryLow,
            online = true, updatedAtEpochMs = received)
    }
}

internal fun GoveeSensor.isShelly() = id.startsWith("shelly:")
