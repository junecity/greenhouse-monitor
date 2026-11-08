package studio.junecity.greenhouse.camera

/**
 * Backend-supplied entitlement snapshot. Never trust client-provided plan labels
 * for cloud signaling or TURN credential issuance.
 */
data class RemoteCameraEntitlement(
    val customerId: String,
    val premiumActive: Boolean,
    val complimentaryAccess: Boolean,
    val expiresAtEpochMillis: Long,
    val serverVerified: Boolean
)

enum class CameraAccessDecision {
    ALLOW_REMOTE,
    REQUIRE_PREMIUM,
    REQUIRE_VERIFICATION
}

/**
 * Local camera viewing is not gated. Remote viewing is gated by a short-lived,
 * server-verified entitlement; AWS must independently recheck before issuing
 * signaling credentials and TURN access.
 */
object RemoteCameraAccessPolicy {
    fun canViewLocalCamera(): Boolean = true

    fun remoteDecision(
        entitlement: RemoteCameraEntitlement?,
        nowEpochMillis: Long
    ): CameraAccessDecision {
        if (entitlement == null || !entitlement.serverVerified ||
            entitlement.customerId.isBlank() || entitlement.expiresAtEpochMillis <= nowEpochMillis
        ) return CameraAccessDecision.REQUIRE_VERIFICATION
        return if (entitlement.premiumActive || entitlement.complimentaryAccess)
            CameraAccessDecision.ALLOW_REMOTE
        else CameraAccessDecision.REQUIRE_PREMIUM
    }
}
