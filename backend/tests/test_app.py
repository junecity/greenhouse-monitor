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

    def get_item(self, Key, **_kwargs):
        value = self.items.get((Key["pk"], Key["sk"]))
        return {"Item": value} if value else {}


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
