package studio.junecity.greenhouse.cloud

enum class MonitoringAlertType {
    CriticalTemperature,
    CriticalHumidity,
    ServerOffline,
    SensorCommunicationFailure,
    Information;

    val isClimateAlarm get() = this == CriticalTemperature || this == CriticalHumidity
}

enum class MonitoringAlertPriority { Critical, High, Normal }

data class MonitoringAlert(
    val id: String,
    val greenhouseId: String,
    val type: MonitoringAlertType,
    val priority: MonitoringAlertPriority,
    val message: String,
    val occurredAtEpochMs: Long,
    val requiresAcknowledgement: Boolean,
    val serverId: String? = null,
    val sensorId: String? = null,
    val resolvedAtEpochMs: Long? = null
) {
    // The server marks verified sensor outages as actionable. Older aggregate notices stay informational.
    val isViewerAlarm get() = type.isClimateAlarm ||
        (type == MonitoringAlertType.SensorCommunicationFailure && requiresAcknowledgement)
}

data class AlertInbox(
    val alerts: List<MonitoringAlert>,
    val complete: Boolean,
    val criticalIncidentsVerified: Boolean = false,
    val sensorTemperatureInbox: Boolean = false,
    val sensorHumidityInbox: Boolean = false
)

internal fun currentAlerts(
    alerts: List<MonitoringAlert>,
    devices: List<RegisteredDevice>?,
    criticalIncidentsVerified: Boolean = false
): List<MonitoringAlert> {
    val servers = devices?.associateBy { it.id }
    val outages = alerts.filter { it.type == MonitoringAlertType.ServerOffline }.filter { alert ->
        val server = servers?.get(alert.serverId)
        // A missing inventory entry can also mean the inventory page was incomplete.
        server == null || (server.enabled && !server.communicationSuspended && !server.online &&
            server.greenhouseId == alert.greenhouseId && (server.lastSeenAtEpochMs ?: 0) < alert.occurredAtEpochMs)
    }.groupBy { it.serverId ?: it.greenhouseId }.values.map { incidents -> incidents.maxBy { it.occurredAtEpochMs } }
    val climateAlerts = alerts.filter { it.type.isClimateAlarm }
    // A verified inbox has already checked each incident's recovery and acknowledgement.
    // A legacy inbox without that check can contain obsolete episodes for the same source.
    val activeClimate = if (criticalIncidentsVerified) climateAlerts else climateAlerts
        .groupBy { it.type to Triple(it.greenhouseId, it.serverId, it.sensorId) }
        .values.map { incidents -> incidents.maxBy { it.occurredAtEpochMs } }
    return alerts.filter { it.type != MonitoringAlertType.ServerOffline && !it.type.isClimateAlarm } +
        outages + activeClimate
}

/** Keeps safety events independent from ordinary informational preferences. */
object MonitoringAlertPolicy {
    fun priority(type: MonitoringAlertType) = when (type) {
        MonitoringAlertType.CriticalTemperature, MonitoringAlertType.CriticalHumidity -> MonitoringAlertPriority.Critical
        MonitoringAlertType.ServerOffline,
        MonitoringAlertType.SensorCommunicationFailure -> MonitoringAlertPriority.High
        MonitoringAlertType.Information -> MonitoringAlertPriority.Normal
    }

    fun bypassesOrdinaryNotificationPreference(type: MonitoringAlertType) =
        type.isClimateAlarm || type == MonitoringAlertType.ServerOffline

    fun requiresAcknowledgement(type: MonitoringAlertType) =
        type.isClimateAlarm || type == MonitoringAlertType.ServerOffline
}

data class MonitoringHealth(
    val serverDeviceId: String,
    val greenhouseId: String,
    val sensorConnectionHealthy: Boolean,
    val criticalTemperature: Boolean,
    val batteryPercent: Int? = null,
    val charging: Boolean? = null,
    val networkType: String? = null,
    val sensorCount: Int? = null,
    val sensorProvider: String? = null,
    val cameraStatus: String? = null,
    val lastSensorSyncEpochMs: Long? = null,
    val lastDataUploadEpochMs: Long? = null,
    val appVersion: String? = null,
    val wifiSignalStrength: Int? = null,
    val managedUpdates: Boolean = false, val updateStatus: String? = null,
    val updateMode: String? = null, val updateStatusRequestId: String? = null,
    val temperatureEvaluated: Boolean? = null, val temperatureBreach: TemperatureBreach? = null,
    val alertRules: AlertRules? = null, val appliedAlertRulesRevision: Int? = null,
    val temperatureObservations: List<ClimateObservation>? = null,
    val remoteRecordingReady: Boolean = false,
    val humidityObservations: List<ClimateObservation>? = null,
    val humidityAlertsEnabled: Boolean? = null
)

class HeartbeatReporter(
    private val gateway: GreenhouseCloudGateway,
    private val nowEpochMs: () -> Long = System::currentTimeMillis
) {
    suspend fun report(health: MonitoringHealth): HeartbeatReply = gateway.reportHeartbeat(
        GreenhouseHeartbeat(
            serverDeviceId = health.serverDeviceId,
            greenhouseId = health.greenhouseId,
            recordedAtEpochMs = nowEpochMs(),
            sensorConnectionHealthy = health.sensorConnectionHealthy,
            criticalTemperature = health.criticalTemperature,
            temperatureEvaluated = health.temperatureEvaluated, temperatureBreach = health.temperatureBreach,
            temperatureObservations = health.temperatureObservations,
            humidityObservations = health.humidityObservations, humidityAlertsEnabled = health.humidityAlertsEnabled,
            alertRules = health.alertRules, appliedAlertRulesRevision = health.appliedAlertRulesRevision,
            batteryPercent = health.batteryPercent,
            charging = health.charging,
            networkType = health.networkType,
            wifiSignalStrength = health.wifiSignalStrength,
            sensorCount = health.sensorCount,
            sensorProvider = health.sensorProvider,
            cameraStatus = health.cameraStatus, remoteRecordingReady = health.remoteRecordingReady,
            lastSensorSyncEpochMs = health.lastSensorSyncEpochMs,
            lastDataUploadEpochMs = health.lastDataUploadEpochMs,
            appVersion = health.appVersion, managedUpdates = health.managedUpdates, updateStatus = health.updateStatus,
            updateMode = health.updateMode, updateStatusRequestId = health.updateStatusRequestId
        )
    )
}
