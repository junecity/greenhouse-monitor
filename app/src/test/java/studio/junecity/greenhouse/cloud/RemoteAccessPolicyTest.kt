package studio.junecity.greenhouse.cloud

import org.junit.Assert.assertEquals
import org.junit.Test

class RemoteAccessPolicyTest {
    private val now = 1_800_000_000_000L
    private val principal = CloudPrincipal("account-a", "tenant-a", "viewer-a")
    private val viewer = RegisteredDevice("viewer-a", "tenant-a", CloudDeviceRole.ViewerClient)
    private val server = RegisteredDevice("server-a", "tenant-a", CloudDeviceRole.GreenhouseServer)
    private val greenhouse = CloudGreenhouse("greenhouse-a", "tenant-a")

    private fun entitlement(
        plan: AccountPlan = AccountPlan.Premium,
        state: EntitlementState = EntitlementState.Active,
        source: EntitlementSource = EntitlementSource.GooglePlayBackendVerification,
        expiresAt: Long? = now + 60_000
    ) = EntitlementSnapshot(
        "account-a", plan, state, source,
        if (plan == AccountPlan.Free) emptySet() else setOf(EntitlementFeature.RemoteLiveVideo),
        expiresAt, now
    )

    @Test fun premiumViewerIsAllowed() {
        assertEquals(RemoteAccessDecision.Allowed, RemoteAccessPolicy.authorizeViewer(principal, viewer, server, greenhouse, entitlement(), now))
    }

    @Test fun freeViewerIsDenied() {
        assertEquals(RemoteAccessDecision.Denied(RemoteAccessDenial.PremiumRequired), RemoteAccessPolicy.authorizeViewer(principal, viewer, server, greenhouse, entitlement(AccountPlan.Free), now))
    }

    @Test fun backendComplimentaryGrantIsAllowedWithoutHardcodedIdentity() {
        val grant = entitlement(AccountPlan.Complimentary, source = EntitlementSource.BackendComplimentaryGrant, expiresAt = null)
        assertEquals(RemoteAccessDecision.Allowed, RemoteAccessPolicy.authorizeViewer(principal, viewer, server, greenhouse, grant, now))
    }

    @Test fun expiredSubscriptionIsDenied() {
        assertEquals(RemoteAccessDecision.Denied(RemoteAccessDenial.EntitlementExpired), RemoteAccessPolicy.authorizeViewer(principal, viewer, server, greenhouse, entitlement(expiresAt = now), now))
    }

    @Test fun revokedSubscriptionIsDenied() {
        assertEquals(RemoteAccessDecision.Denied(RemoteAccessDenial.EntitlementRevoked), RemoteAccessPolicy.authorizeViewer(principal, viewer, server, greenhouse, entitlement(state = EntitlementState.Revoked), now))
    }

    @Test fun crossCustomerViewerIsDenied() {
        val otherServer = server.copy(tenantId = "tenant-b")
        assertEquals(RemoteAccessDecision.Denied(RemoteAccessDenial.CrossTenant), RemoteAccessPolicy.authorizeViewer(principal, viewer, otherServer, greenhouse, entitlement(), now))
    }

    @Test fun disabledDeviceIsDenied() {
        assertEquals(RemoteAccessDecision.Denied(RemoteAccessDenial.DeviceDisabled), RemoteAccessPolicy.authorizeViewer(principal, viewer.copy(enabled = false), server, greenhouse, entitlement(), now))
    }
}

