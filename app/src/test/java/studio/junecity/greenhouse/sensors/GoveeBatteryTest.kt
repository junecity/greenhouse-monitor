package studio.junecity.greenhouse.sensors

import org.junit.Assert.*
import org.junit.Test
import studio.junecity.greenhouse.model.GoveeSensor
import studio.junecity.greenhouse.settings.CurrentSensorCodec

class GoveeBatteryTest {
    private val mac = "D1:06:03:06:63:19"
    private val sensor = GoveeSensor("17:10:$mac", "H5179", "Bench")

    @Test fun documentedPacketsAndBounds() {
        val classic = byteArrayOf(0xec.toByte(), 0, 1, 1, 10, 10, 0xa4.toByte(), 6, 100)
        val packed = byteArrayOf(1, 1, 3, 0xb3.toByte(), 20, 100)
        assertEquals(100, GoveeBattery.decode(0x8801, null, classic))
        assertEquals(100, GoveeBattery.decode(1, "GV5179_6319", packed))
        classic[8] = 0
        packed[5] = 0x94.toByte()
        assertEquals(0, GoveeBattery.decode(0x8801, null, classic))
        assertEquals(20, GoveeBattery.decode(1, "GV5179_6319", packed))
        assertNull(GoveeBattery.decode(1, "H5101", packed))
        assertNull(GoveeBattery.decode(0x8801, null, classic.drop(1).toByteArray()))
        classic[8] = -1
        assertNull(GoveeBattery.decode(0x8801, null, classic))
    }

    @Test fun onlyUniqueEnrolledAddressAndNewerReportsUpdateBattery() {
        val other = sensor.copy(id = "other", sku = "Shelly")
        val packet = GoveeBatteryPacket(mac, 65, 1_700_000_000_000)
        val rows = listOf(sensor, other)
        val updated = GoveeBattery.apply(rows, packet)
        assertEquals(sensor.copy(batteryPercent = 65, batteryObservedAt = packet.receivedAt), updated.first())
        assertEquals(other, updated.last())
        assertEquals(updated, CurrentSensorCodec.decode(CurrentSensorCodec.encode(updated)))
        assertEquals(updated, GoveeBattery.apply(updated, packet.copy(percent = 12, receivedAt = packet.receivedAt - 1)))
        assertEquals(rows, GoveeBattery.apply(rows, packet.copy(address = "00:00:00:00:00:00")))
        val ambiguous = listOf(sensor, sensor.copy(id = mac))
        assertEquals(ambiguous, GoveeBattery.apply(ambiguous, packet))
        val manual = sensor.copy(id = "opaque", batteryRadioAddress = mac)
        assertEquals(65, GoveeBattery.apply(listOf(manual), packet).single().batteryPercent)
    }
}
