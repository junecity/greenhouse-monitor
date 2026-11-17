package studio.junecity.greenhouse.cloud

import android.content.Context
import org.json.JSONObject
import studio.junecity.greenhouse.model.NotificationPreferences
import studio.junecity.greenhouse.settings.CloudEnrollment
import studio.junecity.greenhouse.widget.ClimateWidget

/** These fields travel with the greenhouse. Alarm audio, snooze and delivery remain phone preferences. */
data class AlertRules(
    val lowTemperatureF: Double, val highTemperatureF: Double,
    val lowHumidity: Double, val highHumidity: Double,
    val temperatureAlerts: Boolean, val humidityAlerts: Boolean, val offlineAlerts: Boolean
) {
    fun valid() = lowTemperatureF in -150.0..250.0 && highTemperatureF in -150.0..250.0 &&
        lowTemperatureF < highTemperatureF && lowHumidity in 0.0..100.0 && highHumidity in 0.0..100.0 && lowHumidity < highHumidity

    fun applyTo(p: NotificationPreferences) = p.copy(lowTemperatureF = lowTemperatureF, highTemperatureF = highTemperatureF,
        lowHumidity = lowHumidity, highHumidity = highHumidity, temperatureAlerts = temperatureAlerts,
        humidityAlerts = humidityAlerts, offlineAlerts = offlineAlerts)

    internal fun toJson(): JSONObject = JSONObject().put("lowTemperatureF", lowTemperatureF).put("highTemperatureF", highTemperatureF)
        .put("lowHumidity", lowHumidity).put("highHumidity", highHumidity).put("temperatureAlerts", temperatureAlerts)
        .put("humidityAlerts", humidityAlerts).put("offlineAlerts", offlineAlerts)

    companion object {
        fun from(p: NotificationPreferences) = AlertRules(p.lowTemperatureF, p.highTemperatureF, p.lowHumidity, p.highHumidity,
            p.temperatureAlerts, p.humidityAlerts, p.offlineAlerts)

        internal fun parse(j: JSONObject) = AlertRules(j.getDouble("lowTemperatureF"), j.getDouble("highTemperatureF"),
            j.getDouble("lowHumidity"), j.getDouble("highHumidity"), j.getBoolean("temperatureAlerts"),
            j.getBoolean("humidityAlerts"), j.getBoolean("offlineAlerts")).also { require(it.valid()) }
    }
}

data class AlertRuleState(val revision: Int, val rules: AlertRules?, val appliedRevision: Int) {
    companion object {
        internal fun parse(j: JSONObject): AlertRuleState {
            val revision = j.getInt("revision")
            val applied = j.getInt("appliedRevision")
            val rules = j.optJSONObject("rules")?.let(AlertRules::parse)
            require(revision >= 0 && applied in 0..revision && (revision == 0) == (rules == null))
            return AlertRuleState(revision, rules, applied)
        }
    }
}

/** Only the server whose monitoring loop applied this revision may acknowledge it to the backend. */
internal class AppliedAlertRules(private val context: Context, owner: CloudEnrollment) {
    private val store = context.getSharedPreferences("applied_greenhouse_alert_rules", Context.MODE_PRIVATE)
    private val key = "${owner.tenantId}/${owner.deviceId}/${owner.greenhouseId}"

    fun load(): AlertRuleState? = store.getString(key, null)?.let { raw ->
        runCatching { AlertRuleState.parse(JSONObject(raw)) }.getOrNull()
    }

    fun install(state: AlertRuleState): Boolean {
        val rules = state.rules ?: return false
        val current = load()
        if (current != null && current.revision == state.revision && current.rules == rules) return false
        val data = JSONObject().put("revision", state.revision).put("appliedRevision", state.revision).put("rules", rules.toJson())
        check(store.edit().putString(key, data.toString()).commit()) { "Could not save greenhouse alert rules." }
        ClimateWidget.changed(context)
        return true
    }
}
