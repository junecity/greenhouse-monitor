"""Run the real handlers against a local DynamoDB simulator, including CAS transactions."""
import hashlib
import json
import time
import unittest
from unittest.mock import patch

import boto3
from moto import mock_aws

from test_app import app, event
from alert_rules import save_rules


BASE = {"lowTemperatureF": 41.5, "highTemperatureF": 92.5, "lowHumidity": 31.2,
        "highHumidity": 88.7, "temperatureAlerts": True, "humidityAlerts": True, "offlineAlerts": False}


class GreenhouseFixture(unittest.TestCase):
    house = "greenhouse-first"
    other = "greenhouse-other"
    client = "client-fixture"
    server = "server-fixture"
    secret = "test-device-secret" * 3

    def setUp(self):
        cloud = mock_aws()
        cloud.start()
        self.addCleanup(cloud.stop)
        self.db = boto3.resource("dynamodb", region_name="us-east-1").create_table(
            TableName="test-alert-rules", BillingMode="PAY_PER_REQUEST",
            KeySchema=[{"AttributeName": "pk", "KeyType": "HASH"}, {"AttributeName": "sk", "KeyType": "RANGE"}],
            AttributeDefinitions=[{"AttributeName": "pk", "AttributeType": "S"},
                                  {"AttributeName": "sk", "AttributeType": "S"}])
        replacement = patch.object(app, "table", self.db)
        replacement.start()
        self.addCleanup(replacement.stop)
        owner = {"accountId": "customer-test", "tenantId": "tenant-test"}
        self.put("USER#cognito-subject", "ACCOUNT", **owner)
        self.put("ACCOUNT#customer-test", "METADATA", **owner, state="ACTIVE")
        self.put(f"DEVICE#{self.client}", "METADATA", **owner, enabled=True, role="VIEWER_CLIENT")
        self.put(f"DEVICE#{self.server}", "METADATA", **owner, enabled=True, role="GREENHOUSE_SERVER",
                 greenhouseId=self.house, deviceSecretHash=hashlib.sha256(self.secret.encode()).hexdigest())
        for house, device in [(self.house, self.server), (self.other, "server-another")]:
            self.put(f"GREENHOUSE#{house}", "METADATA", **owner, enabled=True, serverDeviceId=device)

    def put(self, pk, sk, **fields):
        self.db.put_item(Item={"pk": pk, "sk": sk, **fields})

    def api(self, path, body=None, server=False, query=None):
        req = event(path, "GET" if body is None else "POST", body=body, device_id=self.server if server else self.client)
        req["queryStringParameters"] = query
        if server:
            req["headers"]["x-greenhouse-device-secret"] = self.secret
        result = app.handler(req, None)
        return result["statusCode"], json.loads(result["body"])

    def rules(self, body=None, house=None, server=False):
        p = "/v1/device" if server else "/v1"
        return self.api(f"{p}/greenhouses/{house or self.house}/alert-rules", body, server)

    def beat(self, rules=BASE, applied=0, **fields):
        return self.api(f"/v1/device/devices/{self.server}/heartbeat",
                        {"sensorConnectionHealthy": True, "alertRules": rules, "appliedAlertRulesRevision": applied, **fields}, True)


