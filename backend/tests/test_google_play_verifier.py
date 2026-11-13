import os
from unittest.mock import patch
import pathlib
import sys
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).parents[1] / "src"))

from google_play_verifier import GooglePlayVerificationError, parse_subscription, verify_google_play_purchase


class GooglePlayVerifierTests(unittest.TestCase):
    def test_active_subscription_is_parsed_without_exposing_token(self):
        result = parse_subscription({
            "subscriptionState": "SUBSCRIPTION_STATE_ACTIVE",
            "acknowledgementState": "ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED",
            "lineItems": [{"productId": "premium_monthly", "expiryTime": "2030-01-01T00:00:00Z"}],
        }, "premium_monthly")
        self.assertTrue(result["active"])
        self.assertGreater(result["expiresAt"], 1_800_000_000)

    def test_approved_test_purchase_is_acknowledged_only_after_play_confirms_ownership(self):
        line = {"productId": "premium_monthly", "expiryTime": "2030-01-01T00:00:00Z",
                "offerDetails": {"basePlanId": "monthly"}}
        payload = {"testPurchase": {}, "lineItems": [line], "subscriptionState": "SUBSCRIPTION_STATE_ACTIVE",
                   "acknowledgementState": "ACKNOWLEDGEMENT_STATE_PENDING",
                   "externalAccountIdentifiers": {"obfuscatedExternalAccountId": "owner"}}
        with patch.dict(os.environ, {"GOOGLE_PLAY_PACKAGE_NAME": "fixture", "GOOGLE_PLAY_CREDENTIALS_SECRET_ID": "fixture"}), \
             patch("google_play_verifier.boto3.client") as secrets, \
             patch("google.oauth2.service_account.Credentials.from_service_account_info"), \
             patch("google.auth.transport.requests.AuthorizedSession") as session:
            secrets.return_value.get_secret_value.return_value = {"SecretString": "{}"}
            http = session.return_value
            http.get.return_value.status_code = 200
            http.get.return_value.json.return_value = payload
            http.post.return_value.ok = True
            def verify():
                return verify_google_play_purchase("premium_monthly", "fixture-token", test_account_binding="owner")
            for invalid in ({"testPurchase": None}, {"externalAccountIdentifiers": {}}, {"lineItems": [dict(line, offerDetails={})]}):
                with self.subTest(invalid=invalid), patch.dict(payload, invalid):
                    with self.assertRaisesRegex(GooglePlayVerificationError, "purchase_not_eligible"):
                        verify()
            http.post.assert_not_called()
            self.assertTrue(verify()["active"])
            self.assertTrue(http.post.call_args.args[0].endswith(":acknowledge"))
            http.post.return_value.ok = False
            with self.assertRaisesRegex(GooglePlayVerificationError, "acknowledgement_failed"):
                verify()
            with patch.dict(payload, {"acknowledgementState": "ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED",
                                      "subscriptionState": "SUBSCRIPTION_STATE_CANCELED"}):
                http.post.reset_mock()
                self.assertTrue(verify()["active"])
                http.post.assert_not_called()

    def test_product_mismatch_fails_closed(self):
        with self.assertRaisesRegex(GooglePlayVerificationError, "google_play_product_mismatch"):
            parse_subscription({
                "subscriptionState": "SUBSCRIPTION_STATE_ACTIVE",
                "lineItems": [{"productId": "other", "expiryTime": "2030-01-01T00:00:00Z"}],
            }, "premium_monthly")


if __name__ == "__main__":
    unittest.main()
