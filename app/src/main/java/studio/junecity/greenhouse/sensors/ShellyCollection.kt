package studio.junecity.greenhouse.sensors

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.produceIn
import studio.junecity.greenhouse.data.AlertNotifier
import studio.junecity.greenhouse.data.HistoryStore
import studio.junecity.greenhouse.model.GoveeSensor
import studio.junecity.greenhouse.model.monitorsSensor
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
    packets: Map<String, ShellyAdvertisement>, accepts: (GoveeSensor) -> Boolean = { true }, onUpdated: (GoveeSensor) -> Unit
): List<GoveeSensor> {
    val changes = mutableListOf<GoveeSensor>()
    val all = update { old -> old.map { sensor ->
        if (accepts(sensor)) packets[sensor.id]?.update(sensor)?.also(changes::add) ?: sensor else sensor
    } }
    changes.forEach(onUpdated)
    return all
}

private val radioGate = Mutex()

/** Briefly use the normal collector when a standalone phone only has its home screen open. */
internal suspend fun sampleWidgetRadios(context: Context, owner: CloudEnrollment, authenticatedAs: CloudEnrollment = owner) {
    if (!owner.isStandaloneClient() || !SensorBluetooth.allowed(context) || !radioGate.tryLock()) return
    try {
        val access = CollectorEnrollment(context, owner, authenticatedAs)
        if (!access.isCurrent()) return
        val now = System.currentTimeMillis()
        if (CurrentSensorStore(context, "_standalone").load().none {
            it.isLocalRadio() && (!it.online || now - it.updatedAtEpochMs !in 0 until 60_000L)
        }) return
        withTimeoutOrNull(30_000L) { collectRadioLoop(context, access, "_standalone") }
    } finally { radioGate.unlock() }
}

/** One BLE scan serves the whole greenhouse. Only explicitly added addresses enter the inventory. */
internal suspend fun collectShelly(context: Context, owner: CloudEnrollment?, suffix: String,
                                   authenticatedAs: CloudEnrollment? = owner) =
    radioGate.withLock { collectRadioLoop(context, CollectorEnrollment(context, owner, authenticatedAs), suffix) }

private suspend fun collectRadioLoop(context: Context, access: CollectorEnrollment, suffix: String) = withContext(Dispatchers.IO) {
    val owner = access.source
    if (!access.isCurrent()) return@withContext
    launch { collectGoveeBatteries(context, access, suffix) }
    launch { collectEnvironmental(context, owner, suffix, access) }
    val store = CurrentSensorStore(context, suffix)
    val preferences = AppPreferencesStore(context, suffix)
    val coldWatch = studio.junecity.greenhouse.data.EveningColdWatch(context)
    val alerts = AlertNotifier(context, suffix)
    HistoryStore(context, suffix).use { history ->
        while (isActive && access.isCurrent()) {
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
                            if (!access.isCurrent()) break
                            val packets = mutableMapOf(first.id to first)
                            while (true) {
                                val event = events.tryReceive().getOrNull() ?: break
                                if (event.beacon.temperatureF != null) packets[event.id] = event
                            }
                            var changed = false
                            val all = access.whileCurrent {
                                val latest = preferences.loadForMonitoring(owner)
                                store.applyShellyPackets(packets, { latest.monitorsSensor(it) }) { sensor ->
                                    history.insert(sensor)
                                    alerts.evaluate(sensor, owner)
                                    changed = true
                                }
                            } ?: break
                            if (changed && packets.values.any { it.beacon.light == "Twilight" || it.beacon.light == "Dark" }) {
                                // An unavailable advisory must never interrupt radio collection or heartbeat reporting.
                                try { coldWatch.check(history, all, preferences.loadForMonitoring(owner), owner?.deviceId ?: suffix) }
                                catch (e: Exception) {
                                    if (e is CancellationException) throw e
                                    android.util.Log.w("EveningColdWatch", "Advisory unavailable: ${e.javaClass.simpleName}")
                                }
                            }
                            if (all.none { it.isShelly() }) break
                        }
                    } finally { events.cancel() }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { delay(15_000) } // Bluetooth can be switched off, or permission withdrawn.
        }
    }
}
