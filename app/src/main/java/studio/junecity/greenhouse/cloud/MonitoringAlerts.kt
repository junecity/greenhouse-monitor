package studio.junecity.greenhouse.cloud

enum class MonitoringAlertType {
    CriticalTemperature,
    ServerOffline,
    SensorCommunicationFailure,
    Information
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
    val serverId: String? = null
)

data class AlertInbox(val alerts: List<MonitoringAlert>, val complete: Boolean)

internal fun currentAlerts(alerts: List<MonitoringAlert>, devices: List<RegisteredDevice>?): List<MonitoringAlert> {
    val servers = devices?.associateBy { it.id }
    val outages = alerts.filter { it.type == MonitoringAlertType.ServerOffline }.filter { alert ->
        val server = servers?.get(alert.serverId)
        // A missing inventory entry can also mean the inventory page was incomplete.
        server == null || (server.enabled && !server.communicationSuspended && !server.online &&
            server.greenhouseId == alert.greenhouseId && (server.lastSeenAtEpochMs ?: 0) < alert.occurredAtEpochMs)
    }.groupBy { it.serverId ?: it.greenhouseId }.values.map { incidents -> incidents.maxBy { it.occurredAtEpochMs } }
    return alerts.filter { it.type != MonitoringAlertType.ServerOffline } + outages
}

/** Keeps safety events independent from ordinary informational preferences. */
object MonitoringAlertPolicy {
    fun priority(type: MonitoringAlertType) = when (type) {
        MonitoringAlertType.CriticalTemperature -> MonitoringAlertPriority.Critical
        MonitoringAlertType.ServerOffline,
        MonitoringAlertType.SensorCommunicationFailure -> MonitoringAlertPriority.High
        MonitoringAlertType.Information -> MonitoringAlertPriority.Normal
    }

    fun bypassesOrdinaryNotificationPreference(type: MonitoringAlertType) =
        type == MonitoringAlertType.CriticalTemperature || type == MonitoringAlertType.ServerOffline

    fun requiresAcknowledgement(type: MonitoringAlertType) =
        type == MonitoringAlertType.CriticalTemperature || type == MonitoringAlertType.ServerOffline
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
    val appVersion: String? = null
)

class HeartbeatReporter(
    private val gateway: GreenhouseCloudGateway,
    private val nowEpochMs: () -> Long = System::currentTimeMillis
) {
    suspend fun report(health: MonitoringHealth) = gateway.reportHeartbeat(
        GreenhouseHeartbeat(
            serverDeviceId = health.serverDeviceId,
            greenhouseId = health.greenhouseId,
            recordedAtEpochMs = nowEpochMs(),
            sensorConnectionHealthy = health.sensorConnectionHealthy,
            criticalTemperature = health.criticalTemperature,
            batteryPercent = health.batteryPercent,
            charging = health.charging,
            networkType = health.networkType,
            sensorCount = health.sensorCount,
            sensorProvider = health.sensorProvider,
            cameraStatus = health.cameraStatus,
            lastSensorSyncEpochMs = health.lastSensorSyncEpochMs,
            lastDataUploadEpochMs = health.lastDataUploadEpochMs,
            appVersion = health.appVersion
        )
    )
}
