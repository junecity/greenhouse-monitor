package studio.junecity.greenhouse.cloud

import android.content.Context
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import studio.junecity.greenhouse.model.forAlertsIn
import studio.junecity.greenhouse.model.sensorHouse
import studio.junecity.greenhouse.settings.AppPreferencesStore
import studio.junecity.greenhouse.settings.CloudEnrollment
import studio.junecity.greenhouse.settings.CurrentSensorCodec

internal data class ServerTemperatures(
    val house: String,
    val server: String,
    val generation: Long,
    val evaluatedAt: Long,
    val observations: List<TemperatureObservation>,
    val uploadTime: Long? = null
)

internal data class ViewerReadingCheck(val readings: List<ServerTemperatures>, val complete: Boolean)

/** Read the collector's applied limits and inventory without changing the viewer's selected house or history. */
internal fun temperatureSnapshot(
    context: Context, source: AccountDevice, snapshot: JSONObject, now: Long
): ServerTemperatures? {
    val house = source.device.greenhouseId ?: return null
    val health = snapshot.serverHealth(house) ?: return null
    if (health.sourceDeviceId != source.device.id || health.reportedAt < source.enrollmentEpochMs ||
        health.reportedAt !in (now - 600_000L)..now || snapshot.optInt("schema") !in 1..2) return null
    val saved = snapshot.optJSONObject("preferences") ?: return null
    val planId = saved.optString("active")
    val plans = saved.optJSONArray("greenhouses") ?: return null
    val plan = (0 until plans.length()).mapNotNull(plans::optJSONObject).find { it.optString("id") == planId } ?: return null
    // The preferences decoder has UI defaults. Only use limits actually present in this snapshot.
    if (!plan.has("alertRules") || plan.isNull("alertRules")) {
        val legacy = saved.optJSONObject("notifications") ?: return null
        if (!legacy.optDouble("lowT").isFinite() || !legacy.optDouble("highT").isFinite()) return null
    } else if (plan.optJSONObject("alertRules") == null) return null
    val preferences = AppPreferencesStore(context).decode(saved)
    val limits = preferences.forAlertsIn(planId)
    val inventory = CurrentSensorCodec.decode(snapshot.optJSONArray("sensors") ?: return null)
        .filter { preferences.sensorHouse(it) == planId && it.updatedAtEpochMs >= source.enrollmentEpochMs }
    val observations = assessTemperature(inventory, limits.lowTemperatureF, limits.highTemperatureF, now).observations
    return ServerTemperatures(house, source.device.id, source.enrollmentEpochMs, health.reportedAt, observations, source.lastDataUploadEpochMs)
}

internal suspend fun AuthenticatedCloudGateway.viewerReadingCheck(
    context: Context, enrollment: CloudEnrollment, inbox: AlertInbox?
): ViewerReadingCheck {
    val sources = ClientTemperatureAlarms.readingSources(context, enrollment, devices(), System.currentTimeMillis())
        .filterNot { inbox?.sensorTemperatureInbox == true && inbox.complete && it.sensorTemperatureMode }
    var complete = true
    val readings = mutableListOf<ServerTemperatures>()
    for (source in sources) {
        try {
            if (ClientTemperatureAlarms.checkedUpload(context, enrollment, source)) continue
            val snapshot = temperatureBackup(requireNotNull(source.device.greenhouseId)) ?: continue
            temperatureSnapshot(context, source, snapshot, System.currentTimeMillis())?.let(readings::add)
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
