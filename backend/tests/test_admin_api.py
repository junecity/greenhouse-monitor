import json
import os
import pathlib
import sys
import time
import unittest
from types import SimpleNamespace
from unittest.mock import Mock, patch

os.environ.setdefault("AWS_ACCESS_KEY_ID", "test")
os.environ.setdefault("AWS_SECRET_ACCESS_KEY", "test")
os.environ.setdefault("AWS_DEFAULT_REGION", "us-east-1")
os.environ.setdefault("AWS_EC2_METADATA_DISABLED", "true")
os.environ.setdefault("TABLE_NAME", "greenhouse-test")
sys.path.insert(0, str(pathlib.Path(__file__).parents[1] / "src"))

import admin_api


def event(path="/admin/v1/dashboard", method="GET", role="Owner", exp=None, body=None, query=None, stage=None):
    claims = {
        "sub": "admin-subject", "email": "owner@example.test",
        "cognito:groups": role, "exp": str(exp or int(time.time()) + 300),
    }
    return {
        "rawPath": path,
        "body": json.dumps(body) if body is not None else None,
        "queryStringParameters": query,
        "requestContext": {
            "stage": stage,
            "requestId": "request-1", "http": {"method": method},
            "authorizer": {"jwt": {"claims": claims}},
        },
    }


class FakeClient:
    def __init__(self):
        self.transactions = []

    def transact_write_items(self, **kwargs):
        self.transactions.append(kwargs["TransactItems"])


class FakeTable:
    name = "greenhouse-test"

    def __init__(self, items=None):
        self.items = items or {}
        self.query_items = []
        self.updates = []
        self.puts = []
        self.meta = SimpleNamespace(client=FakeClient())

    def get_item(self, Key, **_kwargs):
        item = self.items.get((Key["pk"], Key["sk"]))
        return {"Item": item} if item else {}

    def query(self, **_kwargs):
        return {"Items": self.query_items}

    def update_item(self, **kwargs):
        self.updates.append(kwargs)
        return {}

    def put_item(self, **kwargs):
        self.puts.append(kwargs["Item"])
        return {}


class AdminAuthorizationTests(unittest.TestCase):
    def test_cors_preflight_does_not_require_admin_claims(self):
        preflight = event(method="OPTIONS")
        preflight["requestContext"].pop("authorizer")
        result = admin_api.handler(preflight, SimpleNamespace(aws_request_id="request"))
        self.assertEqual(204, result["statusCode"])

    def test_owner_group_is_accepted(self):
        principal = admin_api.admin_principal_from(event())
        self.assertEqual("Owner", principal.role)
        self.assertEqual("admin-subject", principal.subject)

    def test_missing_admin_group_is_denied(self):
        with self.assertRaisesRegex(admin_api.AdminAccessDenied, "administrator_role_required"):
            admin_api.admin_principal_from(event(role="Customer"))

    def test_expired_session_is_denied(self):
        with self.assertRaisesRegex(admin_api.AdminAccessDenied, "session_expired"):
            admin_api.admin_principal_from(event(exp=1), now=2)

    def test_read_only_cannot_mutate_customer(self):
        principal = admin_api.AdminPrincipal("sub", "read@example.test", "ReadOnly")
        with self.assertRaisesRegex(admin_api.AdminAccessDenied, "insufficient_role"):
            admin_api.set_customer_state(principal, "account-12345678", "SUSPENDED", "request")


