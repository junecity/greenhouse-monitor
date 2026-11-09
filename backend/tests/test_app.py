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


def event(path, method="POST", subject="cognito-subject", device_id=None, body=None, stage=None):
    headers = {}
    if device_id:
        headers["x-greenhouse-device-id"] = device_id
    return {
        "rawPath": path,
        "headers": headers,
        "body": json.dumps(body) if body is not None else None,
        "requestContext": {
            "stage": stage,
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
        self.puts = []

    def get_item(self, Key, **_kwargs):
        value = self.items.get((Key["pk"], Key["sk"]))
        return {"Item": value} if value else {}

    def query(self, **_kwargs):
        return {"Items": self.query_items}

    def update_item(self, **kwargs):
        self.updates.append(kwargs)
        return {}

    def put_item(self, **kwargs):
        self.puts.append(kwargs)
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
        self.assertEqual("FREE", entitlement["plan"])
        self.assertEqual([], entitlement["features"])

    def test_live_stage_prefix_is_removed_before_bootstrap_routing(self):
        fake = FakeTable()
        app.table = fake

        result = app.handler(event("/dev/v1/bootstrap", stage="dev"), None)

        self.assertEqual(201, result["statusCode"])
        self.assertEqual(1, len(fake.client.transactions))

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
        alert_types = {item["Put"]["Item"]["type"] for item in transaction[1:]}
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
        self.assertEqual("tenant-existing", device["tenantId"])
        self.assertEqual("account-existing", greenhouse["accountId"])
        self.assertEqual("greenhouse-12345678", device["greenhouseId"])

    def test_authenticated_phone_is_automatically_registered_as_client(self):
        fake = FakeTable({
            ("USER#cognito-subject", "ACCOUNT"): {"accountId": "account-existing", "tenantId": "tenant-existing"},
        })
        app.table = fake

        result = app.handler(event(
            "/v1/devices", device_id="client-12345678",
            body={"deviceId": "client-12345678", "role": "VIEWER_CLIENT"},
        ), None)

        self.assertEqual(201, result["statusCode"])
        transaction = fake.client.transactions[0]
        self.assertEqual("VIEWER_CLIENT", transaction[0]["Put"]["Item"]["role"])
        self.assertEqual("PRIMARY_CLIENT", transaction[0]["Put"]["Item"]["clientRole"])
        self.assertIn("primaryClientDeviceId=if_not_exists", transaction[2]["Update"]["UpdateExpression"])

    def test_server_invitation_is_created_by_an_enabled_client(self):
        fake = FakeTable({
            ("USER#cognito-subject", "ACCOUNT"): {"accountId": "account-existing", "tenantId": "tenant-existing"},
            ("DEVICE#client-12345678", "METADATA"): {
                "deviceId": "client-12345678", "accountId": "account-existing", "tenantId": "tenant-existing",
                "role": "VIEWER_CLIENT", "enabled": True,
            },
        })
        app.table = fake

        result = app.handler(event(
            "/v1/server-pairing/invitations", device_id="client-12345678",
            body={"greenhouseId": "greenhouse-12345678", "greenhouseName": "North House"},
        ), None)
        body = json.loads(result["body"])

        self.assertEqual(201, result["statusCode"])
        self.assertEqual("greenhouse-12345678", body["greenhouseId"])
        self.assertGreaterEqual(len(body["displayCode"]), 24)
        self.assertEqual("SERVER_INVITATION", fake.puts[0]["Item"]["sk"])
        self.assertEqual(app.SERVER_PAIRING_SECONDS, fake.puts[0]["Item"]["expiresAt"] - fake.puts[0]["Item"]["createdAt"])

    def test_disconnect_preserves_credentials_while_revoke_invalidates_them(self):
        fake = FakeTable({
            ("USER#cognito-subject", "ACCOUNT"): {"accountId": "account-existing", "tenantId": "tenant-existing"},
            ("DEVICE#client-12345678", "METADATA"): {
                "deviceId": "client-12345678", "accountId": "account-existing", "tenantId": "tenant-existing",
                "role": "VIEWER_CLIENT", "enabled": True,
            },
            ("DEVICE#server-12345678", "METADATA"): {
                "deviceId": "server-12345678", "accountId": "account-existing", "tenantId": "tenant-existing",
                "role": "GREENHOUSE_SERVER", "greenhouseId": "greenhouse-12345678", "enabled": True,
            },
        })
        app.table = fake
        disconnected = app.handler(event(
            "/v1/devices/server-12345678/connection", device_id="client-12345678", body={"connected": False},
        ), None)
        revoked = app.handler(event(
            "/v1/devices/server-12345678/state", device_id="client-12345678", body={"enabled": False},
        ), None)

        self.assertEqual(200, disconnected["statusCode"])
        self.assertTrue(json.loads(disconnected["body"])["communicationSuspended"])
        self.assertNotIn("enabled=:enabled", fake.client.transactions[0][0]["Update"]["UpdateExpression"])
        self.assertEqual(200, revoked["statusCode"])
        self.assertFalse(json.loads(revoked["body"])["enabled"])

    def test_revoked_server_requires_fresh_pairing_instead_of_reenable(self):
        fake = FakeTable({
            ("USER#cognito-subject", "ACCOUNT"): {"accountId": "account-existing", "tenantId": "tenant-existing"},
            ("DEVICE#client-12345678", "METADATA"): {"deviceId": "client-12345678", "accountId": "account-existing", "tenantId": "tenant-existing", "role": "VIEWER_CLIENT", "enabled": True},
            ("DEVICE#server-12345678", "METADATA"): {"deviceId": "server-12345678", "accountId": "account-existing", "tenantId": "tenant-existing", "role": "GREENHOUSE_SERVER", "enabled": False},
        })
        app.table = fake
        result = app.handler(event(
            "/v1/devices/server-12345678/state", device_id="client-12345678", body={"enabled": True},
        ), None)

        self.assertEqual(403, result["statusCode"])
        self.assertEqual("fresh_pairing_required", json.loads(result["body"])["error"])

    def test_video_override_controls_actual_entitlement_without_changing_subscription(self):
        paid = {"accountId": "account-existing", "plan": "PREMIUM", "state": "ACTIVE", "source": "GOOGLE_PLAY_VERIFIED", "features": ["REMOTE_LIVE_VIDEO"]}
        disabled = app.effective_video_entitlement({**paid, "videoAccessOverride": "DISABLED"}, now=100)
        complimentary = app.effective_video_entitlement({"accountId": "account-existing", "plan": "FREE", "state": "ACTIVE", "source": "NONE", "features": [], "videoAccessOverride": "COMPLIMENTARY"}, now=100)

        self.assertEqual("PREMIUM", disabled["plan"])
        self.assertNotIn("REMOTE_LIVE_VIDEO", disabled["features"])
        self.assertEqual("COMPLIMENTARY_BACKEND_GRANT", complimentary["source"])
        self.assertIn("REMOTE_LIVE_VIDEO", complimentary["features"])

    def test_expired_server_qr_cannot_create_pairing_request(self):
        code = "abcdefghijklmnopqrstuvwxyz123456"
        fake = FakeTable({
            (f"PAIRING#{app.pairing_code_hash(code)}", "SERVER_INVITATION"): {
                "pk": f"PAIRING#{app.pairing_code_hash(code)}", "sk": "SERVER_INVITATION",
                "accountId": "account-existing", "tenantId": "tenant-existing", "greenhouseId": "greenhouse-12345678",
                "expiresAt": 1,
            },
        })
        app.table = fake

        result = app.handler(event(
            "/v1/public-server-pairing/requests", subject=None,
            body={"pairingCode": code, "serverDeviceId": "server-12345678", "deviceSecret": "s" * 48},
        ), None)

        self.assertEqual(403, result["statusCode"])
        self.assertEqual("expired_used_or_invalid_pairing_code", json.loads(result["body"])["error"])

    def test_valid_server_qr_creates_request_that_requires_client_approval(self):
        code = "abcdefghijklmnopqrstuvwxyz123456"
        invitation = {
            "pk": f"PAIRING#{app.pairing_code_hash(code)}", "sk": "SERVER_INVITATION",
            "accountId": "account-existing", "tenantId": "tenant-existing", "greenhouseId": "greenhouse-12345678",
            "greenhouseName": "North House", "expiresAt": 4_000_000_000,
        }
        fake = FakeTable({(invitation["pk"], invitation["sk"]): invitation})
        app.table = fake

        result = app.handler(event(
            "/v1/public-server-pairing/requests", subject=None,
            body={"pairingCode": code, "serverDeviceId": "server-12345678", "deviceSecret": "s" * 48},
        ), None)
        body = json.loads(result["body"])

        self.assertEqual(202, result["statusCode"])
        self.assertEqual("PENDING_APPROVAL", body["state"])
        self.assertEqual(3, len(fake.client.transactions[0]))
        request = fake.client.transactions[0][1]["Put"]["Item"]
        self.assertNotIn("s" * 48, request.values())
        self.assertEqual(64, len(request["deviceSecretHash"]))

    def test_client_approval_replaces_server_without_replacing_greenhouse_record(self):
        request_id = "request-12345678"
        request = {
            "pk": f"SERVER_PAIRING#{request_id}", "sk": "METADATA", "requestId": request_id,
            "accountId": "account-existing", "tenantId": "tenant-existing", "greenhouseId": "greenhouse-12345678",
            "greenhouseName": "North House", "serverDeviceId": "server-new12345", "replaceServerDeviceId": "server-old12345",
            "deviceSecretHash": "a" * 64, "state": "PENDING_APPROVAL", "expiresAt": 4_000_000_000,
        }
        fake = FakeTable({
            ("USER#cognito-subject", "ACCOUNT"): {"accountId": "account-existing", "tenantId": "tenant-existing"},
            ("DEVICE#client-12345678", "METADATA"): {
                "deviceId": "client-12345678", "accountId": "account-existing", "tenantId": "tenant-existing",
                "role": "VIEWER_CLIENT", "enabled": True,
            },
            (request["pk"], request["sk"]): request,
            ("GREENHOUSE#greenhouse-12345678", "METADATA"): {
                "pk": "GREENHOUSE#greenhouse-12345678", "sk": "METADATA", "greenhouseId": "greenhouse-12345678",
                "accountId": "account-existing", "tenantId": "tenant-existing", "serverDeviceId": "server-old12345",
                "createdAt": 100, "layoutVersion": 7, "historyPartition": "greenhouse-12345678",
            },
        })
        app.table = fake

        result = app.handler(event(
            f"/v1/server-pairing/requests/{request_id}/approve", device_id="client-12345678",
        ), None)

        self.assertEqual(200, result["statusCode"])
        transaction = fake.client.transactions[0]
        greenhouse = transaction[1]["Put"]["Item"]
        self.assertEqual(7, greenhouse["layoutVersion"])
        self.assertEqual("greenhouse-12345678", greenhouse["historyPartition"])
        self.assertEqual("server-new12345", greenhouse["serverDeviceId"])
        disabled_keys = [item["Update"]["Key"] for item in transaction if "Update" in item and "replacedAt" in item["Update"].get("UpdateExpression", "")]
        self.assertEqual(2, len(disabled_keys))

    def test_server_device_secret_authentication_restores_role(self):
        secret = "secure-device-secret-12345678901234567890"
        fake = FakeTable({
            ("DEVICE#server-12345678", "METADATA"): {
                "deviceId": "server-12345678", "accountId": "account-existing", "tenantId": "tenant-existing",
                "role": "GREENHOUSE_SERVER", "greenhouseId": "greenhouse-12345678", "enabled": True,
                "deviceSecretHash": app.hashlib.sha256(secret.encode()).hexdigest(),
            },
            ("ACCOUNT#account-existing", "METADATA"): {
                "accountId": "account-existing", "tenantId": "tenant-existing", "state": "ACTIVE",
            },
        })
        app.table = fake
        request = event("/v1/device/devices/current", method="GET", subject=None, device_id="server-12345678")
        request["headers"]["x-greenhouse-device-secret"] = secret

        result = app.handler(request, None)

        self.assertEqual(200, result["statusCode"])
        self.assertEqual("GREENHOUSE_SERVER", json.loads(result["body"])["role"])

    def test_authorized_server_uploads_replacement_backup_without_exposing_secret(self):
        secret = "secure-device-secret-12345678901234567890"
        fake = FakeTable({
            ("DEVICE#server-12345678", "METADATA"): {
                "deviceId": "server-12345678", "accountId": "account-existing", "tenantId": "tenant-existing",
                "role": "GREENHOUSE_SERVER", "greenhouseId": "greenhouse-12345678", "enabled": True,
                "deviceSecretHash": app.hashlib.sha256(secret.encode()).hexdigest(),
            },
            ("ACCOUNT#account-existing", "METADATA"): {"accountId": "account-existing", "tenantId": "tenant-existing", "state": "ACTIVE"},
            ("GREENHOUSE#greenhouse-12345678", "METADATA"): {
                "greenhouseId": "greenhouse-12345678", "serverDeviceId": "server-12345678",
                "accountId": "account-existing", "tenantId": "tenant-existing",
            },
        })
        app.table = fake
        request = event(
            "/v1/device/greenhouses/greenhouse-12345678/backup", device_id="server-12345678", subject=None,
            body={"backup": {"schema": 2, "preferences": {"active": "main"}, "zones": {}, "sensors": [], "history": []}},
        )
        request["headers"]["x-greenhouse-device-secret"] = secret

        result = app.handler(request, None)

        self.assertEqual(204, result["statusCode"])
        backup = fake.puts[0]["Item"]
        self.assertEqual("BACKUP", backup["sk"])
        self.assertNotIn(secret, backup["backupJson"])

    def test_authorized_replacement_server_reads_existing_backup(self):
        secret = "secure-device-secret-12345678901234567890"
        backup = {"schema": 2, "preferences": {"active": "main"}, "zones": {}, "sensors": [], "history": []}
        fake = FakeTable({
            ("DEVICE#server-12345678", "METADATA"): {
                "deviceId": "server-12345678", "accountId": "account-existing", "tenantId": "tenant-existing",
                "role": "GREENHOUSE_SERVER", "greenhouseId": "greenhouse-12345678", "enabled": True,
                "deviceSecretHash": app.hashlib.sha256(secret.encode()).hexdigest(),
            },
            ("ACCOUNT#account-existing", "METADATA"): {"accountId": "account-existing", "tenantId": "tenant-existing", "state": "ACTIVE"},
            ("GREENHOUSE#greenhouse-12345678", "METADATA"): {
                "greenhouseId": "greenhouse-12345678", "serverDeviceId": "server-12345678",
                "accountId": "account-existing", "tenantId": "tenant-existing",
            },
            ("GREENHOUSE#greenhouse-12345678", "BACKUP"): {
                "accountId": "account-existing", "tenantId": "tenant-existing",
                "backupJson": json.dumps(backup), "updatedAt": 100,
            },
        })
        app.table = fake
        request = event(
            "/v1/device/greenhouses/greenhouse-12345678/backup", method="GET",
            device_id="server-12345678", subject=None,
        )
        request["headers"]["x-greenhouse-device-secret"] = secret

        result = app.handler(request, None)

        self.assertEqual(200, result["statusCode"])
        self.assertEqual(backup, json.loads(result["body"])["backup"])

    def test_authorized_client_reads_server_backup_for_its_greenhouse(self):
        backup = {"schema": 2, "preferences": {"active": "main"}, "zones": {}, "sensors": [], "history": []}
        fake = FakeTable({
            ("USER#cognito-subject", "ACCOUNT"): {"accountId": "account-existing", "tenantId": "tenant-existing"},
            ("DEVICE#client-12345678", "METADATA"): {
                "deviceId": "client-12345678", "accountId": "account-existing", "tenantId": "tenant-existing",
                "role": "VIEWER_CLIENT", "enabled": True,
            },
            ("GREENHOUSE#greenhouse-12345678", "METADATA"): {
                "greenhouseId": "greenhouse-12345678", "serverDeviceId": "server-12345678",
                "accountId": "account-existing", "tenantId": "tenant-existing",
            },
            ("GREENHOUSE#greenhouse-12345678", "BACKUP"): {
                "accountId": "account-existing", "tenantId": "tenant-existing",
                "backupJson": json.dumps(backup), "updatedAt": 100,
            },
        })
        app.table = fake

        result = app.handler(event(
            "/v1/greenhouses/greenhouse-12345678/backup", method="GET", device_id="client-12345678",
        ), None)

        self.assertEqual(200, result["statusCode"])
        self.assertEqual(backup, json.loads(result["body"])["backup"])

    def test_client_cannot_overwrite_authoritative_server_backup(self):
        fake = FakeTable({
            ("USER#cognito-subject", "ACCOUNT"): {"accountId": "account-existing", "tenantId": "tenant-existing"},
            ("DEVICE#client-12345678", "METADATA"): {
                "deviceId": "client-12345678", "accountId": "account-existing", "tenantId": "tenant-existing",
                "role": "VIEWER_CLIENT", "enabled": True,
            },
            ("GREENHOUSE#greenhouse-12345678", "METADATA"): {
                "greenhouseId": "greenhouse-12345678", "serverDeviceId": "server-12345678",
                "accountId": "account-existing", "tenantId": "tenant-existing",
            },
        })
        app.table = fake

        result = app.handler(event(
            "/v1/greenhouses/greenhouse-12345678/backup", method="POST", device_id="client-12345678",
            body={"backup": {"schema": 2, "preferences": {}, "zones": {}, "sensors": [], "history": []}},
        ), None)

        self.assertEqual(403, result["statusCode"])
        self.assertEqual("server_device", json.loads(result["body"])["error"])

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
        self.assertEqual("DEVICE#viewer-87654321", transaction[0]["Update"]["Key"]["pk"])
        self.assertEqual("ACCOUNT#account-existing", transaction[1]["Update"]["Key"]["pk"])

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
