package studio.junecity.greenhouse.cloud

import org.json.JSONObject
import studio.junecity.greenhouse.model.DeviceStatus

internal fun DeviceStatus.toHealthJson() = JSONObject()
    .put("reportedAt", reportedAt).put("batteryPercent", batteryPercent).put("charging", charging)
    .put("appVersion", appVersion)
    .put("appVersionCode", appVersionCode)
    .put("network", network).put("signalDbm", signalDbm)

internal fun JSONObject.serverHealth(): DeviceStatus? {
    val report = optJSONObject("serverHealth") ?: return null
    val at = report.optLong("reportedAt").takeIf { it > 0 } ?: return null
    val network = report.optString("network").takeIf { it in setOf("Wi-Fi", "Cellular", "Network", "Offline") } ?: return null
    return DeviceStatus(report.optInt("batteryPercent", -1).takeIf { it in 0..100 },
        report.opt("charging") as? Boolean, network == "Wi-Fi", network,
        report.optInt("signalDbm", 0).takeIf { it in -160..-1 }, at, report.optString("appVersion").takeIf { it.isNotBlank() },
        report.optInt("appVersionCode").takeIf { it > 0 })
}