class AdminDataTests(unittest.TestCase):
    def setUp(self):
        self.previous = admin_api.table
        self.fake = FakeTable({
            ("ACCOUNT#account-12345678", "METADATA"): {
                "accountId": "account-12345678", "tenantId": "tenant-12345678",
                "cognitoSubject": "customer-sub", "state": "ACTIVE", "createdAt": 1700000000,
            },
            ("ACCOUNT#account-12345678", "ENTITLEMENT"): {
                "accountId": "account-12345678", "tenantId": "tenant-12345678",
                "plan": "FREE", "state": "ACTIVE", "source": "NONE", "features": [],
            },
            ("DEVICE#server-12345678", "METADATA"): {
                "deviceId": "server-12345678", "accountId": "account-12345678",
                "tenantId": "tenant-12345678", "role": "GREENHOUSE_SERVER", "enabled": True,
                "createdAt": 1700000000,
            },
        })
        admin_api.table = self.fake

    def tearDown(self):
        admin_api.table = self.previous

    def test_customer_search_uses_real_records(self):
        accounts = [{
            "accountId": "account-12345678", "tenantId": "tenant-12345678",
            "cognitoSubject": "customer-sub",
            "displayName": "June Garden", "email": "june@example.test",
            "state": "ACTIVE", "createdAt": 1700000000,
        }]
        with patch.object(admin_api, "entity_items", return_value=accounts), \
             patch.object(admin_api, "customer_identities", return_value=[]):
            result = admin_api.list_customers("garden")
        self.assertEqual(1, result["count"])
        self.assertEqual("account-12345678", result["customers"][0]["accountId"])

    def test_customer_directory_includes_confirmed_identity_before_app_setup(self):
        identity = {
            "subject": "pending-subject", "email": "pending@example.test",
            "displayName": None, "enabled": True, "status": "CONFIRMED", "createdAt": 1700000000,
        }
        with patch.object(admin_api, "entity_items", return_value=[]), \
             patch.object(admin_api, "customer_identities", return_value=[identity]):
            result = admin_api.list_customers("pending@example.test")

        self.assertEqual(1, result["count"])
        self.assertEqual("PENDING_SETUP", result["customers"][0]["state"])
        self.assertFalse(result["customers"][0]["setupComplete"])

    def test_customer_directory_excludes_anonymous_legacy_account_shells(self):
        accounts = [
            {"accountId": "account-orphan", "tenantId": "tenant-orphan", "createdAt": 1700000001},
            {"accountId": "account-real", "tenantId": "tenant-real", "cognitoSubject": "real-subject", "email": "notify@junecity.com", "createdAt": 1700000000},
        ]
        with patch.object(admin_api, "entity_items", return_value=accounts), \
             patch.object(admin_api, "customer_identities", return_value=[]):
            result = admin_api.list_customers("")

        self.assertEqual(1, result["count"])
        self.assertEqual("account-real", result["customers"][0]["accountId"])

    def test_dashboard_does_not_count_anonymous_account_shells_as_customers(self):
        now = int(time.time())
        data = {
            "ACCOUNT": [
                {"accountId": "account-orphan", "state": "ACTIVE", "createdAt": now},
                {"accountId": "account-real", "cognitoSubject": "real-subject", "state": "ACTIVE", "createdAt": now},
            ],
            "DEVICE": [],
            "ENTITLEMENT": [
                {"accountId": "account-orphan", "plan": "FREE", "state": "ACTIVE", "createdAt": now},
                {"accountId": "account-real", "plan": "FREE", "state": "ACTIVE", "createdAt": now},
            ],
            "STREAM_SESSION": [],
        }
        with patch.object(admin_api, "entity_items", side_effect=lambda value: data[value]), \
             patch.object(admin_api, "customer_identities", return_value=[{"subject": "real-subject", "enabled": True, "createdAt": now}]), \
             patch.object(admin_api, "cost_snapshot", return_value={"status": "UNAVAILABLE"}), \
             patch.object(admin_api, "operation_alarms", return_value=[]):
            result = admin_api.dashboard()

        self.assertEqual(1, result["metrics"]["totalCustomers"])
        self.assertEqual(1, result["metrics"]["activeCustomers"])
        self.assertEqual(1, result["metrics"]["freeAccounts"])

    def test_pending_customer_detail_has_no_privileged_account_controls(self):
        identity = {
            "subject": "pending-subject", "email": "pending@example.test",
            "displayName": None, "enabled": True, "status": "CONFIRMED", "createdAt": 1700000000,
        }
        with patch.object(admin_api, "customer_identities", return_value=[identity]):
            result = admin_api.customer_detail("registration-pending-subject")

        self.assertFalse(result["setupComplete"])
        self.assertEqual("NOT_STARTED", result["entitlement"]["plan"])
        self.assertEqual([], result["devices"])

    def test_dashboard_counts_only_backend_records(self):
        now = int(time.time())
        data = {
            "ACCOUNT": [{"accountId": "a", "cognitoSubject": "customer-sub", "state": "ACTIVE", "createdAt": now}],
            "DEVICE": [{
                "deviceId": "d", "accountId": "a", "role": "GREENHOUSE_SERVER", "enabled": True,
                "lastHeartbeat": now, "sensorCount": 4, "createdAt": now,
            }],
            "ENTITLEMENT": [{
                "accountId": "a", "plan": "PREMIUM", "state": "ACTIVE",
                "source": "GOOGLE_PLAY_VERIFIED", "verifiedAt": now,
            }],
            "STREAM_SESSION": [{"state": "REQUESTED", "expiresAt": now + 60}],
        }
        with patch.object(admin_api, "entity_items", side_effect=lambda value: data[value]), \
             patch.object(admin_api, "customer_identities", return_value=[{"subject": "customer-sub", "enabled": True, "createdAt": now}]), \
             patch.object(admin_api, "cost_snapshot", return_value={"status": "UNAVAILABLE"}), \
             patch.object(admin_api, "operation_alarms", return_value=[]):
            result = admin_api.dashboard()
        self.assertEqual(1, result["metrics"]["totalCustomers"])
        self.assertEqual(1, result["metrics"]["premiumSubscribers"])
        self.assertEqual(4, result["metrics"]["connectedSensors"])
        self.assertEqual(1, result["metrics"]["activeCameraSessions"])

    def test_customer_groups_servers_under_greenhouses_and_reports_revocation(self):
        self.fake.query_items = [{
            "pk": "ACCOUNT#account-12345678", "sk": "DEVICE#server-12345678",
            "deviceId": "server-12345678", "accountId": "account-12345678",
            "tenantId": "tenant-12345678", "role": "GREENHOUSE_SERVER",
            "greenhouseId": "greenhouse-12345678", "enabled": False,
            "createdAt": 1700000000,
        }]
        self.fake.items[("DEVICE#server-12345678", "METADATA")]["enabled"] = False
        self.fake.items[("DEVICE#server-12345678", "METADATA")]["greenhouseId"] = "greenhouse-12345678"
        self.fake.items[("GREENHOUSE#greenhouse-12345678", "METADATA")] = {
            "greenhouseId": "greenhouse-12345678", "accountId": "account-12345678",
            "tenantId": "tenant-12345678", "name": "North House",
            "serverDeviceId": "server-12345678", "enabled": True,
            "createdAt": 1700000000,
        }

        result = admin_api.customer_detail("account-12345678")

        self.assertEqual(1, len(result["greenhouses"]))
        self.assertEqual("server-12345678", result["greenhouses"][0]["serverDeviceId"])
        self.assertFalse(result["greenhouses"][0]["enabled"])

    def test_support_can_revoke_device_but_cannot_grant_premium(self):
        principal = admin_api.AdminPrincipal("support", "support@example.test", "Support")
        result = admin_api.revoke_device(principal, "server-12345678", "request")
        self.assertFalse(result["enabled"])
        transaction = self.fake.meta.client.transactions[0]
        self.assertEqual(2, len(transaction))
        self.assertEqual("DEVICE#server-12345678", transaction[0]["Update"]["Key"]["pk"])
        self.assertEqual(False, transaction[0]["Update"]["ExpressionAttributeValues"][":enabled"])
        self.assertEqual("DEVICE_SESSION_REVOKED", self.fake.puts[0]["action"])
        with self.assertRaisesRegex(admin_api.AdminAccessDenied, "insufficient_role"):
            admin_api.set_complimentary(principal, "account-12345678", None, "request")

    def test_owner_suspension_updates_identity_and_account(self):
        principal = admin_api.AdminPrincipal("owner", "owner@example.test", "Owner")
        with patch.object(admin_api, "CUSTOMER_USER_POOL_ID", "pool"), \
             patch.object(admin_api.cognito, "admin_disable_user") as disable:
            result = admin_api.set_customer_state(principal, "account-12345678", "SUSPENDED", "request")
        self.assertEqual("SUSPENDED", result["state"])
        disable.assert_called_once_with(UserPoolId="pool", Username="customer-sub")
        self.assertEqual("CUSTOMER_STATE_CHANGED", self.fake.puts[0]["action"])

    def test_google_play_entitlement_cannot_be_overwritten_by_complimentary(self):
        self.fake.items[("ACCOUNT#account-12345678", "ENTITLEMENT")].update({
            "plan": "PREMIUM", "source": "GOOGLE_PLAY_VERIFIED",
        })
        principal = admin_api.AdminPrincipal("owner", "owner@example.test", "Owner")
        with self.assertRaisesRegex(admin_api.AdminConflict, "google_play_subscription_is_authoritative"):
            admin_api.set_complimentary(principal, "account-12345678", None, "request")

    def test_video_override_is_separate_from_google_play_billing(self):
        self.fake.items[("ACCOUNT#account-12345678", "ENTITLEMENT")].update({
            "plan": "PREMIUM", "source": "GOOGLE_PLAY_VERIFIED", "features": ["REMOTE_LIVE_VIDEO"],
        })
        principal = admin_api.AdminPrincipal("owner", "owner@example.test", "Owner")
        admin_api.set_video_access(principal, "account-12345678", "DISABLED", None, "testing", "request")

        update = self.fake.updates[0]
        self.assertIn("videoAccessOverride", update["UpdateExpression"])
        self.assertNotIn("plan=", update["UpdateExpression"])
        self.assertEqual("DISABLED", update["ExpressionAttributeValues"][":mode"])
        self.assertEqual("VIDEO_ACCESS_OVERRIDE_CHANGED", self.fake.puts[0]["action"])

    def test_handler_returns_secure_headers_and_role(self):
        result = admin_api.handler(event("/admin/v1/session"), SimpleNamespace(aws_request_id="request"))
        self.assertEqual(200, result["statusCode"])
        self.assertEqual("DENY", result["headers"]["x-frame-options"])
        self.assertEqual("Owner", json.loads(result["body"])["role"])

    def test_live_stage_prefix_is_removed_before_admin_routing(self):
        result = admin_api.handler(
            event("/dev/admin/v1/session", stage="dev"),
            SimpleNamespace(aws_request_id="request"),
        )

        self.assertEqual(200, result["statusCode"])
        self.assertEqual("Owner", json.loads(result["body"])["role"])


if __name__ == "__main__":
    unittest.main()
