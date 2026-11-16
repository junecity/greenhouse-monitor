import pathlib
import sys
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).parents[1] / "src"))

from alerts import ALERT_RETENTION_SECONDS, new_alert, temperature_breach_message


class AlertRecordTests(unittest.TestCase):
    def test_measured_temperature_evidence_is_validated(self):
        details = {"temperatureF": 92.25, "limitF": 90, "direction": "HIGH", "sensorName": "Roof",
                   "measuredAt": 99_999}
        self.assertEqual("Roof measured 92.25°F, above the high limit of 90°F.",
                         temperature_breach_message(details, 100_000))
        for invalid in [{"temperatureF": 89}, {"temperatureF": float("nan")}, {"limitF": False},
                        {"temperatureF": "999"}, {"direction": "LOW"}, {"measuredAt": 1},
                        {"measuredAt": 100_500}, {"sensorName": " "}, {"direction": "ON"}]:
            with self.subTest(invalid=invalid):
                self.assertIsNone(temperature_breach_message(dict(details, **invalid), 100_000))

    def test_critical_alert_is_account_scoped_and_expires(self):
        alert = new_alert(
            account_id="account-12345678", tenant_id="tenant-12345678",
            greenhouse_id="greenhouse-12345678", device_id="server-12345678",
            alert_type="CRITICAL_TEMPERATURE", created_at=100,
            alert_id="0000000100-aaaaaaaa",
        )

        self.assertEqual("ACCOUNT#account-12345678", alert["pk"])
        self.assertEqual("ALERT#0000000100-aaaaaaaa", alert["sk"])
        self.assertEqual("CRITICAL", alert["priority"])
        self.assertTrue(alert["requiresAcknowledgement"])
        self.assertEqual(100 + ALERT_RETENTION_SECONDS, alert["expiresAt"])


if __name__ == "__main__":
    unittest.main()
