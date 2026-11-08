import json
import os
import pathlib
import sys
import unittest
from types import SimpleNamespace
from unittest.mock import patch

os.environ.setdefault("AWS_ACCESS_KEY_ID", "test")
os.environ.setdefault("AWS_SECRET_ACCESS_KEY", "test")
os.environ.setdefault("AWS_DEFAULT_REGION", "us-east-1")
os.environ.setdefault("AWS_EC2_METADATA_DISABLED", "true")
os.environ.setdefault("TABLE_NAME", "greenhouse-test")
sys.path.insert(0, str(pathlib.Path(__file__).parents[1] / "src"))

import app


def event(path, method="POST", subject="cognito-subject", device_id=None, body=None):
    headers = {}
    if device_id:
        headers["x-greenhouse-device-id"] = device_id
    return {
        "rawPath": path,
        "headers": headers,
        "body": json.dumps(body) if body is not None else None,
        "requestContext": {
            "http": {"method": method},
            "authorizer": {"jwt": {"claims": {"sub": subject}}},
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
        self.client = FakeClient()
        self.meta = SimpleNamespace(client=self.client)
        self.query_items = []
        self.updates = []

    def get_item(self, Key, **_kwargs):
        value = self.items.get((Key["pk"], Key["sk"]))
        return {"Item": value} if value else {}

    def query(self, **_kwargs):
        return {"Items": self.query_items}

    def update_item(self, **kwargs):
        self.updates.append(kwargs)
        return {}


class BootstrapApiTests(unittest.TestCase):
    def setUp(self):
        self.previous_table = app.table

    def tearDown(self):
        app.table = self.previous_table

    def test_first_bootstrap_is_atomic_and_free(self):
        fake = FakeTable()
        app.table = fake
        result = app.handler(event("/v1/bootstrap"), None)
        body = json.loads(result["body"])

        self.assertEqual(201, result["statusCode"])
        self.assertTrue(body["accountId"].startswith("account-"))
        self.assertTrue(body["tenantId"].startswith("tenant-"))
        self.assertEqual(1, len(fake.client.transactions))
        self.assertEqual(3, len(fake.client.transactions[0]))
        entitlement = fake.client.transactions[0][2]["Put"]["Item"]
        self.assertEqual({"S": "FREE"}, entitlement["plan"])
        self.assertEqual({"L": []}, entitlement["features"])

    def test_bootstrap_is_idempotent(self):
        fake = FakeTable({
            ("USER#cognito-subject", "ACCOUNT"): {
                "accountId": "account-existing", "tenantId": "tenant-existing",
            },
        })
        app.table = fake
        result = app.handler(event("/v1/bootstrap"), None)

        self.assertEqual(200, result["statusCode"])
        self.assertEqual({"accountId": "account-existing", "tenantId": "tenant-existing"}, json.loads(result["body"]))
        self.assertEqual([], fake.client.transactions)

    def test_heartbeat_atomically_records_safety_incidents(self):
        fake = FakeTable({
            ("USER#cognito-subject", "ACCOUNT"): {
                "accountId": "account-existing", "tenantId": "tenant-existing",
            },
            ("DEVICE#server-12345678", "METADATA"): {
                "deviceId": "server-12345678", "greenhouseId": "greenhouse-12345678",
                "accountId": "account-existing", "tenantId": "tenant-existing",
                "role": "GREENHOUSE_SERVER", "enabled": True,
            },
        })
        app.table = fake
        result = app.handler(event(
            "/v1/devices/server-12345678/heartbeat",
            device_id="server-12345678",
            body={"sensorConnectionHealthy": False, "criticalTemperature": True},
        ), None)

        self.assertEqual(204, result["statusCode"])
        transaction = fake.client.transactions[0]
        self.assertEqual(3, len(transaction))
        alert_types = {item["Put"]["Item"]["type"]["S"] for item in transaction[1:]}
        self.assertEqual({"SENSOR_COMMUNICATION_FAILURE", "CRITICAL_TEMPERATURE"}, alert_types)

    def test_pending_alerts_are_account_scoped_and_hide_acknowledged(self):
        fake = FakeTable({
            ("USER#cognito-subject", "ACCOUNT"): {
                "accountId": "account-existing", "tenantId": "tenant-existing",
            },
            ("DEVICE#viewer-12345678", "METADATA"): {
                "deviceId": "viewer-12345678", "accountId": "account-existing",
                "tenantId": "tenant-existing", "role": "VIEWER_CLIENT", "enabled": True,
            },
        })
        fake.query_items = [
            {"id": "1700000000-aaaaaaaa", "tenantId": "tenant-existing", "type": "SERVER_OFFLINE", "priority": "HIGH", "message": "offline", "createdAt": 1700000000, "requiresAcknowledgement": True},
            {"id": "1700000001-bbbbbbbb", "tenantId": "tenant-existing", "type": "CRITICAL_TEMPERATURE", "priority": "CRITICAL", "message": "hot", "createdAt": 1700000001, "requiresAcknowledgement": True, "acknowledgedAt": 1700000010},
            {"id": "1700000002-cccccccc", "tenantId": "tenant-other", "type": "SERVER_OFFLINE", "priority": "HIGH", "message": "other", "createdAt": 1700000002, "requiresAcknowledgement": True},
        ]
        app.table = fake
        result = app.handler(event("/v1/alerts", method="GET", device_id="viewer-12345678"), None)
        body = json.loads(result["body"])

        self.assertEqual(200, result["statusCode"])
        self.assertEqual(["1700000000-aaaaaaaa"], [item["id"] for item in body["alerts"]])

    def test_acknowledgement_is_conditioned_on_account_and_tenant(self):
        fake = FakeTable({
            ("USER#cognito-subject", "ACCOUNT"): {
                "accountId": "account-existing", "tenantId": "tenant-existing",
            },
            ("DEVICE#viewer-12345678", "METADATA"): {
                "deviceId": "viewer-12345678", "accountId": "account-existing",
                "tenantId": "tenant-existing", "role": "VIEWER_CLIENT", "enabled": True,
            },
        })
        app.table = fake
        result = app.handler(event(
            "/v1/alerts/1700000000-aaaaaaaa/acknowledge",
            device_id="viewer-12345678",
        ), None)

        self.assertEqual(204, result["statusCode"])
        update = fake.updates[0]
        self.assertEqual({"pk": "ACCOUNT#account-existing", "sk": "ALERT#1700000000-aaaaaaaa"}, update["Key"])
        self.assertEqual("tenant-existing", update["ExpressionAttributeValues"][":tenant"])

    def test_server_registration_binds_device_and_greenhouse_to_account(self):
        fake = FakeTable({
            ("USER#cognito-subject", "ACCOUNT"): {
                "accountId": "account-existing", "tenantId": "tenant-existing",
            },
        })
        app.table = fake
        result = app.handler(event(
            "/v1/devices",
            device_id="server-12345678",
            body={
                "deviceId": "server-12345678",
                "greenhouseId": "greenhouse-12345678",
                "role": "GREENHOUSE_SERVER",
            },
        ), None)

        self.assertEqual(201, result["statusCode"])
        self.assertEqual(1, len(fake.client.transactions))
        self.assertEqual(3, len(fake.client.transactions[0]))
        device = fake.client.transactions[0][0]["Put"]["Item"]
        greenhouse = fake.client.transactions[0][1]["Put"]["Item"]
        self.assertEqual({"S": "tenant-existing"}, device["tenantId"])
        self.assertEqual({"S": "account-existing"}, greenhouse["accountId"])
        self.assertEqual({"S": "greenhouse-12345678"}, device["greenhouseId"])

    def test_device_inventory_and_revocation_are_account_scoped(self):
        fake = FakeTable({
            ("USER#cognito-subject", "ACCOUNT"): {
                "accountId": "account-existing", "tenantId": "tenant-existing",
            },
            ("DEVICE#viewer-12345678", "METADATA"): {
                "deviceId": "viewer-12345678", "accountId": "account-existing",
                "tenantId": "tenant-existing", "role": "VIEWER_CLIENT", "enabled": True,
                "greenhouseId": "greenhouse-12345678",
            },
            ("DEVICE#viewer-87654321", "METADATA"): {
                "deviceId": "viewer-87654321", "accountId": "account-existing",
                "tenantId": "tenant-existing", "role": "VIEWER_CLIENT", "enabled": True,
                "greenhouseId": "greenhouse-12345678",
            },
        })
        fake.query_items = [{
            "deviceId": "viewer-87654321", "accountId": "account-existing",
            "tenantId": "tenant-existing", "role": "VIEWER_CLIENT", "enabled": True,
            "greenhouseId": "greenhouse-12345678", "createdAt": 100,
        }]
        app.table = fake

        listing = app.handler(event("/v1/devices", method="GET", device_id="viewer-12345678"), None)
        changed = app.handler(event(
            "/v1/devices/viewer-87654321/state", device_id="viewer-12345678",
            body={"enabled": False},
        ), None)

        self.assertEqual(200, listing["statusCode"])
        self.assertEqual(["viewer-87654321"], [item["id"] for item in json.loads(listing["body"])["devices"]])
        self.assertEqual(200, changed["statusCode"])
        self.assertFalse(json.loads(changed["body"])["enabled"])
        transaction = fake.client.transactions[0]
        self.assertEqual(2, len(transaction))
        self.assertEqual({"S": "DEVICE#viewer-87654321"}, transaction[0]["Update"]["Key"]["pk"])
        self.assertEqual({"S": "ACCOUNT#account-existing"}, transaction[1]["Update"]["Key"]["pk"])

    def test_server_registration_rejects_header_body_mismatch(self):
        fake = FakeTable({
            ("USER#cognito-subject", "ACCOUNT"): {
                "accountId": "account-existing", "tenantId": "tenant-existing",
            },
        })
        app.table = fake
        result = app.handler(event(
            "/v1/devices",
            device_id="server-12345678",
            body={
                "deviceId": "server-87654321",
                "greenhouseId": "greenhouse-12345678",
                "role": "GREENHOUSE_SERVER",
            },
        ), None)

        self.assertEqual(403, result["statusCode"])
        self.assertEqual("device_mismatch", json.loads(result["body"])["error"])
        self.assertEqual([], fake.client.transactions)

    def test_google_play_purchase_is_verified_server_side_and_token_is_not_returned(self):
        fake = FakeTable({
            ("USER#cognito-subject", "ACCOUNT"): {
                "accountId": "account-existing", "tenantId": "tenant-existing",
            },
            ("ACCOUNT#account-existing", "METADATA"): {
                "accountId": "account-existing", "tenantId": "tenant-existing",
                "state": "ACTIVE", "createdAt": 100,
            },
            ("DEVICE#viewer-12345678", "METADATA"): {
                "deviceId": "viewer-12345678", "accountId": "account-existing",
                "tenantId": "tenant-existing", "role": "VIEWER_CLIENT", "enabled": True,
            },
        })
        app.table = fake
        with patch.object(app, "verify_google_play_purchase", return_value={"active": True, "expiresAt": 2_000_000_000}):
            result = app.handler(event(
                "/v1/subscriptions/google-play/verify",
                device_id="viewer-12345678",
                body={"productId": "premium_monthly", "purchaseToken": "purchase-token-123456789"},
            ), None)
        body = json.loads(result["body"])

        self.assertEqual(200, result["statusCode"])
        self.assertEqual("GOOGLE_PLAY_VERIFIED", body["source"])
        self.assertNotIn("purchaseToken", body)
        values = fake.updates[0]["ExpressionAttributeValues"]
        self.assertEqual("purchase-token-123456789", values[":token"])
        self.assertEqual(64, len(values[":tokenHash"]))


if __name__ == "__main__":
    unittest.main()
