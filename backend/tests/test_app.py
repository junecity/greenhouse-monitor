import hashlib
import io
import json
import os
import pathlib
import sys
import time
import unittest
from types import SimpleNamespace
from unittest.mock import patch

from botocore.exceptions import ClientError

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
        self.deletes = []

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

    def delete_item(self, **kwargs):
        self.deletes.append(kwargs)
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
            ("ACCOUNT#account-existing", "METADATA"): {
                "accountId": "account-existing", "tenantId": "tenant-existing",
                "cognitoSubject": "cognito-subject",
            },
        })
        app.table = fake
        result = app.handler(event("/v1/bootstrap"), None)

        self.assertEqual(200, result["statusCode"])
        self.assertEqual({"accountId": "account-existing", "tenantId": "tenant-existing"}, json.loads(result["body"]))
        self.assertEqual([], fake.client.transactions)

    def test_bootstrap_rejects_stale_user_mapping_for_another_identity(self):
        fake = FakeTable({
            ("USER#cognito-subject", "ACCOUNT"): {
                "accountId": "account-existing", "tenantId": "tenant-existing",
            },
            ("ACCOUNT#account-existing", "METADATA"): {
                "accountId": "account-existing", "tenantId": "tenant-existing",
                "cognitoSubject": "different-subject",
            },
        })
        app.table = fake

        result = app.handler(event("/v1/bootstrap"), None)

        self.assertEqual(403, result["statusCode"])
        self.assertEqual("account_identity_mismatch", json.loads(result["body"])["error"])

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
        server = {"accountId": "account-existing", "tenantId": "tenant-existing", "greenhouseId": "house",
                  "enabled": True, "role": "GREENHOUSE_SERVER", "offlineAlertedAt": 1700000000}
        fake.items[("DEVICE#source", "METADATA")] = server
        fake.query_items = [
            {"id": "1700000000-aaaaaaaa", "deviceId": "source", "greenhouseId": "house", "tenantId": "tenant-existing", "type": "SERVER_OFFLINE", "priority": "HIGH", "message": "offline", "createdAt": 1700000000, "requiresAcknowledgement": True},
            {"id": "1700000001-bbbbbbbb", "tenantId": "tenant-existing", "type": "CRITICAL_TEMPERATURE", "priority": "CRITICAL", "message": "hot", "createdAt": 1700000001, "requiresAcknowledgement": True, "acknowledgedAt": 1700000010},
            {"id": "1700000002-cccccccc", "tenantId": "tenant-other", "type": "SERVER_OFFLINE", "priority": "HIGH", "message": "other", "createdAt": 1700000002, "requiresAcknowledgement": True},
        ]
        app.table = fake
        result = app.handler(event("/v1/alerts", method="GET", device_id="viewer-12345678"), None)
        body = json.loads(result["body"])

        self.assertEqual(200, result["statusCode"])
        self.assertEqual(["1700000000-aaaaaaaa"], [item["id"] for item in body["alerts"]])

        old = fake.query_items[0]
        fake.query_items.append(dict(old, createdAt=1699999990, id="prior-outage"))
        fresh = app.handler(event("/v1/alerts", method="GET", device_id="viewer-12345678"), None)
        self.assertEqual([old["id"]], [a["id"] for a in json.loads(fresh["body"])["alerts"]])
        for change in [{"offlineAlertedAt": None}, {"enabled": False}, {"communicationSuspended": True},
                       {"greenhouseId": "reassigned"}, {"tenantId": "different"}]:
            with self.subTest(change=change):
                fake.items[("DEVICE#source", "METADATA")] = dict(server, **change)
                result = app.handler(event("/v1/alerts", method="GET", device_id="viewer-12345678"), None)
                self.assertEqual([], json.loads(result["body"])["alerts"])

    def test_temperature_incident_stays_stable_until_a_measured_recovery(self):
        server = {"greenhouseId": "house", "accountId": "a", "tenantId": "t",
                  "role": "GREENHOUSE_SERVER", "enabled": True, "deviceSecretHash": "test-only"}
        fake = FakeTable({("DEVICE#server", "METADATA"): server})
        app.table = fake
        who = app.Principal("a", "t", "server")
        breach = {"direction": "LOW", "temperatureF": 38.5, "limitF": 40,
                  "sensorName": "North bench", "measuredAt": int(time.time())}

        def send(critical, evaluated=True, **details):
            result = app.heartbeat({"body": json.dumps(dict(sensorConnectionHealthy=True,
                criticalTemperature=critical, temperatureEvaluated=evaluated, **details))}, who, "server")
            self.assertEqual(204, result["statusCode"])
            return fake.client.transactions[-1]

        initial, put, delivery = send(True, temperatureBreach=breach)
        incident = put["Put"]["Item"]
        self.assertEqual(incident["id"], delivery["Put"]["Item"]["id"])
        self.assertEqual("North bench measured 38.5°F, below the low limit of 40°F.", incident["message"])
        self.assertEqual(incident["id"], initial["Update"]["ExpressionAttributeValues"][":incidentId"])
        server.update(criticalTemperature=True, criticalAlertedAt=incident["createdAt"],
                      criticalIncidentId=incident["id"], criticalAlertMessage=incident["message"])
        for body in [dict(critical=True, temperatureBreach=breach), dict(critical=False, evaluated=False),
                     dict(critical=True, temperatureBreach=dict(breach, measuredAt=0))]:
            with self.subTest(body=body):
                repeated, = send(**body)
                self.assertNotIn("criticalAlertedAt", repeated["Update"]["UpdateExpression"])
                self.assertNotIn("criticalIncidentId", repeated["Update"]["UpdateExpression"])
                expected_evaluated = body.get("temperatureBreach", {}).get("measuredAt", 0) > 0
                self.assertEqual(expected_evaluated, repeated["Update"]["ExpressionAttributeValues"][":evaluated"])
        recovered, ended = send(False)
        self.assertIn("resolvedAt", ended["Update"]["UpdateExpression"])
        self.assertIn("criticalIncidentId=:previousIncident", recovered["Update"]["ConditionExpression"])
        self.assertIn("criticalIncidentId", recovered["Update"]["UpdateExpression"].split(" REMOVE ")[1])
        server.pop("criticalIncidentId")
        server.pop("criticalAlertedAt")
        _, next_put, _ = send(True, temperatureBreach=breach)
        self.assertNotEqual(incident["id"], next_put["Put"]["Item"]["id"])

    def test_pending_temperature_requires_current_owned_incident_even_for_legacy_clients(self):
        who = app.Principal("a", "t", "viewer")
        server = {"tenantId": "t", "accountId": "a", "greenhouseId": "house", "enabled": True,
                  "role": "GREENHOUSE_SERVER", "criticalTemperature": True, "criticalAlertedAt": 30}
        newer = app.new_alert(account_id="a", tenant_id="t", greenhouse_id="house", device_id="server",
                              alert_type="CRITICAL_TEMPERATURE", alert_id="newer", created_at=20)
        older = dict(newer, id="older", createdAt=10)
        app.table = FakeTable({("DEVICE#server", "METADATA"): server,
                               ("DEVICE#viewer", "METADATA"): {"tenantId": "t", "accountId": "a", "enabled": True}})
        app.table.query_items = [newer, older]

        def pending():
            payload = json.loads(app.pending_alerts(who)["body"])
            self.assertTrue(payload["criticalIncidentsVerified"])
            self.assertTrue(payload["complete"])
            return payload["alerts"]

        self.assertEqual(["newer"], [row["id"] for row in pending()])
        newer["acknowledgedAt"] = 25
        self.assertEqual([], pending())
        del newer["acknowledgedAt"]
        server.update(criticalIncidentId="newer", criticalAlertMessage="North bench measured 38°F, below the low limit of 40°F.")
        self.assertEqual(server["criticalAlertMessage"], pending()[0]["message"])
        server["temperatureEvaluated"] = False
        self.assertIn("Waiting for a fresh reading", pending()[0]["message"])
        for change in [{"criticalTemperature": False}, {"criticalIncidentId": "another-episode"},
                       {"greenhouseId": "other-house"}, {"accountId": "other-account"}, {"tenantId": "other-tenant"},
                       {"enabled": False}, {"communicationSuspended": True}]:
            with self.subTest(change=change):
                app.table.items[("DEVICE#server", "METADATA")] = dict(server, **change)
                self.assertEqual([], pending())

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
        update = fake.client.transactions[0][0]["Update"]
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

    @patch("app.connection_config", return_value={
        "signalingWebSocketUrl": "wss://signed.example",
        "iceServers": [{"urls": ["stun:stun.example:443"]}],
    })
    @patch("app.ensure_channel", return_value="arn:channel")
    def test_new_stream_request_supersedes_abandoned_session(self, _ensure, _connection):
        now = 2_000_000_000
        old_session = {
            "pk": "STREAM_SESSION#old-session", "sk": "METADATA",
            "tenantId": "tenant-existing", "accountId": "account-existing",
            "greenhouseId": "greenhouse-12345678", "serverDeviceId": "server-12345678",
            "viewerDeviceId": "viewer-12345678", "state": "REQUESTED",
            "createdAt": now - 30, "expiresAt": now + 120,
        }
        fake = FakeTable({
            ("DEVICE#viewer-12345678", "METADATA"): {
                "deviceId": "viewer-12345678", "tenantId": "tenant-existing",
                "accountId": "account-existing", "role": "VIEWER_CLIENT", "enabled": True,
            },
            ("DEVICE#server-12345678", "METADATA"): {
                "deviceId": "server-12345678", "tenantId": "tenant-existing",
                "accountId": "account-existing", "role": "GREENHOUSE_SERVER", "enabled": True,
                "greenhouseId": "greenhouse-12345678",
            },
            ("GREENHOUSE#greenhouse-12345678", "METADATA"): {
                "greenhouseId": "greenhouse-12345678", "tenantId": "tenant-existing",
                "accountId": "account-existing", "serverDeviceId": "server-12345678", "enabled": True,
            },
            ("ACCOUNT#account-existing", "ENTITLEMENT"): {
                "accountId": "account-existing", "tenantId": "tenant-existing",
                "videoAccessOverride": "COMPLIMENTARY", "features": [],
            },
            ("STREAM_SESSION#old-session", "METADATA"): old_session,
        })
        fake.query_items = [{
            "pk": "DEVICE#server-12345678", "sk": "STREAM_SESSION#old-session",
            "sessionId": "old-session", "expiresAt": now + 120,
        }]
        app.table = fake

        with patch("app.datetime") as clock:
            clock.now.return_value = __import__("datetime").datetime.fromtimestamp(now, __import__("datetime").timezone.utc)
            clock.side_effect = lambda *args, **kwargs: __import__("datetime").datetime(*args, **kwargs)
            result = app.create_stream_session(
                {"body": json.dumps({"requestId": "new-session"})},
                app.Principal("account-existing", "tenant-existing", "viewer-12345678"),
                "greenhouse-12345678",
            )

        self.assertEqual(201, result["statusCode"])
        self.assertEqual("old-session", fake.deletes[0]["Key"]["sk"].split("#", 1)[1])
        self.assertEqual("SUPERSEDED", fake.updates[0]["ExpressionAttributeValues"][":reason"])

    @patch("app.connection_config", return_value={
        "signalingWebSocketUrl": "wss://signed.example",
        "iceServers": [{"urls": ["stun:stun.example:443"]}],
    })
    def test_server_picks_newest_pending_stream_and_retires_older_one(self, _connection):
        now = 2_000_000_000
        old_session = {
            "pk": "STREAM_SESSION#old-session", "sk": "METADATA", "tenantId": "tenant-existing",
            "greenhouseId": "greenhouse-12345678", "serverDeviceId": "server-12345678",
            "viewerDeviceId": "viewer-old", "signalingChannelArn": "arn:channel", "state": "REQUESTED",
            "createdAt": now - 30, "expiresAt": now + 120, "authorizationExpiresAt": now + 60,
        }
        new_session = {
            "pk": "STREAM_SESSION#new-session", "sk": "METADATA", "tenantId": "tenant-existing",
            "greenhouseId": "greenhouse-12345678", "serverDeviceId": "server-12345678",
            "viewerDeviceId": "viewer-new", "signalingChannelArn": "arn:channel", "state": "REQUESTED",
            "createdAt": now - 5, "expiresAt": now + 120, "authorizationExpiresAt": now + 60,
        }
        fake = FakeTable({
            ("DEVICE#server-12345678", "METADATA"): {
                "deviceId": "server-12345678", "tenantId": "tenant-existing",
                "accountId": "account-existing", "role": "GREENHOUSE_SERVER", "enabled": True,
            },
            ("STREAM_SESSION#old-session", "METADATA"): old_session,
            ("STREAM_SESSION#new-session", "METADATA"): new_session,
        })
        fake.query_items = [
            {"pk": "DEVICE#server-12345678", "sk": "STREAM_SESSION#old-session", "sessionId": "old-session", "expiresAt": now + 120},
            {"pk": "DEVICE#server-12345678", "sk": "STREAM_SESSION#new-session", "sessionId": "new-session", "expiresAt": now + 120},
        ]
        app.table = fake

        with patch("app.datetime") as clock:
            clock.now.return_value = __import__("datetime").datetime.fromtimestamp(now, __import__("datetime").timezone.utc)
            result = app.pending_stream_session(
                app.Principal("account-existing", "tenant-existing", "server-12345678"),
                "server-12345678",
            )

        self.assertEqual(200, result["statusCode"])
        self.assertEqual("new-session", json.loads(result["body"])["id"])
        self.assertEqual("STREAM_SESSION#old-session", fake.deletes[0]["Key"]["sk"])

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

    def test_scoped_update_requests_delivered_only_to_matching_older_server(self):
        owner = {"accountId": "account-existing", "tenantId": "tenant-existing"}
        client = "client-12345678"
        servers = ["server-12345678", "server-23456789"]
        records = {("USER#cognito-subject", "ACCOUNT"): owner,
                   (f"DEVICE#{client}", "METADATA"): {**owner, "enabled": True, "role": "VIEWER_CLIENT"}}
        for server in servers:
            records[(f"DEVICE#{server}", "METADATA")] = {
                **owner, "role": "GREENHOUSE_SERVER", "enabled": True, "deviceId": server,
                "appVersion": "2.33.0", "appVersionCode": 73}
        fake = FakeTable(records)
        app.table = fake
        def request():
            return app.handler(event(f"/v1/devices/{servers[0]}/update-request", device_id=client,
                                     body={"versionCode": 74}), None)["statusCode"]
        def beat(server, build):
            return app.handler(event(f"/v1/devices/{server}/heartbeat", device_id=server,
                                     body={"appVersionCode": build, "sensorConnectionHealthy": True}), None)
        self.assertEqual(202, request())
        self.assertEqual(f"DEVICE#{servers[0]}", fake.updates[-1]["Key"]["pk"])
        server = records[(f"DEVICE#{servers[0]}", "METADATA")]
        values = fake.updates[-1]["ExpressionAttributeValues"]
        server.update(requestedUpdateCode=values[":target"], updateUntil=values[":expires"])
        self.assertEqual({"requestedUpdateCode": 74}, json.loads(beat(servers[0], 73)["body"]))
        self.assertEqual(204, beat(servers[1], 73)["statusCode"])
        self.assertEqual(204, beat(servers[0], 74)["statusCode"])
        fake.query_items = [records[(f"DEVICE#{s}", "METADATA")] for s in servers]
        rows = json.loads(app.handler(event("/v1/devices", method="GET", device_id=client), None)["body"])["devices"]
        self.assertEqual([74, None], [row["requestedUpdateCode"] for row in rows])
        self.assertEqual([73, 73], [row["appVersionCode"] for row in rows])
        server["updateUntil"] = 1
        self.assertEqual(204, beat(servers[0], 73)["statusCode"])
        server["communicationSuspended"] = True
        self.assertEqual(403, request())
        server.update(communicationSuspended=False, enabled=False)
        self.assertEqual(403, request())
        server.update(enabled=True, appVersionCode=72)
        self.assertEqual(409, request())
        server.update(accountId="different-account", appVersionCode=73)
        self.assertEqual(403, request())
        self.assertEqual(1, len(fake.updates))

    def test_managed_update_release_is_scoped_and_expires(self):
        owner = {"accountId": "account-existing", "tenantId": "tenant-existing"}
        server_id = "server-12345678"
        server = {**owner, "enabled": True, "role": "GREENHOUSE_SERVER", "deviceId": server_id,
                  "appVersionCode": 77, "requestedUpdateCode": 78, "updateUntil": 4102444800,
                  "managedUpdates": True}
        app.table = FakeTable({("USER#cognito-subject", "ACCOUNT"): owner,
                               (f"DEVICE#{server_id}", "METADATA"): server})
        def fetch(caller=server_id):
            return app.handler(event(f"/v1/devices/{server_id}/update-package", method="GET", device_id=caller), None)
        with patch.object(app, "server_release", return_value={"versionCode": 78}) as release:
            self.assertEqual(200, fetch()["statusCode"])
            release.assert_called_once_with(78)
            secret = "test-only-device-secret" * 2
            server["deviceSecretHash"] = hashlib.sha256(secret.encode()).hexdigest()
            app.table.items[("ACCOUNT#account-existing", "METADATA")] = {"state": "ACTIVE"}
            scoped = event(f"/v1/device/devices/{server_id}/update-package", method="GET", device_id=server_id)
            self.assertEqual(403, app.handler(scoped, None)["statusCode"])
            scoped["headers"]["x-greenhouse-device-secret"] = secret
            self.assertEqual(200, app.handler(scoped, None)["statusCode"])
            self.assertEqual(2, release.call_count)
            self.assertEqual(403, fetch("server-23456789")["statusCode"])
            for field, value in [("tenantId", "different"), ("accountId", "other"), ("enabled", False),
                                 ("managedUpdates", False), ("communicationSuspended", True), ("updateUntil", 1),
                                 ("appVersionCode", 78), ("role", "VIEWER_CLIENT")]:
                with self.subTest(field=field), patch.dict(server, {field: value}):
                    self.assertIn(fetch()["statusCode"], {403, 404})
            self.assertEqual(2, release.call_count)
            release.return_value = None
            self.assertEqual(409, fetch()["statusCode"])

    def test_release_signs_only_published_version_in_environment(self):
        with patch.dict(os.environ, {"SERVER_RELEASE_BUCKET": "private-artifacts", "ENVIRONMENT_NAME": "dev"}), \
                patch.object(app.boto3, "client") as make_client:
            s3 = make_client.return_value
            s3.head_object.return_value = {"Metadata": {"sha256": "c" * 64}, "ContentLength": 1024}
            s3.generate_presigned_url.return_value = "https://example.test/signed"
            self.assertEqual(1024, app.server_release(78)["bytes"])
            key = {"Bucket": "private-artifacts", "Key": "releases/dev/android/78.apk"}
            s3.head_object.assert_called_once_with(**key)
            s3.generate_presigned_url.assert_called_once_with("get_object", Params=key, ExpiresIn=300)
            s3.head_object.return_value["Metadata"].clear()
            self.assertIsNone(app.server_release(78))
            s3.generate_presigned_url.assert_called_once()

    def test_release_catalog_validates_the_immutable_package_without_exposing_a_download(self):
        manifest = {"versionCode": 80, "versionName": "2.37.0"}
        with patch.dict(os.environ, {"SERVER_RELEASE_BUCKET": "private-artifacts", "ENVIRONMENT_NAME": "dev"}), \
                patch.object(app.boto3, "client") as make_client:
            s3 = make_client.return_value
            s3.get_object.return_value = {"Body": io.BytesIO(json.dumps(manifest).encode())}
            s3.head_object.return_value = {"ContentLength": 1024, "Metadata": {"sha256": "c" * 64}}
            self.assertEqual(manifest, app.latest_server_release())
            s3.get_object.assert_called_once_with(Bucket="private-artifacts", Key="releases/dev/android/latest.json")
            s3.head_object.assert_called_once_with(Bucket="private-artifacts", Key="releases/dev/android/80.apk")
            s3.generate_presigned_url.assert_not_called()
            for bad in [b"{}", b"broken", b" " * 4097, b'{"versionCode":true,"versionName":"v1"}',
                        b'{"versionCode":80,"versionName":"untrusted/path"}']:
                with self.subTest(bad=bad[:32]):
                    s3.get_object.return_value = {"Body": io.BytesIO(bad)}
                    self.assertIsNone(app.latest_server_release())
            s3.head_object.assert_called_once()
            s3.get_object.return_value = {"Body": io.BytesIO(json.dumps(manifest).encode())}
            s3.head_object.side_effect = ClientError({"Error": {"Code": "404"}}, "HeadObject")
            self.assertIsNone(app.latest_server_release())
            s3.get_object.side_effect = ClientError({"Error": {"Code": "NoSuchKey"}}, "GetObject")
            self.assertIsNone(app.latest_server_release())

    def test_release_is_listed_for_each_authorized_server_and_an_s3_outage_keeps_devices_visible(self):
        owner = {"tenantId": "tenant-existing", "accountId": "account-existing"}
        client = {**owner, "role": "VIEWER_CLIENT", "deviceId": "client-12345678", "enabled": True}
        servers = [{**owner, "role": "GREENHOUSE_SERVER", "deviceId": device, "enabled": True, "appVersionCode": 78}
                   for device in ("server-12345678", "server-23456789")]
        app.table = FakeTable({("USER#cognito-subject", "ACCOUNT"): owner,
                               ("DEVICE#client-12345678", "METADATA"): client})
        app.table.query_items = [client, *servers, {**servers[0], "deviceId": "other-server", "tenantId": "other"}]
        def inventory():
            result = app.handler(event("/v1/devices", method="GET", device_id=client["deviceId"]), None)
            self.assertEqual(200, result["statusCode"])
            return json.loads(result["body"])["devices"]
        with patch.object(app, "_release_cache", None), patch.object(app, "latest_server_release") as latest:
            latest.return_value = {"versionCode": 80, "versionName": "2.37.0"}
            rows = inventory()
            self.assertEqual(3, len(rows))
            self.assertNotIn("latestReleaseCode", rows[0])
            self.assertEqual([(80, "2.37.0")] * 2, [(r["latestReleaseCode"], r["latestReleaseName"]) for r in rows[1:]])
            self.assertEqual(rows, inventory())
            latest.assert_called_once()
        with patch.object(app, "_release_cache", None), patch.object(app, "latest_server_release",
                side_effect=ClientError({"Error": {"Code": "SlowDown"}}, "GetObject")):
            rows = inventory()
            self.assertEqual([None, None], [r["latestReleaseCode"] for r in rows[1:]])

    def test_unattended_request_rechecks_published_release_and_current_enrollment(self):
        owner = {"tenantId": "tenant-existing", "accountId": "account-existing"}
        server_id = "server-12345678"
        client_id = "client-12345678"
        server = {**owner, "role": "GREENHOUSE_SERVER", "enabled": True, "deviceId": server_id,
                  "appVersionCode": 78, "managedUpdates": True}
        app.table = FakeTable({("USER#cognito-subject", "ACCOUNT"): owner,
                               (f"DEVICE#{server_id}", "METADATA"): server,
                               (f"DEVICE#{client_id}", "METADATA"): {**owner, "role": "VIEWER_CLIENT", "enabled": True}})
        def request(version):
            return app.handler(event(f"/v1/devices/{server_id}/update-request", device_id=client_id,
                                     body={"versionCode": version, "requireManaged": True}), None)
        with patch.object(app, "latest_server_release", return_value={"versionCode": 80, "versionName": "2.37.0"}):
            self.assertEqual(409, request(79)["statusCode"])
            with patch.dict(server, {"managedUpdates": False}):
                result = request(80)
                self.assertEqual(409, result["statusCode"])
                self.assertEqual("server_enrollment_required", json.loads(result["body"])["error"])
            self.assertFalse(app.table.updates)
            self.assertEqual(202, request(80)["statusCode"])
        write = app.table.updates[0]
        self.assertTrue(write["ExpressionAttributeValues"][":managed"])
        self.assertIn("managedUpdates=:yes", write["ConditionExpression"])
        server.update(requestedUpdateCode=80, updateRequiresManaged=True, updateUntil=4102444800)
        def heartbeat(managed, installed):
            return app.handler(event(f"/v1/devices/{server_id}/heartbeat", device_id=server_id,
                                     body={"appVersionCode": installed, "managedUpdates": managed}), None)
        self.assertEqual({"requestedUpdateCode": 80}, json.loads(heartbeat(True, 78)["body"]))
        self.assertEqual(204, heartbeat(False, 78)["statusCode"])
        self.assertEqual(204, heartbeat(True, 80)["statusCode"])

    def test_reminder_requires_unmanaged_server_and_can_never_authorize_a_download(self):
        owner = {"tenantId": "tenant-existing", "accountId": "account-existing"}
        server_id, client_id = "server-12345678", "client-12345678"
        server = {**owner, "role": "GREENHOUSE_SERVER", "enabled": True, "deviceId": server_id,
                  "appVersionCode": 79, "managedUpdates": False}
        app.table = FakeTable({("USER#cognito-subject", "ACCOUNT"): owner,
                               (f"DEVICE#{server_id}", "METADATA"): server,
                               (f"DEVICE#{client_id}", "METADATA"): {**owner, "role": "VIEWER_CLIENT", "enabled": True}})
        def request(**fields):
            return app.handler(event(f"/v1/devices/{server_id}/update-request", device_id=client_id,
                                     body={"versionCode": 80, **fields}), None)
        self.assertEqual(202, request(requireManaged=False)["statusCode"])
        update = app.table.updates[0]
        self.assertIs(False, update["ExpressionAttributeValues"][":managed"])
        self.assertIn("attribute_not_exists(managedUpdates) OR managedUpdates=:no", update["ConditionExpression"])
        server.update(managedUpdates=True, requestedUpdateCode=80, updateRequiresManaged=False, updateUntil=4102444800)
        mismatch = request(requireManaged=False)
        self.assertEqual(409, mismatch["statusCode"])
        self.assertEqual("server_enrollment_changed", json.loads(mismatch["body"])["error"])
        with patch.object(app, "server_release", return_value={"versionCode": 80}) as release:
            get_package = event(f"/v1/devices/{server_id}/update-package", method="GET", device_id=server_id)
            self.assertEqual(403, app.handler(get_package, None)["statusCode"])
            release.assert_not_called()
            self.assertEqual(202, request()["statusCode"])
            self.assertIs(True, app.table.updates[-1]["ExpressionAttributeValues"][":managed"])

    def test_enrollment_change_cancels_pending_command_including_legacy_and_racing_orders(self):
        owner = {"tenantId": "tenant-existing", "accountId": "account-existing"}
        server_id = "server-12345678"
        server = {**owner, "enabled": True, "role": "GREENHOUSE_SERVER", "deviceId": server_id, "appVersionCode": 79}
        app.table = FakeTable({("USER#cognito-subject", "ACCOUNT"): owner, (f"DEVICE#{server_id}", "METADATA"): server})
        for before, after in [(False, True), (True, False)]:
            for order in [{}, {"requestedUpdateCode": 80, "updateUntil": 4102444800},
                          {"requestedUpdateCode": 80, "updateUntil": 4102444800, "updateRequiresManaged": before}]:
                with self.subTest(before=before, order=order), patch.dict(server, {"managedUpdates": before, **order}):
                    result = app.handler(event(f"/v1/devices/{server_id}/heartbeat", device_id=server_id,
                                         body={"appVersionCode": 79, "managedUpdates": after,
                                               "sensorConnectionHealthy": True, "updateStatus": "INSTALLING"}), None)
                    self.assertEqual(204, result["statusCode"])
                    update = app.table.client.transactions[-1][0]["Update"]
                    removed = update["UpdateExpression"].split(" REMOVE ")[1].split(", ")
                    self.assertTrue({"requestedUpdateCode", "updateUntil", "updateRequiresManaged", "updateStatus"} <= set(removed))
                    self.assertIn("managedUpdates=:previousManaged", update["ConditionExpression"])
                    self.assertEqual(before, update["ExpressionAttributeValues"][":previousManaged"])

    def test_opted_in_server_receives_only_requested_installer_mode_and_catalog_package(self):
        owner = {"tenantId": "tenant-existing", "accountId": "account-existing"}
        sid, cid = "server-12345678", "client-12345678"
        secret = "test-device-credential" * 2
        server = {**owner, "enabled": True, "role": "GREENHOUSE_SERVER", "deviceId": sid, "appVersionCode": 81,
                  "managedUpdates": False, "deviceSecretHash": hashlib.sha256(secret.encode()).hexdigest()}
        app.table = FakeTable({("USER#cognito-subject", "ACCOUNT"): owner,
                               ("ACCOUNT#account-existing", "METADATA"): {"state": "ACTIVE"},
                               (f"DEVICE#{sid}", "METADATA"): server,
                               (f"DEVICE#{cid}", "METADATA"): {**owner, "enabled": True, "role": "VIEWER_CLIENT"}})
        app.table.query_items = [server]
        def call_server(route, body=None, credential=secret):
            path = f"/v1/device/devices/{sid}" if credential else f"/v1/devices/{sid}"
            e = event(f"{path}/{route}", method="POST" if body else "GET", device_id=sid, body=body)
            if credential:
                e["headers"]["x-greenhouse-device-secret"] = credential
            return app.handler(e, None)
        def request(target=82, actor=cid, **fields):
            return app.handler(event(f"/v1/devices/{sid}/update-request", device_id=actor,
                                     body={"versionCode": target, "requireManaged": False, **fields}), None)
        catalog = {"versionCode": 82, "versionName": "test"}
        for mode in app.ORDINARY_INSTALL_MODES:
            with self.subTest(mode=mode), patch.dict(server, {"updateMode": mode}), \
                    patch.object(app, "latest_server_release", return_value=catalog), \
                    patch.object(app, "inventory_server_release", return_value=catalog), \
                    patch.object(app, "server_release", return_value={"versionCode": 82}) as release:
                beat = {"appVersionCode": 81, "updateMode": mode, "sensorConnectionHealthy": True}
                self.assertEqual(403, call_server("heartbeat", beat, credential=None)["statusCode"])
                self.assertEqual(403, call_server("update-package")["statusCode"])
                self.assertEqual(409, request(target=83, updateMode=mode)["statusCode"])
                self.assertEqual(403, request(actor=sid, updateMode=mode)["statusCode"])
                self.assertEqual(400, request(updateMode="MANAGED")["statusCode"])
                self.assertEqual(400, request(updateMode="unexpected")["statusCode"])
                self.assertEqual(409, request(updateMode="MANUAL")["statusCode"])
                self.assertEqual(202, request(updateMode=mode)["statusCode"])
                update = app.table.updates[-1]
                values = update["ExpressionAttributeValues"]
                self.assertEqual(mode, values[":mode"])
                self.assertIn("updateMode=:previousMode", update["ConditionExpression"])
                self.assertEqual(mode, values[":previousMode"])
                order_id = values[":orderId"]
                server.update(requestedUpdateCode=82, updateUntil=values[":expires"], requestedUpdateMode=values[":mode"],
                              requestedUpdateId=order_id, updateRequiresManaged=values[":managed"])
                command = {"requestedUpdateCode": 82, "requestedUpdateMode": mode, "requestedUpdateId": order_id}
                self.assertEqual(command, json.loads(call_server("heartbeat", beat)["body"]))
                self.assertEqual(order_id, json.loads(request(updateMode=mode)["body"])["requestedUpdateId"])
                row = json.loads(app.handler(event("/v1/devices", method="GET", device_id=cid), None)["body"])["devices"][0]
                self.assertEqual((mode, mode, False), (row["updateMode"], row["requestedUpdateMode"], row["managedUpdates"]))
                self.assertEqual(403, call_server("update-package", credential=None)["statusCode"])
                package = call_server("update-package")
                self.assertEqual(200, package["statusCode"])
                self.assertEqual(order_id, json.loads(package["body"])["requestedUpdateId"])
                release.assert_called_once_with(82)
                with patch.dict(server, {"updateMode": "MANUAL"}):
                    self.assertEqual(403, call_server("update-package")["statusCode"])
                declined = {**beat, "updateStatus": "DECLINED", "updateStatusRequestId": order_id}
                stale = call_server("heartbeat", {**declined, "updateStatusRequestId": "previous-order"})
                self.assertEqual(command, json.loads(stale["body"]))
                self.assertNotIn(":updateStatus", app.table.client.transactions[-1][0]["Update"]["ExpressionAttributeValues"])
                self.assertEqual(204, call_server("heartbeat", declined)["statusCode"])
                change = app.table.client.transactions[-1][0]["Update"]
                self.assertEqual("DECLINED", change["ExpressionAttributeValues"][":updateStatus"])
                self.assertIn("requestedUpdateId=:orderId", change["ConditionExpression"])
                self.assertTrue(set(app.UPDATE_ORDER_FIELDS) <= set(change["UpdateExpression"].split(" REMOVE ")[1].split(", ")))
                with patch.dict(server):
                    for field in app.UPDATE_ORDER_FIELDS:
                        server.pop(field)
                    self.assertEqual(403, call_server("update-package")["statusCode"])
                    self.assertNotEqual(order_id, json.loads(request(updateMode=mode)["body"])["requestedUpdateId"])
                # An old client on an opted-in server still orders only a reminder.
                self.assertEqual(202, request()["statusCode"])
                server.pop("requestedUpdateId")
                server["requestedUpdateMode"] = app.table.updates[-1]["ExpressionAttributeValues"][":mode"]
                self.assertEqual("MANUAL", server["requestedUpdateMode"])
                for legacy in (False, True):
                    if legacy:
                        del server["requestedUpdateMode"]
                    self.assertEqual(403, call_server("update-package")["statusCode"])
                    self.assertEqual({"requestedUpdateCode": 82}, json.loads(call_server("heartbeat", beat)["body"]))
                release.assert_called_once()

    def test_phone_consent_or_installer_capability_transition_discards_the_old_order(self):
        owner = {"tenantId": "tenant-existing", "accountId": "account-existing"}
        sid = "server-12345678"
        server = {**owner, "enabled": True, "role": "GREENHOUSE_SERVER", "deviceId": sid, "appVersionCode": 81,
                  "managedUpdates": False, "requestedUpdateCode": 82, "updateUntil": 4102444800}
        app.table = FakeTable({("USER#cognito-subject", "ACCOUNT"): owner, (f"DEVICE#{sid}", "METADATA"): server})
        for previous, following in [("MANUAL", "SELF_UPDATE"), ("SELF_UPDATE", "CONFIRM_ON_SERVER"),
                                    ("CONFIRM_ON_SERVER", "MANUAL")]:
            with self.subTest(previous=previous, following=following), patch.dict(server, {"updateMode": previous}):
                result = app.handler(event(f"/v1/devices/{sid}/heartbeat", device_id=sid,
                                           body={"appVersionCode": 81, "sensorConnectionHealthy": True,
                                                 "updateMode": following, "updateStatus": "INSTALLING"}), None)
                self.assertEqual(204, result["statusCode"])
                update = app.table.client.transactions[-1][0]["Update"]
                removed = update["UpdateExpression"].split(" REMOVE ")[1].split(", ")
                self.assertTrue(set(app.UPDATE_ORDER_FIELDS) <= set(removed))
                self.assertEqual(previous, update["ExpressionAttributeValues"][":previousMode"])
                self.assertEqual(following, update["ExpressionAttributeValues"][":mode"])

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

    def test_client_approval_reauthorizes_same_revoked_server_with_fresh_secret(self):
        request_id = "request-reauthorize"
        request = {
            "pk": f"SERVER_PAIRING#{request_id}", "sk": "METADATA", "requestId": request_id,
            "accountId": "account-existing", "tenantId": "tenant-existing", "greenhouseId": "greenhouse-12345678",
            "greenhouseName": "North House", "serverDeviceId": "server-12345678", "replaceServerDeviceId": "server-12345678",
            "deviceSecretHash": "b" * 64, "state": "PENDING_APPROVAL", "expiresAt": 4_000_000_000,
        }
        fake = FakeTable({
            ("USER#cognito-subject", "ACCOUNT"): {"accountId": "account-existing", "tenantId": "tenant-existing"},
            ("DEVICE#client-12345678", "METADATA"): {
                "deviceId": "client-12345678", "accountId": "account-existing", "tenantId": "tenant-existing",
                "role": "VIEWER_CLIENT", "enabled": True,
            },
            (request["pk"], request["sk"]): request,
            ("DEVICE#server-12345678", "METADATA"): {
                "pk": "DEVICE#server-12345678", "sk": "METADATA", "deviceId": "server-12345678",
                "accountId": "account-existing", "tenantId": "tenant-existing", "role": "GREENHOUSE_SERVER",
                "greenhouseId": "greenhouse-12345678", "enabled": False, "communicationSuspended": False,
                "createdAt": 100, "revokedAt": 200, "revokedBy": "client-12345678",
                "deviceSecretHash": "a" * 64,
            },
            ("GREENHOUSE#greenhouse-12345678", "METADATA"): {
                "pk": "GREENHOUSE#greenhouse-12345678", "sk": "METADATA", "greenhouseId": "greenhouse-12345678",
                "accountId": "account-existing", "tenantId": "tenant-existing", "serverDeviceId": "server-12345678",
                "createdAt": 100, "layoutVersion": 7,
            },
        })
        app.table = fake

        result = app.handler(event(
            f"/v1/server-pairing/requests/{request_id}/approve", device_id="client-12345678",
        ), None)

        self.assertEqual(200, result["statusCode"])
        transaction = fake.client.transactions[0]
        device_update = transaction[0]["Update"]
        self.assertEqual({"pk": "DEVICE#server-12345678", "sk": "METADATA"}, device_update["Key"])
        self.assertEqual("b" * 64, device_update["ExpressionAttributeValues"][":secret"])
        self.assertTrue(device_update["ExpressionAttributeValues"][":enabled"])
        self.assertIn("REMOVE revokedAt, revokedBy", device_update["UpdateExpression"])
        self.assertEqual(100, transaction[2]["Put"]["Item"]["createdAt"])
        replaced = [item for item in transaction if "Update" in item and "SET enabled=:disabled, replacedAt" in item["Update"].get("UpdateExpression", "")]
        self.assertEqual([], replaced)

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
        verified = {"active": True, "expiresAt": 2_000_000_000, "testPurchase": True}
        checkout_env = {"PLAY_TEST_ACCOUNT_IDS": "account-existing", "GOOGLE_PLAY_CREDENTIALS_SECRET_ID": "fixture"}
        with patch.dict(os.environ, checkout_env):
            verified["accountBinding"] = app.play_test_checkout("account-existing")["accountBinding"]
        request = event("/v1/subscriptions/google-play/verify", device_id="viewer-12345678",
                        body={"productId": "premium_monthly", "purchaseToken": "purchase-token-123456789"})
        with patch.dict(os.environ, checkout_env), patch.object(app, "verify_google_play_purchase", return_value=verified):
            for invalid in ({"testPurchase": False}, {"accountBinding": "different-customer"}, {"active": False}):
                with self.subTest(invalid=invalid), patch.dict(verified, invalid):
                    self.assertIn(app.handler(request, None)["statusCode"], (403, 409))
                    self.assertFalse(fake.updates)
        with patch.dict(os.environ, {"PLAY_TEST_ACCOUNT_IDS": ""}):
            self.assertEqual(403, app.handler(request, None)["statusCode"])
        with patch.dict(os.environ, checkout_env), patch.object(app, "verify_google_play_purchase", return_value=verified):
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
