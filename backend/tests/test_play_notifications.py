import base64
import json
import os
import time
from unittest.mock import patch

import camera_archive
import play_notifications
from google_play_verifier import GooglePlayVerificationError
from test_alert_rules import GreenhouseFixture
from test_app import app, event


class PlayNotificationTests(GreenhouseFixture):
    path = "/v1/billing/google-play/notifications"
    token = "a-realistic-archive-purchase-token"
    account = "customer-test"
    package = "studio.junecity.greenhouse"
    sender = "push@project.iam.gserviceaccount.com"
    audience = "https://example.test" + path

    def setUp(self):
        super().setUp()
        environ = patch.dict(os.environ, {
            "CAMERA_ARCHIVE_BUCKET": "archive-test", "CAMERA_ARCHIVE_SALES_MODE": "Production",
            "GOOGLE_PLAY_PACKAGE_NAME": self.package, "GOOGLE_PLAY_CREDENTIALS_SECRET_ID": "fixture",
            "GOOGLE_PLAY_RTDN_SERVICE_ACCOUNT": self.sender, "GOOGLE_PLAY_RTDN_AUDIENCE": self.audience,
        })
        environ.start()
        self.addCleanup(environ.stop)
        self.checked = []
        self.states = {}
        self.binding = app.play_catalog(self.account, camera_archive.PRODUCT)["accountBinding"]
        self.expiry = int(time.time()) + 86400
        self.states[self.token] = self.purchase()
        for module in (camera_archive, play_notifications):
            verifier = patch.object(module, "verify_google_play_purchase", side_effect=self.play)
            verifier.start()
            self.addCleanup(verifier.stop)
        # The phone can close immediately after it obtains its Play checkout offer.
        code, access = self.api(f"/v1/greenhouses/{self.house}/recordings/access")
        self.assertEqual(200, code)
        self.assertEqual(self.binding, access["checkout"]["accountBinding"])
        self.assertFalse(access["active"])

    def purchase(self, **changes):
        return dict({"active": True, "expiresAt": self.expiry, "testPurchase": False,
                     "accountBinding": self.binding, "startedAt": 100}, **changes)

    def play(self, product, token, **options):
        self.assertEqual(camera_archive.PRODUCT, product)
        self.checked.append((token, options))
        if token not in self.states:
            raise GooglePlayVerificationError("google_play_purchase_invalid")
        state = self.states[token]
        if isinstance(state, Exception):
            raise state
        return state

    def grant(self):
        return self.db.get_item(Key={"pk": "ACCOUNT#" + self.account, "sk": "CAMERA_STORAGE"}, ConsistentRead=True).get("Item")

    def push(self, *, message_id="event1", token=None, kind=4, payload=None, claims=None, package=None, outer_base64=False):
        event_data = {"version": "1.0", "packageName": package or self.package, "eventTimeMillis": str(int(time.time() * 1000))}
        event_data.update(payload if payload is not None else {
            "subscriptionNotification": {"version": "1.0", "notificationType": kind, "purchaseToken": token or self.token}})
        request = event(self.path, body={
            "subscription": "projects/project/subscriptions/archive",
            "message": {"messageId": message_id, "data": base64.b64encode(json.dumps(event_data).encode()).decode()},
        })
        request["requestContext"]["authorizer"]["jwt"]["claims"] = claims if claims is not None else {
            "email": self.sender, "iss": "https://accounts.google.com", "aud": self.audience, "email_verified": "true"}
        if outer_base64:
            request["body"] = base64.b64encode(request["body"].encode()).decode()
            request["isBase64Encoded"] = True
        return app.handler(request, None)["statusCode"]

    def test_phone_closed_first_payment_registration_renewal_cancellation_and_revocation(self):
        self.assertIsNone(self.grant())
        self.assertEqual(200, self.push(outer_base64=True))
        self.assertEqual(self.expiry, self.grant()["paidThrough"])
        self.assertEqual([(self.token, {}), (self.token, {"account_binding": self.binding, "require_test_purchase": False})],
                         self.checked)
        self.assertEqual(200, self.push())
        self.assertEqual(2, len(self.checked))
        # The user has canceled renewal, but Play still grants access through the last paid day.
        self.states[self.token] = self.purchase(expiresAt=self.expiry + 3600)
        for kind in (2, 3):
            self.assertEqual(200, self.push(kind=kind, message_id=f"lifecycle-{kind}"))
            self.assertEqual(self.expiry + 3600, self.grant()["paidThrough"])
        voided = {"voidedPurchaseNotification": {"productType": 1, "purchaseToken": self.token, "refundType": 1}}
        # Refunding an earlier order may leave the current term entitled.
        with patch.dict(os.environ, {"CAMERA_ARCHIVE_BUCKET": ""}):
            self.assertEqual(200, self.push(message_id="earlier-order-refunded", payload=voided))
        self.assertEqual(self.expiry + 3600, self.grant()["paidThrough"])
        self.states[self.token] = self.purchase(active=False)
        self.assertEqual(200, self.push(message_id="full-refund", payload=voided))
        self.assertEqual(0, self.grant()["paidThrough"])
        # A delayed PURCHASED notification also observes the authoritative current state.
        self.assertEqual(200, self.push(kind=4, message_id="old-purchase"))
        self.assertEqual(0, self.grant()["paidThrough"])

    def test_trusted_issuer_audience_and_sender_required(self):
        claims = {"email": self.sender, "email_verified": True, "iss": "https://accounts.google.com", "aud": self.audience}
        for field, value in (("email", "other@example.org"), ("email_verified", "false"),
                             ("iss", "https://cognito-idp.example.com"), ("aud", "another-push-audience")):
            with self.subTest(field=field):
                self.assertEqual(403, self.push(claims=dict(claims, **{field: value})))
        self.assertEqual(403, self.push(claims={}))
        with patch.dict(os.environ, {"GOOGLE_PLAY_RTDN_SERVICE_ACCOUNT": ""}):
            self.assertEqual(403, self.push(claims=claims))
        self.assertFalse(self.checked)
        self.assertIsNone(self.grant())

    def test_unrelated_products_packages_and_account_environment_do_not_grant_storage(self):
        self.assertEqual(200, self.push(package="some.other.app"))
        for payload in (
            {"testNotification": {"version": "1.0"}},
            {"oneTimeProductNotification": {"purchaseToken": self.token}},
            {"voidedPurchaseNotification": {"productType": 2, "purchaseToken": self.token}},
            {"subscriptionNotification": {"subscriptionId": "premium_monthly", "purchaseToken": self.token}},
        ):
            self.assertEqual(200, self.push(payload=payload))
        self.assertFalse(self.checked)
        for i, state in enumerate((GooglePlayVerificationError("google_play_product_mismatch"),
                                   self.purchase(accountBinding="a"*64), self.purchase(testPurchase=True))):
            self.states[self.token] = state
            self.assertEqual(200, self.push(message_id=f"ineligible-{i}"))
        self.assertIsNone(self.grant())
        self.assertTrue(all(not options for _, options in self.checked))

    def test_play_failure_retries_without_losing_existing_paid_access(self):
        self.assertEqual(200, self.push())
        prior = self.grant()
        self.states[self.token] = GooglePlayVerificationError("google_play_unavailable")
        self.assertEqual(503, self.push(message_id="retryable", kind=2))
        self.assertEqual(prior, self.grant())
        self.states[self.token] = self.purchase(expiresAt=self.expiry + 86400)
        with patch.object(camera_archive, "paid_period", side_effect=camera_archive.ArchiveGrantChanged):
            self.assertEqual(503, self.push(message_id="retryable", kind=2))
        self.assertEqual(200, self.push(message_id="retryable", kind=2))
        self.assertEqual(self.expiry + 86400, self.grant()["paidThrough"])

    def test_new_purchase_retries_if_play_has_not_published_the_token_yet(self):
        del self.states[self.token]
        self.assertEqual(503, self.push())
        self.assertIsNone(self.grant())
        self.states[self.token] = self.purchase()
        self.assertEqual(200, self.push())
        self.assertEqual(self.token, self.grant()["purchaseToken"])

    def test_late_old_token_cannot_overwrite_its_replacement(self):
        self.assertEqual(200, self.push())
        new = "resubscribed-archive-token"
        self.states[new] = self.purchase(linkedPurchaseToken=self.token, startedAt=200)
        self.assertEqual(200, self.push(token=new, message_id="replacement"))
        self.assertEqual(new, self.grant()["purchaseToken"])
        self.assertEqual(200, self.push(message_id="earlier-interval"))
        self.assertEqual(new, self.grant()["purchaseToken"])
        self.states[self.token] = self.purchase(active=False)
        self.assertEqual(200, self.push(message_id="earlier-refund", kind=12))
        self.assertEqual(new, self.grant()["purchaseToken"])

    def test_pending_and_malformed_push_never_grant_storage(self):
        self.states[self.token] = self.purchase(active=False, expiresAt=0)
        self.assertEqual(200, self.push())
        self.assertIsNone(self.grant())
        self.assertEqual(400, self.push(payload={"subscriptionNotification": []}))
        self.assertEqual(400, self.push(payload={"subscriptionNotification": {"purchaseToken": 42}}))
        self.states[self.token] = self.purchase()
        self.assertEqual(200, self.push(message_id="purchase-completed"))
        self.assertEqual(self.token, self.grant()["purchaseToken"])
