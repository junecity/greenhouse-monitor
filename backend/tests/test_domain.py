import pathlib
import sys
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).parents[1] / "src"))

from domain import AccessDenied, Principal, authorize_stream, is_missing_heartbeat, pairing_code_hash, require_device_id


class AuthorizationTests(unittest.TestCase):
    def setUp(self):
        self.now = 1_800_000_000
        self.principal = Principal("account-a", "tenant-a", "viewer-a")
        self.viewer = {"deviceId": "viewer-a", "tenantId": "tenant-a", "role": "VIEWER_CLIENT", "enabled": True}
        self.server = {"deviceId": "server-a", "tenantId": "tenant-a", "role": "GREENHOUSE_SERVER", "enabled": True}
        self.greenhouse = {"greenhouseId": "greenhouse-a", "tenantId": "tenant-a"}
        self.entitlement = {"accountId": "account-a", "state": "ACTIVE", "source": "GOOGLE_PLAY_VERIFIED", "features": ["REMOTE_LIVE_VIDEO"], "expiresAt": self.now + 60}

    def test_verified_premium_is_allowed(self):
        authorize_stream(self.principal, self.viewer, self.server, self.greenhouse, self.entitlement, self.now)

    def test_complimentary_backend_grant_is_allowed(self):
        self.entitlement.update(source="COMPLIMENTARY_BACKEND_GRANT", expiresAt=None)
        authorize_stream(self.principal, self.viewer, self.server, self.greenhouse, self.entitlement, self.now)

    def test_cross_tenant_is_denied(self):
        self.server["tenantId"] = "tenant-b"
        with self.assertRaisesRegex(AccessDenied, "cross_tenant"):
            authorize_stream(self.principal, self.viewer, self.server, self.greenhouse, self.entitlement, self.now)

    def test_expired_is_denied(self):
        self.entitlement["expiresAt"] = self.now
        with self.assertRaisesRegex(AccessDenied, "expired"):
            authorize_stream(self.principal, self.viewer, self.server, self.greenhouse, self.entitlement, self.now)

    def test_revoked_is_denied(self):
        self.entitlement["state"] = "REVOKED"
        with self.assertRaisesRegex(AccessDenied, "revoked"):
            authorize_stream(self.principal, self.viewer, self.server, self.greenhouse, self.entitlement, self.now)

    def test_free_access_is_denied(self):
        self.entitlement["features"] = []
        with self.assertRaisesRegex(AccessDenied, "premium_required"):
            authorize_stream(self.principal, self.viewer, self.server, self.greenhouse, self.entitlement, self.now)


class HeartbeatPolicyTests(unittest.TestCase):
    def test_enabled_stale_server_is_missing(self):
        self.assertTrue(is_missing_heartbeat({"enabled": True, "lastHeartbeat": 100}, 101))

    def test_disabled_or_recent_server_is_not_missing(self):
        self.assertFalse(is_missing_heartbeat({"enabled": False, "lastHeartbeat": 100}, 101))
        self.assertFalse(is_missing_heartbeat({"enabled": True, "lastHeartbeat": 101}, 101))


class PairingPolicyTests(unittest.TestCase):
    def test_pairing_code_is_hashed_and_stable(self):
        code = "long-single-use-pairing-secret"
        value = pairing_code_hash(code)
        self.assertEqual(64, len(value))
        self.assertEqual(value, pairing_code_hash(f"  {code}  "))
        self.assertNotIn(code, value)

    def test_short_pairing_code_is_rejected(self):
        with self.assertRaisesRegex(AccessDenied, "invalid_pairing_code"):
            pairing_code_hash("123456")

    def test_device_id_validation(self):
        self.assertEqual("viewer-12345678", require_device_id("viewer-12345678"))
        with self.assertRaisesRegex(AccessDenied, "invalid_device_id"):
            require_device_id("bad id")


if __name__ == "__main__":
    unittest.main()

