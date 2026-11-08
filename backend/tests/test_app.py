import json
import os
import pathlib
import sys
import unittest
from types import SimpleNamespace

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
        self.assertEqual(2, len(fake.client.transactions[0]))
        device = fake.client.transactions[0][0]["Put"]["Item"]
        greenhouse = fake.client.transactions[0][1]["Put"]["Item"]
        self.assertEqual({"S": "tenant-existing"}, device["tenantId"])
        self.assertEqual({"S": "account-existing"}, greenhouse["accountId"])
        self.assertEqual({"S": "greenhouse-12345678"}, device["greenhouseId"])

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


if __name__ == "__main__":
    unittest.main()
