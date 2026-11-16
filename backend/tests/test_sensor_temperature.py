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

    def inbox(self):
        status, result = self.api("/v1/alerts")
        self.assertEqual(200, status)
        return result["alerts"]

    def episodes(self):
        return {row["sensorId"]: row for row in self.inbox()}

    def ack(self, event_id):
        self.assertEqual(204, self.api(f"/v1/alerts/{event_id}/acknowledge", {})[0])

    def stored(self, event_id):
        return self.db.get_item(Key={"pk": self.account, "sk": f"ALERT#{event_id}"})["Item"]

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

        self.report([self.reading("A", 82, self.t + 2)], self.t + 2)
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
        self.report([self.reading("A", 45, self.t + 3)], self.t + 3)
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
