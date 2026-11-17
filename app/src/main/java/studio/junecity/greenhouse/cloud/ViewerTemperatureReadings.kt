package studio.junecity.greenhouse.cloud

import android.content.Context
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import studio.junecity.greenhouse.model.sensorHouse
import studio.junecity.greenhouse.settings.AppPreferencesStore
import studio.junecity.greenhouse.settings.CloudEnrollment
import studio.junecity.greenhouse.settings.CurrentSensorCodec

internal data class ServerTemperatures(
    val house: String,
    val server: String,
    val generation: Long,
    val evaluatedAt: Long,
    val observations: List<ClimateObservation>,
    val uploadTime: Long? = null,
    val humidityEnabled: Boolean? = null
)

internal data class ViewerReadingCheck(val readings: List<ServerTemperatures>, val complete: Boolean)

/** Read the collector's applied limits and inventory without changing the viewer's selected house or history. */
internal fun temperatureSnapshot(
    context: Context, source: AccountDevice, snapshot: JSONObject, now: Long
): ServerTemperatures? {
    if (snapshot.optBoolean("monitoringPaused")) return null
    val house = source.device.greenhouseId ?: return null
    val health = snapshot.serverHealth(house) ?: return null
    if (health.sourceDeviceId != source.device.id || health.reportedAt < source.enrollmentEpochMs ||
        health.reportedAt !in (now - 600_000L)..now || snapshot.optInt("schema") !in 1..2) return null
    val saved = snapshot.optJSONObject("preferences") ?: return null
    val planId = saved.optString("active")
    val plans = saved.optJSONArray("greenhouses") ?: return null
    val plan = (0 until plans.length()).mapNotNull(plans::optJSONObject).find { it.optString("id") == planId } ?: return null
    // The preferences decoder has UI defaults. Only use limits actually present in this snapshot.
    val scoped = !plan.isNull("alertRules")
    val rules = (if (scoped) plan.optJSONObject("alertRules") else saved.optJSONObject("notifications")) ?: return null
    val lowT = rules.optDouble(if (scoped) "lowTemperatureF" else "lowT")
    val highT = rules.optDouble(if (scoped) "highTemperatureF" else "highT")
    val lowH = rules.optDouble(if (scoped) "lowHumidity" else "lowH")
    val highH = rules.optDouble(if (scoped) "highHumidity" else "highH")
    val humidityEnabled = rules.opt(if (scoped) "humidityAlerts" else "humidity") as? Boolean
    if ((!lowT.isFinite() || !highT.isFinite() || lowT >= highT) &&
        (humidityEnabled == null || lowH !in 0.0..100.0 || highH !in 0.0..100.0 || lowH >= highH)) return null
    val preferences = AppPreferencesStore(context).decode(saved)
    val inventory = CurrentSensorCodec.decode(snapshot.optJSONArray("sensors") ?: return null)
        .filter { preferences.sensorHouse(it) == planId && it.updatedAtEpochMs >= source.enrollmentEpochMs }
    val observations = assessTemperature(inventory, lowT, highT, now).observations +
        assessHumidity(inventory, lowH, highH, humidityEnabled == true, now)
    return ServerTemperatures(house, source.device.id, source.enrollmentEpochMs, health.reportedAt, observations,
        source.lastDataUploadEpochMs, humidityEnabled)
}

internal suspend fun AuthenticatedCloudGateway.viewerReadingCheck(
    context: Context, enrollment: CloudEnrollment, inbox: AlertInbox?
): ViewerReadingCheck {
    val inventory = devices()
    try {
        studio.junecity.greenhouse.widget.WidgetSyncWorker.serverUploads(context, enrollment, inventory)
    } catch (e: RuntimeException) {
        android.util.Log.w("GreenhouseWidget", "Unable to schedule widget upload check", e)
    }
    val sources = ClientTemperatureAlarms.readingSources(context, enrollment, inventory, System.currentTimeMillis())
    var complete = true
    val readings = mutableListOf<ServerTemperatures>()
    for (source in sources) {
        val temperatureHandled = inbox?.complete == true && inbox.sensorTemperatureInbox && source.sensorTemperatureMode
        val humidityHandled = inbox?.complete == true && inbox.sensorHumidityInbox && source.sensorHumidityMode
        if (temperatureHandled && humidityHandled) continue
        try {
            if (ClientTemperatureAlarms.checkedUpload(context, enrollment, source)) continue
            val snapshot = temperatureBackup(requireNotNull(source.device.greenhouseId)) ?: continue
            val batch = temperatureSnapshot(context, source, snapshot, System.currentTimeMillis()) ?: continue
            readings += batch.copy(observations = batch.observations.filter { observation ->
                if (observation.type == MonitoringAlertType.CriticalHumidity) !humidityHandled else !temperatureHandled
            }, humidityEnabled = batch.humidityEnabled.takeUnless { humidityHandled })
        } catch (c: CancellationException) {
            throw c
        } catch (failure: Exception) {
            if (failure.invalidViewerAuthorization()) throw failure
            complete = false
        }
    }
    return ViewerReadingCheck(readings, complete)
}

internal fun Exception.invalidViewerAuthorization() = this is CloudApiException && statusCode == 403 &&
    message in setOf("device_disabled", "device_revoked", "device_missing", "account_missing",
        "account_identity_mismatch", "account_suspended", "device_credential_invalid")
