"""Humidity alarms through the greenhouse heartbeat and authenticated viewer inbox."""
import time
from unittest.mock import patch

from botocore.exceptions import ClientError
from test_alert_rules import GreenhouseFixture


class PerSensorHumidityTests(GreenhouseFixture):
    def setUp(self):
        super().setUp()
        self.start = int(time.time() * 1000) - 60000
        self.account = "ACCOUNT#customer-test"

    def reading(self, sensor, value, step=0):
        return dict(sensorId=sensor, sensorName=f"Bench {sensor}", measuredAtEpochMs=self.start + step,
                    humidityPercent=value, lowHumidity=30, highHumidity=80)

    def beat_rh(self, readings, step=0, enabled=True, **fields):
        return self.api(f"/v1/device/devices/{self.server}/heartbeat",
                        dict(sensorConnectionHealthy=True, recordedAtEpochMs=self.start + step,
                             humidityAlertsEnabled=enabled, humidityObservations=readings, **fields), True)

    def inbox(self, recovered=False, humidity=True):
        query = {"includeRecovered": str(recovered).lower()}
        if humidity:
            query["includeHumidity"] = "true"
        status, payload = self.api("/v1/alerts", query=query)
        self.assertEqual(200, status)
        self.assertEqual(humidity, payload.get("sensorHumidityInbox", False))
        return payload["alerts"]

    def stop(self, episode):
        self.assertEqual(204, self.api(f"/v1/alerts/{episode['id']}/acknowledge", {})[0])

    def test_shared_limits_independent_sensors_and_temperature_including_exact_crossings(self):
        temp = dict(sensorId="A", sensorName="Bench A", measuredAtEpochMs=self.start, temperatureF=35, lowF=45, highF=80)
        self.assertEqual(204, self.beat_rh([self.reading("A", 30), self.reading("B", 80)], temperatureObservations=[temp])[0])
        humidity = {a["sensorId"]: a for a in self.inbox() if a["type"] == "CRITICAL_HUMIDITY"}
        self.assertEqual({"A", "B"}, humidity.keys())
        self.assertEqual("Bench A measured 30% RH, at the low limit of 30% RH.", humidity["A"]["message"])
        self.assertIn("at the high limit", humidity["B"]["message"])
        self.assertTrue(humidity["A"]["requiresAcknowledgement"])
        self.assertEqual("CRITICAL", humidity["A"]["priority"])
        temperature, = self.inbox(humidity=False)
        self.assertEqual(("A", "CRITICAL_TEMPERATURE"), (temperature["sensorId"], temperature["type"]))
        self.stop(humidity["A"])
        self.assertEqual(204, self.beat_rh([self.reading("A", 25, 1), self.reading("C", 89.4, 1)], 1)[0])
        self.assertEqual({"B", "C"}, {a["sensorId"] for a in self.inbox() if a["type"] == "CRITICAL_HUMIDITY"})
        self.assertEqual([temperature], self.inbox(humidity=False))
        self.assertEqual(204, self.beat_rh([self.reading("A", 32, 2)], 2)[0])
        self.assertEqual(204, self.beat_rh([self.reading("A", 26, 3)], 3)[0])
        reopened, = [a for a in self.inbox() if a["sensorId"] == "A" and a["type"] == "CRITICAL_HUMIDITY"]
        self.assertNotEqual(reopened["id"], humidity["A"]["id"])

    def test_missing_stale_and_out_of_order_readings_and_recovered_delivery_to_closed_viewer(self):
        self.beat_rh([self.reading("A", 89.4)])
        alert, = self.inbox()
        for readings, step in (([], 1), ([self.reading("A", 50, -1000000)], 2), ([self.reading("A", 50, -1)], 3)):
            self.assertEqual(204, self.beat_rh(readings, step)[0])
            self.assertEqual([alert], self.inbox())
        self.beat_rh([self.reading("A", 75, 4)], 4)
        self.assertEqual([], self.inbox())
        self.assertEqual([], self.inbox(True, humidity=False))
        ended, = self.inbox(True)
        self.assertEqual(alert["id"], ended["id"])
        self.assertIn("89.4% RH, above the high limit of 80% RH", ended["message"])
        self.assertIn("within the configured limits", ended["message"])
        self.assertIn("resolvedAtEpochMs", ended)
        self.stop(ended)
        self.assertEqual([], self.inbox(True))

    def test_disable_suppresses_pending_and_catchup_and_reenable_requires_new_reading(self):
        self.beat_rh([self.reading("A", 90), self.reading("B", 12)])
        self.beat_rh([self.reading("B", 50, 1)], 1)
        original = self.inbox(True)
        self.assertEqual(2, len(original))
        self.assertEqual(204, self.beat_rh([], 2, enabled=False)[0])
        self.assertEqual([], self.inbox(True))
        self.assertEqual(204, self.beat_rh([self.reading("C", 90, 1)], 1)[0])
        self.assertEqual([], self.inbox(True))
        self.beat_rh([self.reading("A", 90)], 3)
        self.assertEqual([], self.inbox(True))
        self.beat_rh([self.reading("A", 90, 4)], 4)
        current, = self.inbox(True)
        self.assertNotIn(current["id"], {a["id"] for a in original})
        self.beat_rh([], 3, enabled=False)
        self.assertEqual([current], self.inbox(True))

    def test_bad_or_unowned_report_never_commits_and_reassignment_hides_existing_alert(self):
        valid = self.reading("A", 90)
        for invalid in [self.reading("B", 101), self.reading("B", True), dict(self.reading("B", 50), lowHumidity=80),
                        dict(self.reading("B", 50), highHumidity=90), valid]:
            self.assertEqual(400, self.beat_rh([valid, invalid])[0])
        self.assertEqual(400, self.beat_rh([valid], enabled=False)[0])
        self.assertEqual(400, self.beat_rh([], enabled="false")[0])
        self.assertEqual(403, self.beat_rh([valid], greenhouseId=self.other)[0])
        self.assertEqual([], self.inbox(True))
        self.beat_rh([valid])
        self.assertEqual(1, len(self.inbox()))
        key = {"pk": f"GREENHOUSE#{self.house}", "sk": "METADATA"}
        self.db.update_item(Key=key, UpdateExpression="SET serverDeviceId=:new", ExpressionAttributeValues={":new": "other"})
        self.assertEqual([], self.inbox(True))

    def test_capability_and_multi_batch_retry(self):
        self.put(self.account, f"DEVICE#{self.server}", deviceId=self.server, accountId="customer-test",
                 tenantId="tenant-test", role="GREENHOUSE_SERVER", greenhouseId=self.house, enabled=True, createdAt=123)
        def inventory():
            return self.api("/v1/devices")[1]["devices"][0]
        self.assertFalse(inventory()["sensorHumidityMode"])
        reports = [self.reading(f"bench-{i}", 82) for i in range(52)]
        transport = self.db.meta.client.transact_write_items
        calls = 0

        def interrupt(**kwargs):
            nonlocal calls
            calls += 1
            if calls == 2:
                raise ClientError({"Error": {"Code": "ProvisionedThroughputExceededException"}}, "TransactWriteItems")
            return transport(**kwargs)

        with patch.object(self.db.meta.client, "transact_write_items", interrupt):
            self.assertEqual(503, self.beat_rh(reports)[0])
        partial = self.inbox()
        self.assertTrue(0 < len(partial) < len(reports))
        self.assertEqual(204, self.beat_rh(reports)[0])
        complete = self.inbox()
        self.assertEqual(52, len(complete))
        self.assertTrue({a["id"] for a in partial}.issubset({a["id"] for a in complete}))
        self.assertTrue(inventory()["sensorHumidityMode"])
        self.assertFalse(inventory()["sensorTemperatureMode"])
        self.assertEqual(204, self.beat_rh([], 1, enabled=False)[0])
        self.assertEqual([], self.inbox(True))
