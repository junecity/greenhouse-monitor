import json
import base64
import hashlib
import uuid
from urllib.parse import urlparse

import boto3
import os
import time
import unittest
from datetime import datetime, timezone
from unittest.mock import patch
from botocore.exceptions import ClientError
from test_app import FakeTable, event, app
import camera_archive
from google_play_verifier import GooglePlayVerificationError
from test_alert_rules import GreenhouseFixture


class CameraArchiveTests(unittest.TestCase):
    def setUp(self):
        self.house = "greenhouse-one"
        self.device = "device-viewer"
        self.server = "device-server"
        self.table = FakeTable({
            ("USER#cognito-subject", "ACCOUNT"): {"tenantId": "tenant", "accountId": "account"},
            ("GREENHOUSE#"+self.house, "METADATA"): {"tenantId": "tenant", "accountId": "account", "serverDeviceId": self.server},
            ("ACCOUNT#account", "ENTITLEMENT"): {"plan": "PREMIUM", "videoAccessOverride": "COMPLIMENTARY"}
        })
        for device, role in [(self.device,"VIEWER_CLIENT"),(self.server,"GREENHOUSE_SERVER")]:
            self.table.items[("DEVICE#"+device,"METADATA")] = dict(tenantId="tenant", accountId="account",
                enabled=True, role=role, greenhouseId=self.house)
        self.start(patch.object(app,"table",self.table))
        self.start(patch.dict(os.environ, dict(CAMERA_ARCHIVE_BUCKET="private-fixture",PLAY_TEST_ACCOUNT_IDS="account",GOOGLE_PLAY_CREDENTIALS_SECRET_ID="fixture")))
        self.verify=self.start(patch.object(camera_archive,"verify_google_play_purchase"))
        self.verify.return_value=dict(active=True, testPurchase=True,expiresAt=int(time.time())+3600,
                                     accountBinding=app.play_test_checkout("account")["accountBinding"])
        self.s3=self.start(patch.object(camera_archive.boto3,"client")).return_value
        self.s3.generate_presigned_url.return_value="https://signed.invalid/fixture"

    def start(self, patcher):
        self.addCleanup(patcher.stop)
        return patcher.start()

    def call(self, action, device=None, body=None):
        return app.handler(event(f"/v1/greenhouses/{self.house}/recordings/{action}",
                                 "GET" if body is None else "POST",device_id=device or self.device,body=body),None)
    def grant(self):
        self.table.items[("ACCOUNT#account","CAMERA_STORAGE")]={"purchaseToken":"fixture-token-123456"}

    def test_separate_product_and_ownership(self):
        self.assertEqual(403,self.call("list")["statusCode"])
        result=json.loads(self.call("access")["body"])
        self.assertFalse(result["active"])
        self.assertEqual("camera_storage_monthly",result["checkout"]["productId"])
        self.assertEqual(200,self.call("verify",body={"purchaseToken":"fixture-token-123456"})["statusCode"])
        self.assertEqual("CAMERA_STORAGE",self.table.puts[0]["Item"]["sk"])
        self.verify.return_value["accountBinding"]="other-account"
        self.assertEqual(403,self.call("verify",body={"purchaseToken":"fixture-token-123456"})["statusCode"])

    def test_server_upload_and_viewer_playback_scoped_to_house(self):
        self.grant()
        body={"bytes":1000,"md5":"A"*22+"=="}
        self.assertEqual(404,self.call("upload",body=body)["statusCode"])
        self.assertEqual(400,self.call("upload",self.server,{**body,"bytes":9000000})["statusCode"])
        self.assertEqual(200,self.call("upload",self.server,body)["statusCode"])
        params=self.s3.generate_presigned_url.call_args.kwargs["Params"]
        self.assertEqual(1000,params["ContentLength"])
        self.assertTrue(params["Key"].startswith("movies/tenant/greenhouse-one/"))
        self.assertEqual(200,self.call("play",body={"id":params["Key"].split("/")[-1]})["statusCode"])
        self.assertEqual(200,self.call("delete",body={"id":params["Key"].split("/")[-1]})["statusCode"])
        self.s3.delete_object.assert_called_once_with(Bucket="private-fixture",Key=params["Key"])
        self.assertEqual(400,self.call("play",body={"id":"../other/file.mp4"})["statusCode"])
        self.table.items[("GREENHOUSE#"+self.house,"METADATA")]["tenantId"]="other"
        self.assertEqual(403,self.call("list")["statusCode"])

    def test_listing_retention_expired_playback_and_quota(self):
        self.grant()
        now = int(time.time())
        prefix = "movies/tenant/greenhouse-one/"
        current = f"{now}-" + "a"*32 + ".mp4"
        old = f"{now-8*86400}-" + "b"*32 + ".mp4"
        self.s3.get_paginator.return_value.paginate.return_value = [{"Contents":[
            {"Key": prefix+current, "LastModified":datetime.fromtimestamp(now, timezone.utc)},
            {"Key": prefix+old, "LastModified":datetime.fromtimestamp(now-8*86400, timezone.utc)}]}]
        self.assertEqual([current], [clip["id"] for clip in json.loads(self.call("list")["body"])["clips"]])
        self.assertEqual(410, self.call("play",body={"id":old})["statusCode"])
        error = ClientError({"Error":{"Code":"ConditionalCheckFailedException"}}, "UpdateItem")
        with patch.object(self.table, "update_item", side_effect=error):
            self.assertEqual(429,self.call("upload",self.server,{"bytes":150,"md5":"A"*22+"=="})["statusCode"])
        self.s3.generate_presigned_url.assert_not_called()

    def test_production_mode_requires_real_account_bound_purchase(self):
        with patch.dict(os.environ, {"CAMERA_ARCHIVE_SALES_MODE": "Production", "PLAY_TEST_ACCOUNT_IDS": ""}):
            self.assertEqual(403, self.call("verify", body={"purchaseToken":"fixture-token-123456"})["statusCode"])
            self.verify.return_value["testPurchase"] = False
            self.assertEqual(200, self.call("verify", body={"purchaseToken":"fixture-token-123456"})["statusCode"])
            self.assertFalse(self.verify.call_args.kwargs["require_test_purchase"])

    def test_revoked_replaced_disabled_or_expired(self):
        self.grant()
        self.table.items[("DEVICE#"+self.device,"METADATA")]["enabled"]=False
        self.assertEqual(403,self.call("list")["statusCode"])
        self.table.items[("GREENHOUSE#"+self.house,"METADATA")]["serverDeviceId"]="replacement"
        self.assertEqual(403,self.call("access",self.server)["statusCode"])
        self.table.items[("DEVICE#"+self.device,"METADATA")]["enabled"]=True
        self.verify.return_value["expiresAt"]=0
        self.assertEqual(403,self.call("list")["statusCode"])
        self.table.items[("ACCOUNT#account","ENTITLEMENT")]["videoAccessOverride"]="DISABLED"
        self.assertEqual(403,self.call("access")["statusCode"])

