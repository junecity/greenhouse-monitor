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
os.environ.setdefault("ALERT_TOPIC_ARN", "arn:aws:sns:us-east-1:123456789012:test")
sys.path.insert(0, str(pathlib.Path(__file__).parents[1] / "src"))

import heartbeat_monitor


class FakeClient:
    def __init__(self):
        self.transactions = []

    def transact_write_items(self, **kwargs):
        self.transactions.append(kwargs["TransactItems"])


class FakeTable:
    name = "greenhouse-test"

    def __init__(self):
        self.client = FakeClient()
        self.meta = SimpleNamespace(client=self.client)
        self.calls = 0

    def query(self, **_kwargs):
        self.calls += 1
        if self.calls == 1:
            return {"Items": [{
                "pk": "DEVICE#server-12345678", "sk": "METADATA",
                "accountId": "account-12345678", "tenantId": "tenant-12345678",
                "greenhouseId": "greenhouse-12345678", "deviceId": "server-12345678",
                "lastHeartbeat": 10, "enabled": True,
            }]}
        return {"Items": []}


class FakeSns:
    def __init__(self):
        self.messages = []

    def publish(self, **kwargs):
        self.messages.append(kwargs)


class HeartbeatMonitorTests(unittest.TestCase):
    def test_offline_incident_and_alert_are_atomic(self):
        old_table, old_sns = heartbeat_monitor.table, heartbeat_monitor.sns
        fake_table, fake_sns = FakeTable(), FakeSns()
        heartbeat_monitor.table, heartbeat_monitor.sns = fake_table, fake_sns
        try:
            with patch("heartbeat_monitor.time.time", return_value=1000):
                result = heartbeat_monitor.handler({}, None)
        finally:
            heartbeat_monitor.table, heartbeat_monitor.sns = old_table, old_sns

        self.assertEqual(1, result["alerts"])
        self.assertEqual(2, len(fake_table.client.transactions[0]))
        alert = fake_table.client.transactions[0][1]["Put"]["Item"]
        self.assertEqual("SERVER_OFFLINE", alert["type"])
        self.assertEqual(1, len(fake_sns.messages))


if __name__ == "__main__":
    unittest.main()
