"""Sensor alarm episodes through the authenticated public API and DynamoDB transactions."""
import time
from unittest.mock import patch

from boto3.dynamodb.conditions import Key
from botocore.exceptions import ClientError
from test_alert_rules import GreenhouseFixture


class PerSensorTemperatureTests(GreenhouseFixture):
    def setUp(self):
        super().setUp()
        self.t = int(time.time() * 1000) - 60000
        self.account = "ACCOUNT#customer-test"

    def reading(self, sensor, value, measured=None, name=None, low=45, high=82):
        return dict(sensorId=sensor, sensorName=name or f"Sensor {sensor}", temperatureF=value,
                    lowF=low, highF=high, measuredAtEpochMs=measured or self.t)

    def report(self, readings, sent=None, **fields):
        return self.api(f"/v1/device/devices/{self.server}/heartbeat",
                        dict(sensorConnectionHealthy=True, recordedAtEpochMs=sent or self.t,
                             temperatureObservations=readings, **fields), True)

    def inbox(self, include_recovered=False):
        query = {"includeRecovered": "true"} if include_recovered else None
        status, result = self.api("/v1/alerts", query=query)
        self.assertEqual(200, status)
        return result["alerts"]

    def episodes(self):
        return {row["sensorId"]: row for row in self.inbox()}

    def ack(self, event_id):
        self.assertEqual(204, self.api(f"/v1/alerts/{event_id}/acknowledge", {})[0])

    def stored(self, event_id):
        return self.db.get_item(Key={"pk": self.account, "sk": f"ALERT#{event_id}"})["Item"]

    def test_temperature_snapshot_projection_preserves_backup_and_access_rules(self):
        snapshot = {
            "schema": 2, "preferences": {"active": "main"}, "sensors": [{"id": "bench"}],
            "serverHealth": {"serverDeviceId": self.server}, "zones": {"bench": 2}, "history": ["earlier"],
        }
        path = f"/v1/greenhouses/{self.house}/backup"
        projection = {"temperatureOnly": "true"}
        self.assertIsNone(self.api(path, query=projection)[1]["backup"])
        self.assertEqual(204, self.api(f"/v1/device/greenhouses/{self.house}/backup", {"backup": snapshot}, True)[0])
        status, reduced = self.api(path, query=projection)
        self.assertEqual(200, status)
        self.assertEqual({k: snapshot[k] for k in ("schema", "preferences", "sensors", "serverHealth")}, reduced["backup"])
        self.assertEqual((200, {"backup": snapshot, "updatedAt": reduced["updatedAt"]}), self.api(path))
        self.db.update_item(Key={"pk": f"GREENHOUSE#{self.house}", "sk": "METADATA"},
                            UpdateExpression="SET tenantId=:other", ExpressionAttributeValues={":other": "another-tenant"})
        self.assertEqual(403, self.api(path, query=projection)[0])

    def test_inventory_reports_server_capability_and_fresh_enrollment_after_pairing(self):
        device_key = {"pk": f"DEVICE#{self.server}", "sk": "METADATA"}
        self.db.update_item(Key=device_key, UpdateExpression="SET createdAt=:createdAt",
                            ExpressionAttributeValues={":createdAt": 100})
        self.put(self.account, f"DEVICE#{self.server}", deviceId=self.server, accountId="customer-test",
                 tenantId="tenant-test", role="GREENHOUSE_SERVER", greenhouseId=self.house, enabled=True, createdAt=100)

        def inventory():
            status, response = self.api("/v1/devices")
            self.assertEqual(200, status)
            server, = response["devices"]
            return server

        original = inventory()
        self.assertEqual(100_000, original["enrollmentEpochMs"])
        self.assertFalse(original["sensorTemperatureMode"])
        self.assertIsNone(original["lastDataUploadEpochMs"])
        self.assertEqual(204, self.report([self.reading("A", 89)], lastDataUploadEpochMs=self.t)[0])
        upgraded = inventory()
        self.assertTrue(upgraded["sensorTemperatureMode"])
        self.assertEqual(self.t // 1000 * 1000, upgraded["lastDataUploadEpochMs"])
        self.assertEqual(200, self.api(f"/v1/devices/{self.server}/state", {"enabled": False})[0])
        status, invitation = self.api("/v1/server-pairing/invitations", {"greenhouseId": self.house})
        self.assertEqual(201, status)
        status, request = self.api("/v1/public-server-pairing/requests", {
            "serverDeviceId": self.server, "deviceSecret": "new-test-device-secret" * 2,
            "pairingCode": invitation["displayCode"],
        })
        self.assertEqual(202, status)
        self.assertEqual(200, self.api(f"/v1/server-pairing/requests/{request['requestId']}/approve", {})[0])
        repaired = inventory()
        self.assertTrue(repaired["enabled"])
        self.assertFalse(repaired["sensorTemperatureMode"])
        self.assertEqual(original["createdAtEpochMs"], repaired["createdAtEpochMs"])
        self.assertGreater(repaired["enrollmentEpochMs"], self.t)

    def test_inbox_advertises_per_sensor_capability_on_complete_and_partial_results(self):
        status, complete = self.api("/v1/alerts")
        self.assertEqual(200, status)
        self.assertTrue(complete["sensorTemperatureInbox"])
        self.assertTrue(complete["complete"])
        with self.db.batch_writer() as batch:
            for i in range(51):
                batch.put_item(Item={"pk": self.account, "sk": f"ALERT#{i:03}", "id": f"notice-{i}",
                                     "tenantId": "tenant-test", "type": "INFO", "priority": "INFO",
                                     "createdAt": self.t // 1000, "message": "Test notice"})
        status, partial = self.api("/v1/alerts", query={"includeRecovered": "true"})
        self.assertEqual(200, status)
        self.assertTrue(partial["sensorTemperatureInbox"])
        self.assertFalse(partial["complete"])

    def test_client_closed_until_after_recovery(self):
        self.report([self.reading("A", 89.42), self.reading("B", 91)])
        self.report([self.reading("A", 71, self.t + 1)], self.t + 1)
        self.assertEqual(["B"], [a["sensorId"] for a in self.inbox()])
        received = {a["sensorId"]: a for a in self.inbox(True)}
        self.assertIn("Sensor A measured 89.42°F", received["A"]["message"])
        self.assertIn("within the configured limits", received["A"]["message"])
        self.assertGreaterEqual(received["A"]["resolvedAtEpochMs"], received["A"]["createdAtEpochMs"])
        self.assertNotIn("resolvedAtEpochMs", received["B"])
        self.assertEqual(list(received.values()), self.inbox(True))
        self.ack(received["A"]["id"])
        self.ack(received["B"]["id"])
        self.report([self.reading("B", 70, self.t + 2)], self.t + 2)
        self.assertEqual([], self.inbox(True))  # Recovery cannot resurrect an acknowledged episode.

    def test_opposite_breach_is_not_described_as_safe(self):
        self.report([self.reading("A", 30)])
        self.report([self.reading("A", 90, self.t + 1)], self.t + 1)
        alerts = self.inbox(True)
        self.assertEqual(2, len(alerts))
        ended, = [a for a in alerts if "resolvedAtEpochMs" in a]
        self.assertIn("crossed the high limit", ended["message"])
        self.assertNotIn("within", ended["message"])
        active, = [a for a in alerts if "resolvedAtEpochMs" not in a]
        self.assertNotEqual(active["id"], ended["id"])

    def test_recovered_before_viewer_registration_or_timeout_is_not_delivered(self):
        self.report([self.reading("A", 40)])
        self.report([self.reading("A", 80, self.t + 1)], self.t + 1)
        ended, = self.inbox(True)
        now = ended["resolvedAtEpochMs"] // 1000
        viewer = {"pk": f"DEVICE#{self.client}", "sk": "METADATA"}
        for field in ("createdAt", "reauthorizedAt"):
            self.db.update_item(Key=viewer, UpdateExpression=f"SET {field}=:newer",
                                ExpressionAttributeValues={":newer": now + 1})
            self.assertEqual([], self.inbox(True))
            self.db.update_item(Key=viewer, UpdateExpression=f"REMOVE {field}")
        marker = {"pk": self.account, "sk": f'TEMPERATURE_DELIVERY#{ended["id"]}'}
        self.db.update_item(Key=marker, UpdateExpression="SET resolvedAt=:old",
                            ExpressionAttributeValues={":old": now - 86401})
        self.assertEqual([], self.inbox(True))

    def test_recovered_records_require_current_owned_house_and_source_enrollment(self):
        self.report([self.reading("A", 90)])
        self.report([self.reading("A", 70, self.t + 1)], self.t + 1)
        self.assertEqual(1, len(self.inbox(True)))
        for pk, fields in [
            (f"DEVICE#{self.server}", {"enabled": False}),
            (f"DEVICE#{self.server}", {"reauthorizedAt": self.t // 1000}),
            (f"DEVICE#{self.server}", {"communicationSuspended": True}),
            (f"GREENHOUSE#{self.house}", {"serverDeviceId": "replacement"}),
            (f"GREENHOUSE#{self.house}", {"tenantId": "other-tenant"}),
            (f"GREENHOUSE#{self.house}", {"accountId": "other-customer"}),
        ]:
            with self.subTest(pk=pk, fields=fields):
                key = {"pk": pk, "sk": "METADATA"}
                original = self.db.get_item(Key=key)["Item"]
                self.db.put_item(Item={**original, **fields})
                self.assertEqual([], self.inbox(True))
                self.db.put_item(Item=original)

    def test_old_aggregate_server_can_catch_up_only_on_confirmed_recovery(self):
        path = f"/v1/device/devices/{self.server}/heartbeat"
        active = dict(sensorConnectionHealthy=True, temperatureEvaluated=True, criticalTemperature=True,
                      temperatureBreach=dict(sensorName="Bench", direction="LOW", temperatureF=39,
                                             limitF=45, measuredAt=self.t // 1000))
        self.assertEqual(204, self.api(path, active, True)[0])
        self.assertEqual(204, self.api(path, dict(sensorConnectionHealthy=True, temperatureEvaluated=False,
                                                  criticalTemperature=False), True)[0])
        self.assertNotIn("resolvedAtEpochMs", self.inbox(True)[0])
        self.assertEqual(204, self.api(path, dict(sensorConnectionHealthy=True, temperatureEvaluated=True,
                                                  criticalTemperature=False), True)[0])
        self.assertEqual([], self.inbox())
        ended, = self.inbox(True)
        self.assertIn("Bench measured 39°F", ended["message"])
        self.assertIn("resolvedAtEpochMs", ended)
        self.ack(ended["id"])
        self.assertEqual([], self.inbox(True))

    def test_old_events_without_projected_identity_cannot_be_replayed_after_upgrade(self):
        self.report([self.reading("A", 90)])
        breach, = self.inbox()
        # Equivalent pre-upgrade records contain an open item and history, but no delivery projection.
        self.db.delete_item(Key={"pk": self.account, "sk": f'TEMPERATURE_DELIVERY#{breach["id"]}'})
        self.report([self.reading("A", 74, self.t + 1)], self.t + 1)
        self.assertEqual([], self.inbox(True))

    def test_older_lambda_acknowledgement_prevents_catch_up_during_rollout(self):
        self.report([self.reading("A", 38)])
        self.report([self.reading("A", 74, self.t + 1)], self.t + 1)
        ended, = self.inbox(True)
        self.db.update_item(Key={"pk": self.account, "sk": f'ALERT#{ended["id"]}'},
                            UpdateExpression="SET acknowledgedAt=:t", ExpressionAttributeValues={":t": self.t // 1000})
        self.assertEqual([], self.inbox(True))

    def test_missing_legacy_recovery_evidence_does_not_claim_limits_were_met(self):
        path = f"/v1/device/devices/{self.server}/heartbeat"
        breach = dict(sensorName="Bench", direction="HIGH", temperatureF=91, limitF=82, measuredAt=self.t // 1000)
        self.api(path, dict(sensorConnectionHealthy=True, criticalTemperature=True, temperatureEvaluated=True,
                            temperatureBreach=breach), True)
        self.api(path, dict(sensorConnectionHealthy=True, criticalTemperature=False), True)
        self.assertEqual([], self.inbox(True))

    def test_compact_recovery_inbox_pages_after_100_sensors(self):
        sensors = [f"S{n}" for n in range(105)]
        self.report([self.reading(sensor, 36) for sensor in sensors])
        self.report([self.reading(sensor, 72, self.t + 1) for sensor in sensors], self.t + 1)
        self.assertEqual(set(sensors), {a["sensorId"] for a in self.inbox(True)})

    def test_ack_a_then_b_reaches_identical_temperature(self):
        a, b = self.reading("A", 87), self.reading("B", 87)
        self.assertEqual(204, self.report([a])[0])
        first = self.episodes()["A"]
        self.assertIn("Sensor A measured 87°F", first["message"])
        self.assertNotIn("expiresAt", self.stored(first["id"]))
        self.ack(first["id"])
        self.assertEqual(204, self.report([a, b], self.t + 1)[0])
        self.assertEqual({"B"}, self.episodes().keys())
        second = self.episodes()["B"]
        self.assertNotEqual(first["id"], second["id"])
        self.assertEqual(204, self.report([a, b], self.t + 1)[0])
        self.assertEqual(second["id"], self.episodes()["B"]["id"])

        self.report([self.reading("A", 81.9, self.t + 2)], self.t + 2)
        self.assertIn("expiresAt", self.stored(first["id"]))
        self.report([self.reading("A", 87, self.t + 3)], self.t + 3)
        events = self.episodes()
        self.assertEqual({"A", "B"}, events.keys())
        self.assertNotEqual(first["id"], events["A"]["id"])
        self.assertEqual(second["id"], events["B"]["id"])
        self.ack(second["id"])
        self.assertEqual({"A"}, self.episodes().keys())

    def test_missing_stale_retry_reorder_and_threshold_change(self):
        self.report([self.reading("A", 40), self.reading("B", 90)])
        first = self.episodes()
        self.report([], self.t + 1)
        self.report([self.reading("A", 70, self.t - 1000000)], self.t + 2)
        self.assertEqual(first, self.episodes())
        self.report([self.reading("A", 45.1, self.t + 3)], self.t + 3)
        self.report([self.reading("A", 40, self.t + 1)], self.t + 4)
        self.assertEqual({"B"}, self.episodes().keys())
        self.report([self.reading("B", 90, high=91)], self.t + 5)
        self.assertEqual({}, self.episodes())
        self.report([self.reading("B", 90, high=91, low=90.5)], self.t + 6)
        b = self.episodes()["B"]
        self.assertIn("below the low limit", b["message"])
        self.assertNotEqual(first["B"]["id"], b["id"])
        self.report([self.reading("B", 98, self.t + 7)], self.t + 7)
        self.assertIn("above the high limit", self.episodes()["B"]["message"])
        self.assertIn("resolvedAt", self.stored(b["id"]))

    def test_first_observation_at_either_limit_rings_and_stop_lasts_until_inside(self):
        def observe(low_value, high_value, step):
            t = self.t + step
            status, _ = self.report([self.reading("low", low_value, t), self.reading("high", high_value, t)], t)
            self.assertEqual(204, status)
            return self.episodes()

        first = observe(45, 82, 0)
        self.assertIn("at the low limit", first["low"]["message"])
        self.assertIn("at the high limit", first["high"]["message"])
        self.assertEqual(first, observe(39, 89, 1))
        self.ack(first["low"]["id"])
        self.assertEqual({"high": first["high"]}, observe(45, 82, 2))
        self.assertEqual({"high": first["high"]}, observe(38, 91, 3))
        self.assertEqual({}, observe(45.1, 81.9, 4))
        next_episodes = observe(44, 84, 5)
        self.assertEqual({"low", "high"}, next_episodes.keys())
        for sensor in next_episodes:
            self.assertNotEqual(first[sensor]["id"], next_episodes[sensor]["id"])

    def test_names_are_labels_and_do_not_group_episodes(self):
        self.report([self.reading("A", 88, name="Bench"), self.reading("B", 88, name="Bench")])
        old = self.episodes()
        self.assertEqual({"A", "B"}, old.keys())
        self.report([self.reading("A", 98, self.t + 1, name="Renamed")], self.t + 1)
        self.assertEqual(old, self.episodes())

    def test_ack_or_revoke_cannot_affect_different_enrollment(self):
        self.report([self.reading("A", 35)])
        first = self.episodes()["A"]["id"]
        self.db.update_item(Key={"pk": f"DEVICE#{self.server}", "sk": "METADATA"},
                            UpdateExpression="SET enabled=:value", ExpressionAttributeValues={":value": False})
        self.assertEqual([], self.inbox())
        self.assertEqual(403, self.report([self.reading("A", 40)])[0])
        self.db.update_item(Key={"pk": f"DEVICE#{self.server}", "sk": "METADATA"},
                            UpdateExpression="SET enabled=:value, reauthorizedAt=:t",
                            ExpressionAttributeValues={":value": True, ":t": self.t // 1000})
        self.report([self.reading("A", 35)])
        second = self.episodes()["A"]["id"]
        self.assertNotEqual(first, second)
        self.ack(first)
        self.assertEqual(second, self.episodes()["A"]["id"])

    def test_bad_and_foreign_measurements_do_not_partially_commit(self):
        valid = self.reading("A", 40)
        for invalid in [self.reading("B", 71, high=30), self.reading("B", True), valid,
                        self.reading("B", 72, self.t + 1), {"sensorId": "C"}]:
            with self.subTest(invalid=invalid):
                self.assertEqual(400, self.report([valid, invalid])[0])
        self.assertEqual(403, self.report([valid], greenhouseId=self.other)[0])
        self.assertEqual({}, self.episodes())

    def test_all_60_still_visible_after_510_newer_resolved_alarms(self):
        self.assertEqual(204, self.report([self.reading(f"S{i}", 90) for i in range(60)])[0])
        with self.db.batch_writer() as writer:
            for i in range(510):
                writer.put_item(Item={"pk": self.account, "sk": f"ALERT#9999999999-{i:03}",
                                      "type": "CRITICAL_TEMPERATURE", "tenantId": "tenant-test",
                                      "sensorId": "changing-sensor", "message": "Closed", "resolvedAt": self.t // 1000})
        self.assertEqual({f"S{i}" for i in range(60)}, self.episodes().keys())

    def test_concurrent_newer_safe_reading_cannot_be_overwritten_by_older_high(self):
        self.report([self.reading("A", 60)])
        transact = self.db.meta.client.transact_write_items
        def competing_write(**args):
            if args["TransactItems"][0].get("ConditionCheck"):
                with patch.object(self.db.meta.client, "transact_write_items", transact):
                    self.assertEqual(204, self.report([self.reading("A", 71, self.t + 2)], self.t + 2)[0])
            return transact(**args)
        with patch.object(self.db.meta.client, "transact_write_items", competing_write):
            self.assertEqual(204, self.report([self.reading("A", 90, self.t + 1)], self.t + 1)[0])
        self.assertEqual({}, self.episodes())
        self.assertEqual([], self.db.query(
            KeyConditionExpression=Key("pk").eq(self.account) & Key("sk").begins_with("ALERT#"))["Items"])

    def test_partially_delivered_large_heartbeat_retries_without_duplicate_events(self):
        observations = [self.reading(f"S{s}", 88) for s in range(40)]
        transport = self.db.meta.client.transact_write_items
        calls = 0
        def interrupt_once(**args):
            nonlocal calls
            calls += 1
            if calls == 2:
                raise ClientError({"Error": {"Code": "ProvisionedThroughputExceededException"}}, "TransactWriteItems")
            return transport(**args)
        with patch.object(self.db.meta.client, "transact_write_items", interrupt_once):
            self.assertEqual(503, self.report(observations)[0])
        partial = self.episodes()
        self.assertTrue(0 < len(partial) < len(observations))
        self.assertEqual(204, self.report(observations)[0])
        complete = self.episodes()
        self.assertEqual(40, len(complete))
        for sensor, event in partial.items():
            self.assertEqual(event["id"], complete[sensor]["id"])
        self.assertEqual(40, self.db.query(
            KeyConditionExpression=Key("pk").eq(self.account) & Key("sk").begins_with("ALERT#"))["Count"])

    def test_upgrading_cannot_assign_acknowledgement_by_display_name(self):
        legacy = dict(criticalTemperature=True, temperatureEvaluated=True, sensorConnectionHealthy=True,
                      temperatureBreach={"sensorName": "Bench", "temperatureF": 87, "limitF": 82,
                                         "direction": "HIGH", "measuredAt": self.t // 1000})
        path = f"/v1/device/devices/{self.server}/heartbeat"
        self.assertEqual(204, self.api(path, legacy, True)[0])
        event = self.inbox()[0]
        self.assertNotIn("sensorId", event)
        self.report([], self.t + 1)
        self.assertEqual(event["id"], self.inbox()[0]["id"])
        self.ack(event["id"])
        self.assertEqual(204, self.report([self.reading("A", 88, name="Bench"),
                                          self.reading("B", 88, name="Bench")], self.t + 2)[0])
        current = self.episodes()
        self.assertEqual({"A", "B"}, current.keys())
        self.assertNotIn(event["id"], [row["id"] for row in current.values()])
        self.assertEqual(204, self.api(path, legacy, True)[0])
        self.assertEqual(current, self.episodes())
