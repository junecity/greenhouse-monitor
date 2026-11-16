package studio.junecity.greenhouse.model

import studio.junecity.greenhouse.cloud.AlertRules

fun AppPreferences.forAlertsIn(houseId: String = activeGreenhouse.id): NotificationPreferences {
    val houseRules = if (houseId == activeGreenhouse.id && serverAlertRules != null) serverAlertRules
        else greenhouses.find { it.id == houseId }?.alertRules
    return houseRules?.applyTo(notifications) ?: notifications
}

fun AppPreferences.withAlertRules(houseId: String, rules: AlertRules): AppPreferences {
    require(rules.valid() && greenhouses.any { it.id == houseId })
    return copy(greenhouses = greenhouses.map { if (it.id == houseId) it.copy(alertRules = rules) else it })
}
