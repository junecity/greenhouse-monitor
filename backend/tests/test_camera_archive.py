import json
import os
import time
import unittest
from datetime import datetime, timezone
from unittest.mock import patch
from botocore.exceptions import ClientError
from test_app import FakeTable, event, app
import camera_archive


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