class GreenhouseAlertRulesTests(GreenhouseFixture):
    def test_seed_client_edit_ack_and_restart(self):
        self.assertEqual((200, {"revision": 0, "rules": None, "appliedRevision": 0}), self.rules())
        self.assertEqual(200, self.beat()[0])
        self.assertEqual((200, {"revision": 1, "rules": BASE, "appliedRevision": 1}), self.rules())
        updated = dict(BASE, lowTemperatureF=51.5, highHumidity=80.1, humidityAlerts=False)
        self.assertEqual((200, {"revision": 2, "rules": updated, "appliedRevision": 1}),
                         self.rules({"expectedRevision": 1, "rules": updated}))
        self.assertEqual(409, self.rules({"expectedRevision": 1, "rules": BASE})[0])
        # A restarted or stale collector may not overwrite new client settings.
        self.assertEqual({"revision": 2, "rules": updated, "appliedRevision": 0},
                         self.beat(BASE, applied=2)[1]["alertRules"])
        self.assertEqual(2, self.beat(updated, applied=2)[1]["alertRules"]["appliedRevision"])
        self.assertEqual((200, {"revision": 2, "rules": updated, "appliedRevision": 2}), self.rules())
        self.assertEqual(0, self.rules(house=self.other)[1]["revision"])
        self.assertEqual(200, self.rules({"expectedRevision": 2, "rules": BASE}, server=True)[0])

    def test_replacement_must_apply_existing_rules(self):
        self.beat()
        self.db.update_item(Key={"pk": f"GREENHOUSE#{self.house}", "sk": "METADATA"},
                            UpdateExpression="SET serverDeviceId=:id", ExpressionAttributeValues={":id": "new-server"})
        self.assertEqual(0, self.rules()[1]["appliedRevision"])
        self.assertEqual(403, self.beat()[0])
        self.assertEqual(403, self.rules(server=True)[0])
        self.assertEqual(BASE, self.rules()[1]["rules"])

    def test_explicit_client_write_before_first_collector_heartbeat(self):
        self.assertEqual(200, self.rules({"expectedRevision": 0, "rules": BASE})[0])
        hb = self.beat(dict(BASE, lowTemperatureF=36), applied=0)
        self.assertEqual(200, hb[0])
        self.assertEqual({"revision": 1, "appliedRevision": 0, "rules": BASE}, hb[1]["alertRules"])

    def test_changed_while_saving_returns_conflict_atomically(self):
        def competing_write(table, principal, house, device, expected, edited):
            save_rules(table, principal, house, device, expected, BASE)
            # The other client commits after this client reads the revision.
            return save_rules(table, principal, house, device, expected, edited)
        with patch("alert_rules.save_rules", competing_write):
            self.assertEqual(409, self.rules({"expectedRevision": 0, "rules": dict(BASE, lowTemperatureF=47)})[0])
        self.assertEqual(BASE, self.rules()[1]["rules"])

    def test_critical_temperature_independent_of_ordinary_switch_and_legacy_heartbeat(self):
        critical = dict(BASE, temperatureAlerts=False)
        self.assertEqual(200, self.rules({"expectedRevision": 0, "rules": critical})[0])
        body = dict(sensorConnectionHealthy=True, temperatureEvaluated=True, criticalTemperature=True,
                    temperatureBreach={"direction": "LOW", "temperatureF": 37, "limitF": 41.5,
                                       "sensorName": "Bench", "measuredAt": int(time.time())})
        self.assertEqual(200, self.beat(critical, applied=1, **body)[0])
        result = self.api("/v1/alerts")
        self.assertEqual("CRITICAL_TEMPERATURE", result[1]["alerts"][0]["type"])
        self.assertIn("41.5", result[1]["alerts"][0]["message"])
        # Older collectors don't advertise configuration sync and keep receiving the legacy reply.
        self.assertEqual(204, self.api(f"/v1/device/devices/{self.server}/heartbeat", body, True)[0])

    def test_rule_validation(self):
        for invalid in [dict(BASE, lowTemperatureF=93), dict(BASE, highHumidity=101),
                        dict(BASE, lowHumidity=True), dict(BASE, offlineAlerts=1),
                        dict(BASE, highTemperatureF=float("nan")), dict(BASE, masterEnabled=False),
                        {k: v for k, v in BASE.items() if k != "temperatureAlerts"}]:
            with self.subTest(invalid=invalid):
                self.assertEqual(400, self.rules({"expectedRevision": 0, "rules": invalid})[0])
        self.assertEqual(400, self.rules({"expectedRevision": False, "rules": BASE})[0])
        self.assertEqual(0, self.rules()[1]["revision"])
        # An upgraded collector with legacy invalid local bounds can still receive a correction.
        self.assertEqual(0, self.beat(None)[1]["alertRules"]["revision"])
        self.assertEqual(200, self.rules({"expectedRevision": 0, "rules": BASE})[0])
        self.assertEqual(BASE, self.beat(None)[1]["alertRules"]["rules"])

    def test_revocation_during_save_cancels_write(self):
        def revoke_before_commit(table, principal, house, device, expected, rules):
            self.db.update_item(Key={"pk": f"DEVICE#{self.client}", "sk": "METADATA"},
                                UpdateExpression="SET enabled=:value", ExpressionAttributeValues={":value": False})
            return save_rules(table, principal, house, device, expected, rules)
        with patch("alert_rules.save_rules", revoke_before_commit):
            self.assertEqual(403, self.rules({"expectedRevision": 0, "rules": BASE})[0])
        self.assertIsNone(self.rules(server=True)[1]["rules"])

    def test_account_device_and_greenhouse_authorization(self):
        self.assertEqual(403, self.rules(server=True, house=self.other)[0])
        self.put("GREENHOUSE#foreign-house", "METADATA", accountId="another", tenantId="tenant-test", enabled=True)
        self.assertEqual(403, self.rules(house="foreign-house")[0])
        self.put(f"DEVICE#{self.client}", "METADATA", enabled=False, role="VIEWER_CLIENT",
                 accountId="customer-test", tenantId="tenant-test")
        self.assertEqual(403, self.rules({"expectedRevision": 0, "rules": BASE})[0])
        self.put("ACCOUNT#customer-test", "METADATA", state="SUSPENDED")
        self.assertEqual(403, self.beat()[0])
