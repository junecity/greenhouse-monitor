import pathlib
import sys
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).parents[1] / "src"))

from google_play_verifier import GooglePlayVerificationError, parse_subscription


class GooglePlayVerifierTests(unittest.TestCase):
    def test_active_subscription_is_parsed_without_exposing_token(self):
        result = parse_subscription({
            "subscriptionState": "SUBSCRIPTION_STATE_ACTIVE",
            "acknowledgementState": "ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED",
            "lineItems": [{"productId": "premium_monthly", "expiryTime": "2030-01-01T00:00:00Z"}],
        }, "premium_monthly")
        self.assertTrue(result["active"])
        self.assertGreater(result["expiresAt"], 1_800_000_000)

    def test_product_mismatch_fails_closed(self):
        with self.assertRaisesRegex(GooglePlayVerificationError, "google_play_product_mismatch"):
            parse_subscription({
                "subscriptionState": "SUBSCRIPTION_STATE_ACTIVE",
                "lineItems": [{"productId": "other", "expiryTime": "2030-01-01T00:00:00Z"}],
            }, "premium_monthly")


if __name__ == "__main__":
    unittest.main()