class ArchiveLifecycleTests(GreenhouseFixture):
    token = "existing-camera-archive-token"
    account = "customer-test"

    def setUp(self):
        super().setUp()
        self.bucket = "greenhouse-archive-test"
        self.s3 = boto3.client("s3", region_name="us-east-1")
        self.s3.create_bucket(Bucket=self.bucket)
        self.addCleanup(self.s3.close)
        env = patch.dict(os.environ, {"CAMERA_ARCHIVE_BUCKET": self.bucket, "CAMERA_ARCHIVE_SALES_MODE": "Production",
                                      "GOOGLE_PLAY_CREDENTIALS_SECRET_ID": "fixture"})
        env.start()
        self.addCleanup(env.stop)
        verify = patch.object(camera_archive, "verify_google_play_purchase")
        self.verify = verify.start()
        self.addCleanup(verify.stop)
        self.now = int(time.time())
        self.verify.return_value = dict(active=True, testPurchase=False, expiresAt=self.now + 3600,
                                        accountBinding=app.play_catalog(self.account, camera_archive.PRODUCT)["accountBinding"])
        self.grant_key = {"pk": "ACCOUNT#" + self.account, "sk": "CAMERA_STORAGE"}

    def recordings(self, action, body=None, server=False):
        prefix = "/v1/device" if server else "/v1"
        return self.api(f"{prefix}/greenhouses/{self.house}/recordings/{action}", body, server=server)

    def purchase(self):
        self.assertEqual((200, {"active": True}), self.recordings("verify", {"purchaseToken": self.token}))

    def test_eligible_viewer_registers_opaque_account_binding_before_checkout(self):
        access = self.recordings("access")[1]
        self.assertFalse(access["active"])
        binding = access["checkout"]["accountBinding"]
        key = {"pk": "CAMERA_BINDING#" + binding, "sk": "OWNER"}
        owner = self.db.get_item(Key=key)["Item"]
        self.assertEqual((self.account, "tenant-test"), (owner["accountId"], owner["tenantId"]))
        self.assertNotIn("purchaseToken", owner)
        self.assertIsNone(self.recordings("access", server=True)[1]["checkout"])
        self.assertFalse(self.recordings("access")[1]["active"])

    def test_registered_server_credential_and_legacy_enrollment_can_each_request_an_upload(self):
        self.purchase()
        body = {"bytes": 100, "md5": "A"*22+"=="}
        self.assertEqual(200, self.recordings("upload", body, server=True)[0])
        self.assertTrue(self.recordings("access")[1]["active"])
        legacy_route = f"/v1/greenhouses/{self.house}/recordings/upload"
        self.assertEqual((403, {"error": "device_credential_required"}), self.api(legacy_route, body, server=True))
        self.db.update_item(Key={"pk": "DEVICE#" + self.server, "sk": "METADATA"}, UpdateExpression="REMOVE deviceSecretHash")
        self.assertEqual(200, self.api(legacy_route, body, server=True)[0])

    def age_grant(self, seconds):
        self.db.update_item(Key=self.grant_key, UpdateExpression="SET checkedAt=:at",
                            ExpressionAttributeValues={":at": self.now - seconds})

    def test_outage_uses_recent_paid_period_and_never_extends_its_expiry(self):
        self.purchase()
        self.verify.side_effect = GooglePlayVerificationError("google_play_unavailable")
        self.age_grant(80)
        self.assertTrue(self.recordings("access")[1]["active"])
        # Outages do not advance the time of the last successful check.
        self.age_grant(301)
        self.assertEqual(503, self.recordings("list")[0])
        self.age_grant(80)
        self.db.update_item(Key=self.grant_key, UpdateExpression="SET paidThrough=:at",
                            ExpressionAttributeValues={":at": self.now - 1})
        self.assertEqual(503, self.recordings("play", {"id": f"{self.now}-" + "a"*32 + ".mp4"})[0])
        self.assertEqual(503, self.recordings("verify", {"purchaseToken": "different-valid-length-token"})[0])
        self.verify.side_effect = None
        self.verify.return_value["expiresAt"] = self.now + 7200
        self.assertTrue(self.recordings("access")[1]["active"])
        self.assertEqual(self.now + 7200, self.db.get_item(Key=self.grant_key)["Item"]["paidThrough"])

    def test_revocation_and_account_suspension_supersede_cached_access(self):
        self.purchase()
        self.assertFalse(self.recordings("access")[1]["checkout"]["licenseTest"])
        self.verify.assert_called_once()
        self.put("ACCOUNT#" + self.account, "METADATA", state="SUSPENDED")
        self.assertEqual(403, self.recordings("list")[0])
        self.put("ACCOUNT#" + self.account, "METADATA", state="ACTIVE")
        self.age_grant(65)
        self.verify.return_value["active"] = False
        self.assertEqual(403, self.recordings("list")[0])
        self.verify.side_effect = GooglePlayVerificationError("google_play_unavailable")
        self.assertFalse(self.recordings("access")[1]["active"])

    def test_late_verification_cannot_replace_a_new_purchase_with_an_old_one(self):
        self.purchase()
        self.age_grant(65)
        fresh = "a-new-camera-archive-token"
        def purchase_during_check(*args, **kwargs):
            self.verify.side_effect = None
            self.assertEqual((200, {"active": True}), self.recordings("verify", {"purchaseToken": fresh}))
            return {**self.verify.return_value, "active": False}
        self.verify.side_effect = purchase_during_check
        self.assertTrue(self.recordings("access")[1]["active"])
        self.assertEqual(fresh, self.db.get_item(Key=self.grant_key)["Item"]["purchaseToken"])

    def test_retry_after_lost_authorization_or_put_uses_one_slot_and_one_playable_clip(self):
        self.purchase()
        movie = b"fixture silent video"
        req = {"requestId": str(uuid.uuid4()), "bytes": len(movie),
               "md5": base64.b64encode(hashlib.md5(movie).digest()).decode()}
        quota = {"pk": self.grant_key["pk"], "sk": f"CAMERA_UPLOADS#{self.now//86400}"}
        self.db.put_item(Item={**quota, "requests": 23})
        code, permit = self.recordings("upload", req, server=True)
        self.assertEqual(200, code, permit)
        key = urlparse(permit["url"]).path.lstrip("/")
        code, retry = self.recordings("upload", req, server=True)
        self.assertEqual(200, code, retry)
        self.assertEqual(key, urlparse(retry["url"]).path.lstrip("/"))
        self.assertEqual(409, self.recordings("upload", {**req, "bytes": len(movie)+1}, server=True)[0])
        self.assertEqual(429, self.recordings("upload", {**req, "requestId": str(uuid.uuid4())}, server=True)[0])
        self.s3.put_object(Bucket=self.bucket, Key=key, Body=movie, ContentMD5=req["md5"], ContentType="video/mp4")
        self.assertEqual((200, {"alreadyUploaded": True}), self.recordings("upload", req, server=True))
        code, library = self.recordings("list")
        self.assertEqual(200, code)
        self.assertEqual(1, len(library["clips"]))
        clip = library["clips"][0]["id"]
        self.assertEqual(key, urlparse(self.recordings("play", {"id": clip})[1]["url"]).path.lstrip("/"))
        self.assertEqual(200, self.recordings("delete", {"id": clip})[0])
        # A PUT authorized before deletion can still finish within the signed URL lifetime.
        self.s3.put_object(Bucket=self.bucket, Key=key, Body=movie)
        self.assertEqual(410, self.recordings("upload", req, server=True)[0])
        self.assertEqual(410, self.recordings("play", {"id": clip})[0])
        self.assertEqual(24, self.db.get_item(Key=quota)["Item"]["requests"])
        self.assertEqual([], self.recordings("list")[1]["clips"])
