"""Mixed-mode capacity and Play lifecycle against the actual API with a transactional DynamoDB."""
import base64
import hashlib
import json
import os
import time
from unittest.mock import patch

from botocore.exceptions import ClientError

from test_alert_rules import GreenhouseFixture
from test_app import app, event
import greenhouse_plan as plans
from google_play_verifier import GooglePlayVerificationError, parse_greenhouse_subscription


class GreenhousePlanTests(GreenhouseFixture):
    account = "customer-test"
    token = "fixture-owner-receipt-1"
    path = "/v1/greenhouse-plan"

    def setUp(self):
        super().setUp()
        self.owner = app.Principal(self.account, "tenant-test", self.client)
        env = patch.dict(os.environ, {
            "GREENHOUSE_PLAN_SALES_MODE": "Production", "GREENHOUSE_PLAN_MAX_EXTRAS": "10",
            "GOOGLE_PLAY_PACKAGE_NAME": "studio.junecity.greenhouse", "GOOGLE_PLAY_CREDENTIALS_SECRET_ID": "test",
            "GOOGLE_PLAY_RTDN_AUDIENCE": "https://example.test/notifications",
            "GOOGLE_PLAY_RTDN_SERVICE_ACCOUNT": "push@fixture.test"})
        env.start()
        self.addCleanup(env.stop)
        self.through = int(time.time()) + 7200
        self.receipts = {self.token: self.receipt()}
        verifier = patch.object(plans, "verify_google_play_purchase", side_effect=self.play)
        verifier.start()
        self.addCleanup(verifier.stop)

    def receipt(self, slots=1, **overrides):
        return dict({"productId": f"greenhouse_extra_{slots}", "expiresAt": self.through,
                     "active": True, "accountBinding": app.play_catalog(self.account, "")["accountBinding"],
                     "testPurchase": False, "startedAt": 100, "renews": True,
                     "subscriptionState": "SUBSCRIPTION_STATE_ACTIVE"}, **overrides)

    def play(self, product, token, **kwargs):
        self.assertEqual(plans.GREENHOUSE_FAMILY, product)
        result = self.receipts[token]
        if isinstance(result, Exception):
            raise result
        return result

    def plan(self, operation=None, *, server=False, **kwargs):
        path = "/v1/device/greenhouse-plan" if server else self.path
        return self.api(path + "/" + operation, kwargs, server=server) if operation else self.api(path, server=server)

    def add(self, id):
        return self.plan("activate", houseId=id, name=id.title())

    def pay(self, slots=1, token=None):
        return self.plan("verify", productId=f"greenhouse_extra_{slots}", purchaseToken=token or self.token)

    def invite(self, cloud, alias=None):
        return self.api("/v1/server-pairing/invitations", {"greenhouseId": cloud, "greenhouseName": "Attached",
                                                          "planHouseId": alias})

    def pair(self, invitation, id="server-new-123"):
        request = event("/v1/public-server-pairing/requests", body={
            "serverDeviceId": id, "deviceSecret": self.secret, "pairingCode": invitation["displayCode"]})
        result = app.handler(request, None)
        self.assertEqual(202, result["statusCode"])
        ident = json.loads(result["body"])["requestId"]
        return self.api(f"/v1/server-pairing/requests/{ident}/approve", {})

    def seed_directory(self, device=None, house=None, created=1):
        self.put("ACCOUNT#" + self.account, "DEVICE#" + (device or self.server), tenantId="tenant-test",
                 accountId=self.account, enabled=True, greenhouseId=house or self.house, createdAt=created,
                 deviceId=device or self.server, role="GREENHOUSE_SERVER")

    def test_free_standalone_then_attach_or_replace_server_in_place(self):
        self.assertTrue(self.add("main")[1]["houses"][0]["free"])
        self.assertEqual((409, {"error": "greenhouse_plan_required"}), self.add("other"))
        cloud = "greenhouse-new-123"
        self.assertEqual(409, self.invite(cloud)[0])
        self.assertEqual(200, self.pair(self.invite(cloud, "main")[1])[0])
        row, = self.plan()[1]["houses"]
        self.assertEqual(("main", True, cloud), (row["id"], row["free"], row["serverGreenhouseId"]))
        self.assertEqual(200, self.pair(self.invite(cloud)[1], id="server-replacement")[0])
        self.assertEqual(409, self.invite("greenhouse-other-123", "main")[0])
        reg = event("/v1/devices", device_id="server-third", body={
            "role": "GREENHOUSE_SERVER", "deviceId": "server-third", "greenhouseId": "greenhouse-other-123"})
        self.assertEqual(409, app.handler(reg, None)["statusCode"])

    def test_upgrade_migrates_existing_servers_and_scopes_collector_mutations(self):
        self.seed_directory()
        self.seed_directory("server-another", self.other, created=2)
        initial = self.plan(server=True)[1]["houses"]
        self.assertEqual([self.house, self.other], [row["id"] for row in initial])
        self.assertTrue(initial[0]["free"] and initial[1]["grandfathered"])
        self.assertEqual(409, self.add("third")[0])
        self.assertEqual(200, self.pay()[0])
        self.assertEqual(403, self.plan("remove", houseId=self.other, server=True)[0])
        self.assertEqual(200, self.plan("rename", houseId=self.house, name="Upper", server=True)[0])
        self.assertEqual(403, self.plan("rename", houseId=self.other, name="Wrong", server=True)[0])
        code, added = self.plan("activate", houseId="collector-floor-uuid", name="West", server=True)
        self.assertEqual(200, code)
        self.assertEqual(self.house, added["houses"][-1]["hostingGreenhouseId"])
        self.assertEqual(409, self.invite("another-host-id", "collector-floor-uuid")[0])
        self.assertEqual(409, self.add("fourth")[0])
        self.assertEqual(200, self.plan("remove", houseId="collector-floor-uuid", server=True)[0])
        self.assertEqual(200, self.add("fourth")[0])
        self.assertEqual(403, self.plan("verify", server=True, productId="greenhouse_extra_1", purchaseToken=self.token)[0])
        self.assertEqual(200, self.plan("remove", houseId=self.house)[0])
        self.assertFalse(plans.monitoring_enabled(self.db, self.owner, self.house))

    def test_google_period_replacement_selection_cancel_and_restore(self):
        self.add("main")
        self.receipts[self.token] = self.receipt(2)
        self.assertEqual(2, self.pay(2)[1]["extraSlots"])
        self.add("west")
        self.add("east")
        pending = self.plan("pause", houseId="west")[1]
        self.assertEqual([True, True, True], [r["active"] for r in pending["houses"]])
        self.assertEqual(0, pending["houses"][1]["pauseAtEpochMs"])
        self.assertTrue(pending["houses"][1]["pauseRequested"])
        replacement = "fixture-deferred-new-token"
        self.receipts[replacement] = self.receipt(2, linkedPurchaseToken=self.token, startedAt=150,
                                                  nextProductId="greenhouse_extra_1", renews=True)
        # The chosen 1-slot SKU is not yet the effective subscription line.
        status, before = self.pay(1, replacement)
        self.assertEqual((200, 2, 1), (status, before["extraSlots"], before["nextExtraSlots"]))
        self.assertEqual([0, self.through*1000, 0], [r["pauseAtEpochMs"] for r in before["houses"]])
        # A delayed old PURCHASED or even refund cannot reinstate the old grant.
        self.assertEqual(before["revision"], self.pay(2)[1]["revision"])
        self.receipts[replacement] = self.receipt(1, startedAt=150, expiresAt=self.through + 3600)
        after = self.pay(1, replacement)[1]
        self.assertEqual([True, False, True], [r["active"] for r in after["houses"]])
        renamed = self.plan("rename", houseId="west", name="Propagation house")[1]["houses"][1]
        self.assertEqual(("Propagation house", False), (renamed["name"], renamed["active"]))
        self.assertEqual(404, self.plan("rename", houseId="missing", name="Other")[0])
        self.assertEqual(409, self.plan("resume", houseId="west")[0])
        self.receipts[replacement]["renews"] = False
        self.receipts[replacement]["subscriptionState"] = "SUBSCRIPTION_STATE_CANCELED"
        canceled = self.pay(1, replacement)[1]
        self.assertEqual(0, canceled["nextExtraSlots"])
        self.assertTrue(canceled["houses"][2]["active"])
        self.assertEqual((self.through + 3600)*1000, canceled["houses"][2]["pauseAtEpochMs"])
        self.receipts[replacement].update(active=False, subscriptionState="SUBSCRIPTION_STATE_EXPIRED")
        expired = self.pay(1, replacement)[1]
        self.assertEqual([True, False, False], [r["active"] for r in expired["houses"]])
        self.assertEqual(0, expired["extraSlots"])
        self.receipts[replacement] = self.receipt(1, startedAt=150)
        self.assertEqual(200, self.pay(1, replacement)[0])
        resumed = self.plan("resume", houseId="west")[1]
        self.assertEqual([True, True, False], [r["active"] for r in resumed["houses"]])

    def test_concurrent_initial_purchases_only_acknowledge_reserved_token(self):
        self.add("main")
        other = "purchase-on-other-play-phone"
        self.receipts[other] = self.receipt(2)  # same purchase start second
        acknowledgements = []
        def raced(product, token, **options):
            if not options and token == self.token and not acknowledgements:
                self.assertEqual(200, self.pay(2, other)[0])
            if options:
                acknowledgements.append(token)
            return self.receipts[token]
        with patch.object(plans, "verify_google_play_purchase", side_effect=raced):
            self.assertEqual((409, {"error":"greenhouse_plan_changed"}), self.pay())
            self.assertEqual((409, {"error":"greenhouse_plan_purchase_conflict"}), self.pay())
            self.assertEqual([other], acknowledgements)
            self.assertEqual(2, self.plan()[1]["extraSlots"])

    def test_confirmation_interruption_recovers_without_second_checkout(self):
        self.add("main")
        def interrupted(product, token, **options):
            if options:
                raise GooglePlayVerificationError("google_play_acknowledgement_failed")
            return self.receipts[token]
        with patch.object(plans, "verify_google_play_purchase", side_effect=interrupted):
            self.assertEqual(503, self.pay()[0])
            self.assertEqual(0, self.plan()[1]["extraSlots"])
        self.assertEqual(1, self.plan()[1]["extraSlots"])
        self.assertNotIn("confirmingToken", plans.read(self.db, self.owner))

    def test_owner_receipt_digest_and_abandoned_reservation(self):
        self.seed_directory()
        self.assertNotIn("purchaseTokenDigest", self.plan()[1])
        expected = hashlib.sha256(self.token.encode()).hexdigest()
        self.assertEqual(expected, self.pay()[1]["purchaseTokenDigest"])
        self.assertEqual(expected, self.plan()[1]["purchaseTokenDigest"])
        self.assertNotIn("purchaseTokenDigest", self.plan(server=True)[1])
        abandoned = "other-token-pending-verification"
        for failure in (self.receipt(active=False), GooglePlayVerificationError("google_play_purchase_invalid")):
            plans.save(self.db, {**plans.read(self.db, self.owner), "confirmingToken": abandoned})
            self.receipts[abandoned] = failure
            self.assertEqual(expected, self.plan()[1]["purchaseTokenDigest"])
            self.assertNotIn("confirmingToken", plans.read(self.db, self.owner))
        self.receipts[self.token]["active"] = False
        self.assertNotIn("purchaseTokenDigest", self.pay()[1])

    def test_wrong_owner_wrong_environment_pending_and_off(self):
        self.add("main")
        for fields in ({"accountBinding": "b"*64}, {"testPurchase": True}):
            self.receipts[self.token] = self.receipt(**fields)
            self.assertEqual(403, self.pay()[0])
        self.receipts[self.token] = self.receipt(active=False, expiresAt=0)
        self.assertEqual(0, self.pay()[1]["extraSlots"])
        self.assertEqual(409, self.add("east")[0])
        with patch.dict(os.environ, GREENHOUSE_PLAN_SALES_MODE="Off"):
            self.assertFalse(self.plan()[1]["checkoutAvailable"])
            self.assertEqual(403, self.pay()[0])
            self.assertEqual(409, self.add("east")[0])
        with patch.dict(os.environ, GREENHOUSE_PLAN_SALES_MODE="LicenseTest", PLAY_TEST_ACCOUNT_IDS=self.account):
            self.receipts[self.token] = self.receipt(testPurchase=True)
            self.assertTrue(self.plan()[1]["licenseTest"])
            self.assertEqual(1, self.pay()[1]["extraSlots"])
        self.assertEqual(0, self.plan()[1]["extraSlots"])

    def test_two_confirmations_for_last_slot_cannot_both_enroll_even_with_old_client(self):
        self.add("main")
        self.pay()
        first = self.invite("greenhouse-one")[1]
        second = self.invite("greenhouse-two")[1]
        # Both preflights succeeded. Exactly one final transaction consumes the slot.
        self.assertEqual(200, self.pair(first)[0])
        self.assertEqual(409, self.pair(second, id="server-other-123")[0])
        self.assertEqual(2, len(self.plan()[1]["houses"]))
        prior = plans.read(self.db, self.owner)
        stale = plans.write_params(prior)
        self.plan("pause", houseId="greenhouse-one")
        with self.assertRaises(ClientError):
            self.db.put_item(**stale)
        self.assertTrue(self.plan()[1]["houses"][1]["pauseRequested"])

    def test_outage_expiry_backup_history_and_alarms(self):
        self.add("main")
        self.pay()
        # Bind the existing test server as the paid greenhouse.
        update = plans.server_allocation(self.db, self.owner, self.house, None, "Paid", app.play_catalog)
        self.db.meta.client.transact_write_items(TransactItems=[update])
        backup = f"/v1/device/greenhouses/{self.house}/backup"
        self.assertEqual(204, self.api(backup, {"backup": {"schema": 2, "sensors": ["past"]}}, server=True)[0])
        self.receipts[self.token] = GooglePlayVerificationError("google_play_unavailable")
        with patch("greenhouse_plan.time.time", return_value=self.through - 1):
            self.assertEqual(1, self.plan()[1]["extraSlots"])
        with patch("greenhouse_plan.time.time", return_value=self.through):
            self.assertEqual(0, self.plan()[1]["extraSlots"])
            self.assertEqual(409, self.api(backup, {"backup": {"schema": 2}}, server=True)[0])
            held = self.api(f"/v1/greenhouses/{self.house}/backup", query={"temperatureOnly": "true"})[1]
            self.assertTrue(held["monitoringPaused"])
            self.assertEqual(["past"], held["backup"]["sensors"])
            self.assertEqual(204, self.api(f"/v1/device/devices/{self.server}/heartbeat", {
                "sensorConnectionHealthy": False, "criticalTemperature": True}, server=True)[0])
            self.assertEqual([], self.api("/v1/alerts")[1]["alerts"])
            self.assertTrue(self.plan()[1]["houses"][0]["active"])

    def test_snapshot_compression_is_transparent_to_viewer_and_restore(self):
        snapshot = {"schema": 2, "sensors": [{"serial": str(i), "temperature": 65} for i in range(100)],
                    "history": ["reading"] * 3000}
        path = f"/v1/greenhouses/{self.house}/backup"
        server_path = path.replace("/v1/", "/v1/device/")
        self.assertEqual(204, self.api(server_path, {"backup": snapshot}, server=True)[0])
        key = {"pk": "GREENHOUSE#" + self.house, "sk": "BACKUP"}
        item = self.db.get_item(Key=key)["Item"]
        self.assertNotIn("backupJson", item)
        self.assertLess(len(bytes(item["backupGzip"])), len(json.dumps(snapshot)) // 2)
        self.assertEqual(snapshot, self.api(path)[1]["backup"])
        self.assertEqual(snapshot, self.api(server_path, server=True)[1]["backup"])
        self.assertEqual({"schema": 2, "sensors": snapshot["sensors"]},
                         self.api(path, query={"temperatureOnly": "true"})[1]["backup"])
        self.assertEqual(413, self.api(server_path, {"backup": {"schema": 2, "history": "A"*350000}}, server=True)[0])
        self.assertEqual(snapshot, self.api(path)[1]["backup"])

    def test_new_token_notification_without_viewer_and_without_archive_sales(self):
        self.plan()  # Binds account before Play checkout; close the viewer here.
        message = {"packageName": "studio.junecity.greenhouse", "eventTimeMillis": str(int(time.time()*1000)),
                   "subscriptionNotification": {"purchaseToken": self.token, "notificationType": 4}}
        request = event("/v1/billing/google-play/notifications", body={
            "subscription": "project/subscription", "message": {"messageId": "closed",
            "data": base64.b64encode(json.dumps(message).encode()).decode()}})
        request["requestContext"]["authorizer"]["jwt"]["claims"] = {"iss": "https://accounts.google.com",
            "aud": "https://example.test/notifications", "email": "push@fixture.test", "email_verified": True}
        with patch.dict(os.environ, CAMERA_ARCHIVE_SALES_MODE="Off"), patch("play_notifications.verify_google_play_purchase",
                side_effect=GooglePlayVerificationError("google_play_product_mismatch")):
            self.assertEqual(200, app.handler(request, None)["statusCode"])
            granted = self.plan()[1]
            self.assertEqual(1, granted["extraSlots"])
            self.assertEqual(200, app.handler(request, None)["statusCode"])
            self.assertEqual(granted["revision"], self.plan()[1]["revision"])
        self.assertNotIn("Item", self.db.get_item(Key={"pk": "ACCOUNT#" + self.account, "sk": "CAMERA_STORAGE"}))

    def test_google_deferred_payload_selects_the_line_with_a_paid_period(self):
        def line(n, **details):
            return {"productId": f"greenhouse_extra_{n}", "offerDetails": {"basePlanId": "monthly"}, **details}
        payload = {"subscriptionState": "SUBSCRIPTION_STATE_ACTIVE", "lineItems": [
            line(2, expiryTime="2098-01-09T00:00:00.124Z", deferredItemReplacement={"productId": "greenhouse_extra_1"}),
            line(1, autoRenewingPlan={"autoRenewEnabled": True})]}
        before = parse_greenhouse_subscription(payload)
        self.assertEqual(("greenhouse_extra_2", "greenhouse_extra_1", 124),
                         (before["productId"], before["nextProductId"], before["expiresAtMillis"]%1000))
        payload["lineItems"] = [line(2, expiryTime="2000-01-09T00:00:00Z"),
                                line(1, expiryTime="2098-02-09T00:00:00Z", autoRenewingPlan={"autoRenewEnabled": True})]
        after = parse_greenhouse_subscription(payload)
        self.assertEqual(("greenhouse_extra_1", None, True), (after["productId"], after["nextProductId"], after["renews"]))
        for product in ("premium_monthly", "camera_storage_monthly", "greenhouse_extra_01"):
            with self.assertRaisesRegex(GooglePlayVerificationError, "mismatch"):
                parse_greenhouse_subscription({**payload, "lineItems": [{"productId": product}]})
