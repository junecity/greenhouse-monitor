"""Private, on-demand camera storage. Server enrollment writes; an authorized account reads."""
import json
import os
import re
import time
import uuid

import boto3
from botocore.config import Config
from botocore.exceptions import ClientError

from domain import AccessDenied
from google_play_verifier import verify_google_play_purchase

PRODUCT = "camera_storage_monthly"
MAX_BYTES = 8 * 1024 * 1024
RETENTION_DAYS = 7


def archive(event, principal, house_id, action, method, *, table, get_owned, checkout_for, catalog_for, response):
    device = get_owned("DEVICE", principal.device_id, principal.tenant_id)
    house = get_owned("GREENHOUSE", house_id, principal.tenant_id)
    server = device.get("role") == "GREENHOUSE_SERVER" and house.get("serverDeviceId") == principal.device_id and device.get("greenhouseId") == house_id
    viewer = device.get("role") == "VIEWER_CLIENT"
    if (not (server or viewer) or not device.get("enabled") or device.get("communicationSuspended")
            or house.get("enabled") is False or device.get("accountId") != principal.account_id or house.get("accountId") != principal.account_id):
        raise AccessDenied("device_not_authorized")
    policy = table.get_item(Key={"pk": f"ACCOUNT#{principal.account_id}", "sk": "ENTITLEMENT"}, ConsistentRead=True).get("Item", {})
    if policy.get("videoAccessOverride") == "DISABLED":
        raise AccessDenied("camera_disabled")
    bucket = os.environ.get("CAMERA_ARCHIVE_BUCKET")
    production = os.environ.get("CAMERA_ARCHIVE_SALES_MODE") == "Production"
    catalog = checkout_for(principal.account_id)
    if production:
        catalog = catalog_for(principal.account_id, PRODUCT) if os.environ.get("GOOGLE_PLAY_CREDENTIALS_SECRET_ID") else None
    catalog = {**catalog, "productId": PRODUCT} if catalog and bucket else None
    grant_key = {"pk": f"ACCOUNT#{principal.account_id}", "sk": "CAMERA_STORAGE"}
    body = json.loads(event.get("body") or "{}")
    purchasing = action == "verify" and method == "POST" and viewer
    grant = table.get_item(Key=grant_key, ConsistentRead=True).get("Item", {})
    token = body.get("purchaseToken") if purchasing else grant.get("purchaseToken")
    active = False
    if catalog and isinstance(token, str) and 16 <= len(token) <= 4096:
        result = verify_google_play_purchase(PRODUCT, token, account_binding=catalog["accountBinding"], require_test_purchase=not production)
        active = bool(result.get("active") and result.get("testPurchase") == (not production) and result.get("accountBinding") == catalog["accountBinding"] and result["expiresAt"] > time.time())
    if action == "access" and method == "GET":
        return response(200, {"active": active, "checkout": catalog if viewer else None})
    if not active:
        raise AccessDenied("camera_storage_required")
    if purchasing:
        table.put_item(Item={**grant_key, "purchaseToken": token})
        return response(200, {"active": True})
    prefix = f"movies/{principal.tenant_id}/{house_id}/"
    s3 = boto3.client("s3", config=Config(signature_version="s3v4"))
    if action == "list" and method == "GET":
        clips = []
        cutoff = time.time() - RETENTION_DAYS * 86400
        for page in s3.get_paginator("list_objects_v2").paginate(Bucket=bucket, Prefix=prefix):
            for obj in page.get("Contents", []):
                if obj["LastModified"].timestamp() > cutoff:
                    clips.append({"id": obj["Key"][len(prefix):], "time": int(obj["LastModified"].timestamp()*1000)})
        return response(200, {"clips": sorted(clips, key=lambda x: x["time"], reverse=True)})
    if action == "upload" and method == "POST" and server:
        size = body.get("bytes")
        if type(size) is not int or not 0 < size <= MAX_BYTES:
            return response(400, {"error": "invalid_clip_size"})
        checksum = str(body.get("md5", ""))
        if not re.fullmatch(r"[A-Za-z0-9+/]{22}==", checksum):
            return response(400, {"error": "invalid_checksum"})
        now = int(time.time())
        try:
            table.update_item(Key={"pk": grant_key["pk"], "sk": f"CAMERA_UPLOADS#{now//86400}"},
                              UpdateExpression="SET #expiry=:ttl ADD #requests :one",
                              ConditionExpression="attribute_not_exists(#requests) OR #requests < :limit",
                              ExpressionAttributeNames={"#expiry": "expiresAt", "#requests": "requests"},
                              ExpressionAttributeValues={":ttl": now+172800, ":one": 1, ":limit": 24})
        except ClientError as error:
            if error.response["Error"]["Code"] == "ConditionalCheckFailedException":
                return response(429, {"error": "daily_upload_limit"})
            raise
        key = prefix + f"{now}-{uuid.uuid4().hex}.mp4"
        url = s3.generate_presigned_url("put_object", Params={"Bucket": bucket, "Key": key,
                "ContentType": "video/mp4", "ContentLength": size, "ContentMD5": checksum}, ExpiresIn=120)
        return response(200, {"url": url})
    if action in {"play", "delete"} and method == "POST":
        clip = body.get("id", "")
        if not isinstance(clip,str) or not re.fullmatch(r"[0-9]{10}-[a-f0-9]{32}\.mp4", clip):
            return response(400, {"error": "invalid_clip"})
        if int(clip.split("-")[0]) <= time.time() - RETENTION_DAYS * 86400:
            return response(410, {"error": "clip_expired"})
        if action == "delete":
            s3.delete_object(Bucket=bucket, Key=prefix+clip)
            return response(200, {"deleted": True})
        return response(200, {"url": s3.generate_presigned_url("get_object", Params={"Bucket": bucket, "Key": prefix+clip}, ExpiresIn=180)})
    return response(404, {"error": "not_found"})
