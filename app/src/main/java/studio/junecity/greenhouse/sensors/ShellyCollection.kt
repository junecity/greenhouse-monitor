package studio.junecity.greenhouse.sensors

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.produceIn
import studio.junecity.greenhouse.cloud.CloudDeviceRole
import studio.junecity.greenhouse.cloud.HeartbeatHealthStore
import studio.junecity.greenhouse.data.AlertNotifier
import studio.junecity.greenhouse.data.HistoryStore
import studio.junecity.greenhouse.model.GoveeSensor
import studio.junecity.greenhouse.settings.*

internal fun CurrentSensorStore.replaceGovee(readings: List<GoveeSensor>) =
    update { existing ->
        val cached = existing.associateBy { it.id }
        allowedReadings(readings).map { reading ->
            val last = cached[reading.id]
            if (last == null || !reading.sku.equals("H5179", true)) reading
            else reading.copy(batteryRadioAddress = last.batteryRadioAddress, batteryObservedAt = last.batteryObservedAt,
                batteryPercent = last.batteryPercent ?: reading.batteryPercent)
        } + existing.filter { it.isLocalRadio() }
    }

internal fun CurrentSensorStore.applyShellyPackets(
    packets: Map<String, ShellyAdvertisement>, onUpdated: (GoveeSensor) -> Unit
): List<GoveeSensor> {
    val changes = mutableListOf<GoveeSensor>()
    val all = update { old -> old.map { sensor ->
        packets[sensor.id]?.update(sensor)?.also(changes::add) ?: sensor
    } }
    changes.forEach(onUpdated)
    return all
}

/** One BLE scan serves the whole greenhouse. Only explicitly added addresses enter the inventory. */
internal suspend fun collectShelly(context: Context, owner: CloudEnrollment?, suffix: String) = withContext(Dispatchers.IO) {
    if (owner?.role == CloudDeviceRole.ViewerClient && !owner.isStandaloneClient()) return@withContext
    launch { collectGoveeBatteries(context, owner, suffix) }
    launch { collectEnvironmental(context, owner, suffix) }
    val secure = SecureSettingsStore(context)
    val store = CurrentSensorStore(context, suffix)
    val preferences = AppPreferencesStore(context, suffix)
    val coldWatch = studio.junecity.greenhouse.data.EveningColdWatch(context)
    val alerts = AlertNotifier(context, suffix)
    HistoryStore(context, suffix).use { history ->
        while (isActive && secure.cloudEnrollment() == owner) {
            if (!SensorBluetooth.allowed(context) || store.load().none { it.isShelly() }) {
                delay(4_000)
                continue
            }
            try {
                coroutineScope {
                    val events = ShellyBluetooth.advertisements(context, false).produceIn(this)
                    try {
                        while (isActive) {
                            val first = events.receive()
                            delay(750)
                            if (secure.cloudEnrollment() != owner) break
                            val packets = mutableMapOf(first.id to first)
                            while (true) {
                                val event = events.tryReceive().getOrNull() ?: break
                                if (event.beacon.temperatureF != null) packets[event.id] = event
                            }
                            var changed = false
                            val all = store.applyShellyPackets(packets) { sensor ->
                                history.insert(sensor)
                                alerts.evaluate(sensor)
                                changed = true
                            }
                            if (changed && packets.values.any { it.beacon.light == "Twilight" || it.beacon.light == "Dark" }) {
                                // An unavailable advisory must never interrupt radio collection or heartbeat reporting.
                                try { coldWatch.check(history, all, preferences.load(), owner?.deviceId ?: suffix) }
                                catch (e: Exception) {
                                    if (e is CancellationException) throw e
                                    android.util.Log.w("EveningColdWatch", "Advisory unavailable: ${e.javaClass.simpleName}")
                                }
                            }
                            if (all.none { it.isShelly() }) break
                            if (changed && owner?.role == CloudDeviceRole.GreenhouseServer) {
                                val rules = preferences.load().notifications
                                HeartbeatHealthStore(context).save(all.filter { it.isFresh() }, rules.lowTemperatureF, rules.highTemperatureF)
                            }
                        }
                    } finally { events.cancel() }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { delay(15_000) } // Bluetooth can be switched off, or permission withdrawn.
        }
    }
}
