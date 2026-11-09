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
    val requiresAcknowledgement: Boolean
)

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
