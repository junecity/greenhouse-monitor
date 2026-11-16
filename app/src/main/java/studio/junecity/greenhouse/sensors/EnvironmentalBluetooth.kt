package studio.junecity.greenhouse.sensors

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanFilter
import android.content.Context
import android.os.ParcelUuid
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import studio.junecity.greenhouse.data.AlertNotifier
import studio.junecity.greenhouse.data.HistoryStore
import studio.junecity.greenhouse.model.GoveeSensor
import studio.junecity.greenhouse.settings.*
import java.util.UUID

private fun sig(id: String): UUID = UUID.fromString("0000$id-0000-1000-8000-00805f9b34fb")
internal fun environmentalId(address: String) = normalizedBluetoothAddress(address)?.let { "ess:$it" }
internal fun GoveeSensor.isLocalRadio() = isShelly() || id.startsWith("ess:")

internal object EnvironmentalBluetooth {
    private val service = sig("181a")
    private val temperature = sig("2a6e")
    private val humidity = sig("2a6f")

    data class Nearby(val address: String, val name: String?)
    @SuppressLint("MissingPermission")
    fun discover(context: Context) = SensorBluetooth.scan(context,
        listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(service)).build()), true) {
        Nearby(it.device.address, it.scanRecord?.deviceName)
    }

    private fun word(bytes: ByteArray): Int {
        require(bytes.size == 2) { "Invalid sensor reading." }
        return (bytes[0].toInt() and 255) or ((bytes[1].toInt() and 255) shl 8)
    }
    internal fun temperatureF(bytes: ByteArray): Double {
        val value = word(bytes).toShort().toInt()
        require(value >= -27315) { "Temperature unavailable." }
        return value / 100.0 * 1.8 + 32
    }
    internal fun humidityPercent(bytes: ByteArray): Double {
        val value = word(bytes)
        require(value in 0..10000) { "Humidity unavailable." }
        return value / 100.0
    }

    // One GATT operation at a time. Closing the coroutine also releases the Android connection.
    @SuppressLint("MissingPermission")
    suspend fun read(context: Context, address: String): Pair<Double, Double> = withContext(Dispatchers.IO) {
        check(SensorBluetooth.allowed(context)) { "Allow Nearby devices to read this sensor." }
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        check(adapter?.isEnabled == true) { "Turn on Bluetooth to connect." }
        val device = requireNotNull(adapter).getRemoteDevice(address)
        val ready = CompletableDeferred<Unit>()
        val values = Channel<Pair<UUID, ByteArray>>(2)
        fun fail(message: String) {
            val failure = IllegalStateException(message)
            ready.completeExceptionally(failure)
            values.close(failure)
        }
        val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                when {
                    status != BluetoothGatt.GATT_SUCCESS || newState == BluetoothProfile.STATE_DISCONNECTED -> fail("Sensor disconnected. Move closer and try again.")
                    newState == BluetoothProfile.STATE_CONNECTED && !gatt.discoverServices() -> fail("Could not discover sensor services.")
                }
            }
            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) ready.complete(Unit) else fail("Could not read sensor services.")
            }
            private fun reading(c: BluetoothGattCharacteristic, bytes: ByteArray, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) values.trySend(c.uuid to bytes.copyOf())
                else fail("Sensor refused the reading. Check its Bluetooth pairing requirements.")
            }
            @Deprecated("Legacy Android callback")
            override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) =
                reading(characteristic, characteristic.value ?: byteArrayOf(), status)
            override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int) =
                reading(characteristic, value, status)
        }
        val gatt = device.connectGatt(context.applicationContext, false, callback, BluetoothDevice.TRANSPORT_LE)
            ?: error("Could not open sensor connection.")
        try {
            withTimeoutOrNull(12_000) {
                ready.await()
                val ess = gatt.getService(service) ?: error("This sensor does not expose the standard Environmental Sensing service.")
                suspend fun readValue(uuid: UUID): ByteArray {
                    val characteristic = ess.getCharacteristic(uuid)
                    check(characteristic != null && characteristic.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0) {
                        "This sensor must expose readable temperature and humidity."
                    }
                    check(gatt.readCharacteristic(characteristic)) { "Sensor busy. Try again." }
                    val (received, bytes) = values.receive()
                    check(received == uuid) { "Unexpected sensor response." }
                    return bytes
                }
                temperatureF(readValue(temperature)) to humidityPercent(readValue(humidity))
            } ?: error("Sensor timed out. Wake it, move closer and try again.")
        } finally {
            try { gatt.disconnect() } finally { gatt.close() }
        }
    }
}

internal suspend fun collectEnvironmental(context: Context, owner: CloudEnrollment?, suffix: String) {
    val secure = SecureSettingsStore(context)
    val store = CurrentSensorStore(context, suffix)
    val alerts = AlertNotifier(context, suffix)
    HistoryStore(context, suffix).use { history ->
        while (currentCoroutineContext().isActive && secure.cloudEnrollment() == owner) {
            val sensors = store.load().filter { it.id.startsWith("ess:") }
            if (SensorBluetooth.allowed(context)) for (sensor in sensors) {
                if (secure.cloudEnrollment() != owner) return
                try {
                    val (temp, humidity) = EnvironmentalBluetooth.read(context, sensor.id.removePrefix("ess:"))
                    if (secure.cloudEnrollment() != owner) return
                    val now = System.currentTimeMillis()
                    val all = store.update { rows -> rows.map {
                        if (it.id == sensor.id) it.copy(temperatureF = temp, humidityPercent = humidity, online = true, updatedAtEpochMs = now) else it
                    } }
                    all.find { it.id == sensor.id }?.let { history.insert(it); alerts.evaluate(it) }
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { /* Keep the last timestamp; the dashboard ages the reading out. */ }
            }
            delay(if (sensors.isEmpty()) 5_000 else 60_000)
        }
    }
}
