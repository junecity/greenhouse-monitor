package studio.junecity.greenhouse.camera

import org.junit.Assert.*
import org.junit.Test

class RemoteCameraAccessPolicyTest {
    private val now = 1_800_000_000_000L
    private fun entitlement(paid: Boolean = false, complimentary: Boolean = false,
        verified: Boolean = true, expiry: Long = now + 60_000) =
        RemoteCameraEntitlement("customer-1", paid, complimentary, expiry, verified)

    @Test fun localCameraIsAvailableWithoutSubscription() {
        assertTrue(RemoteCameraAccessPolicy.canViewLocalCamera())
    }

    @Test fun premiumAndComplimentaryAccountsCanRequestRemoteAccess() {
        assertEquals(CameraAccessDecision.ALLOW_REMOTE,
            RemoteCameraAccessPolicy.remoteDecision(entitlement(paid = true), now))
        assertEquals(CameraAccessDecision.ALLOW_REMOTE,
            RemoteCameraAccessPolicy.remoteDecision(entitlement(complimentary = true), now))
    }

    @Test fun freeAccountRequiresPremium() {
        assertEquals(CameraAccessDecision.REQUIRE_PREMIUM,
            RemoteCameraAccessPolicy.remoteDecision(entitlement(), now))
    }

    @Test fun missingUnverifiedAndExpiredEntitlementsFailClosed() {
        assertEquals(CameraAccessDecision.REQUIRE_VERIFICATION,
            RemoteCameraAccessPolicy.remoteDecision(null, now))
        assertEquals(CameraAccessDecision.REQUIRE_VERIFICATION,
            RemoteCameraAccessPolicy.remoteDecision(entitlement(paid = true, verified = false), now))
        assertEquals(CameraAccessDecision.REQUIRE_VERIFICATION,
            RemoteCameraAccessPolicy.remoteDecision(entitlement(paid = true, expiry = now), now))
    }
}
