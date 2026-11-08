package studio.junecity.greenhouse.cloud

enum class AccountPlan { Free, Premium, Complimentary }
enum class EntitlementState { Active, Expired, Revoked, Pending }
enum class EntitlementFeature { RemoteLiveVideo }
enum class EntitlementSource { GooglePlayBackendVerification, BackendComplimentaryGrant, None }
enum class CloudDeviceRole { GreenhouseServer, ViewerClient }

data class CloudPrincipal(
    val accountId: String,
    val tenantId: String,
    val deviceId: String
)

data class RegisteredDevice(
    val id: String,
    val tenantId: String,
    val role: CloudDeviceRole,
    val enabled: Boolean = true,
    val greenhouseId: String? = null
)

data class CloudAccount(val accountId: String, val tenantId: String)

data class CloudGreenhouse(val id: String, val tenantId: String)

data class EntitlementSnapshot(
    val accountId: String,
    val plan: AccountPlan,
    val state: EntitlementState,
    val source: EntitlementSource,
    val features: Set<EntitlementFeature>,
    val expiresAtEpochMs: Long? = null,
    val verifiedAtEpochMs: Long
) {
    fun allows(feature: EntitlementFeature, nowEpochMs: Long): Boolean =
        state == EntitlementState.Active &&
            feature in features &&
            (expiresAtEpochMs == null || expiresAtEpochMs > nowEpochMs) &&
            source != EntitlementSource.None
}

enum class RemoteAccessDenial {
    Unauthenticated,
    DeviceDisabled,
    CrossTenant,
    WrongDeviceRole,
    PremiumRequired,
    EntitlementExpired,
    EntitlementRevoked
}

sealed interface RemoteAccessDecision {
    data object Allowed : RemoteAccessDecision
    data class Denied(val reason: RemoteAccessDenial) : RemoteAccessDecision
}

object RemoteAccessPolicy {
    fun authorizeViewer(
        principal: CloudPrincipal?,
        viewer: RegisteredDevice,
        server: RegisteredDevice,
        greenhouse: CloudGreenhouse,
        entitlement: EntitlementSnapshot,
        nowEpochMs: Long
    ): RemoteAccessDecision {
        principal ?: return RemoteAccessDecision.Denied(RemoteAccessDenial.Unauthenticated)
        if (!viewer.enabled || !server.enabled) return RemoteAccessDecision.Denied(RemoteAccessDenial.DeviceDisabled)
        if (principal.tenantId != viewer.tenantId || viewer.tenantId != server.tenantId || server.tenantId != greenhouse.tenantId) {
            return RemoteAccessDecision.Denied(RemoteAccessDenial.CrossTenant)
        }
        if (principal.deviceId != viewer.id || viewer.role != CloudDeviceRole.ViewerClient || server.role != CloudDeviceRole.GreenhouseServer) {
            return RemoteAccessDecision.Denied(RemoteAccessDenial.WrongDeviceRole)
        }
        if (entitlement.accountId != principal.accountId) return RemoteAccessDecision.Denied(RemoteAccessDenial.CrossTenant)
        if (entitlement.state == EntitlementState.Revoked) return RemoteAccessDecision.Denied(RemoteAccessDenial.EntitlementRevoked)
        if (entitlement.state == EntitlementState.Expired || entitlement.expiresAtEpochMs?.let { it <= nowEpochMs } == true) {
            return RemoteAccessDecision.Denied(RemoteAccessDenial.EntitlementExpired)
        }
        if (!entitlement.allows(EntitlementFeature.RemoteLiveVideo, nowEpochMs)) {
            return RemoteAccessDecision.Denied(RemoteAccessDenial.PremiumRequired)
        }
        return RemoteAccessDecision.Allowed
    }
}

data class PairingChallenge(
    val challengeId: String,
    val displayCode: String,
    val serverDeviceId: String,
    val expiresAtEpochMs: Long
)

data class IceServer(
    val urls: List<String>,
    val username: String? = null,
    val credential: String? = null
)

data class RemoteStreamingSession(
    val id: String,
    val greenhouseId: String,
    val serverDeviceId: String,
    val viewerDeviceId: String,
    val signalingChannelArn: String,
    val region: String,
    val iceServers: List<IceServer>,
    val expiresAtEpochMs: Long,
    val signalingWebSocketUrl: String? = null,
    val peerClientId: String? = null
)

data class GreenhouseHeartbeat(
    val serverDeviceId: String,
    val greenhouseId: String,
    val recordedAtEpochMs: Long,
    val sensorConnectionHealthy: Boolean,
    val criticalTemperature: Boolean
)

interface GreenhouseCloudGateway {
    suspend fun createPairingChallenge(serverDeviceId: String): PairingChallenge
    suspend fun claimPairingChallenge(displayCode: String, viewerDeviceId: String): RegisteredDevice
    suspend fun entitlement(): EntitlementSnapshot
    suspend fun createRemoteStreamingSession(greenhouseId: String, viewerDeviceId: String): RemoteStreamingSession
    suspend fun endRemoteStreamingSession(sessionId: String)
    suspend fun reportHeartbeat(heartbeat: GreenhouseHeartbeat)
}

