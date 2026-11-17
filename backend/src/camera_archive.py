"""Private, on-demand camera storage. Server enrollment writes; an authorized account reads."""
import json
import remote_recording
import os
import re
import time
import uuid

import boto3
from botocore.config import Config
from botocore.exceptions import ClientError

from domain import AccessDenied
from google_play_verifier import GooglePlayVerificationError, verify_google_play_purchase

PRODUCT = "camera_storage_monthly"
MAX_BYTES = 8 * 1024 * 1024
RETENTION_DAYS = 7
RECHECK_SECONDS = 60
OUTAGE_ALLOWANCE_SECONDS = 300
INVALID_PURCHASE = {"google_play_purchase_invalid", "google_play_product_mismatch", "purchase_not_eligible"}
CLIP_NAME = re.compile(r"([0-9]{10})-([a-f0-9]{32})\.mp4")
ANY_REVISION = object()


class ArchiveGrantChanged(RuntimeError):
    """An overlapping receipt update requires a fresh subscription ownership decision."""


def register_checkout_binding(table, catalog, principal):
    """Associate Play's opaque account ID before checkout so a closed phone can receive a completed purchase."""
    table.update_item(
        Key={"pk": f"CAMERA_BINDING#{catalog['accountBinding']}", "sk": "OWNER"},
        UpdateExpression="SET accountId=:account,tenantId=:tenant",
        ConditionExpression="attribute_not_exists(pk) OR (accountId=:account AND tenantId=:tenant)",
        ExpressionAttributeValues={":account": principal.account_id, ":tenant": principal.tenant_id},
    )


def cached_period(grant, catalog, testing, now, max_age):
    checked = int(grant.get("checkedAt", 0))
    if (grant.get("accountBinding") != catalog["accountBinding"] or grant.get("testPurchase") != testing
            or not 0 <= now - checked < max_age):
        return None
    through = int(grant.get("paidThrough", 0))
    # A paid period that just ended must be looked up again in case it renewed.
    return through if through == 0 or through > now else None


def paid_period(table, grant_key, catalog, testing, requested_token=None, *, purchasing=False, expected_revision=ANY_REVISION):
    """Return the verified paid-through time, or zero. Never extend the expiry returned by Play."""
    if not catalog:
        return 0
    grant = table.get_item(Key=grant_key, ConsistentRead=True).get("Item", {})
    if expected_revision is not ANY_REVISION and grant.get("revision") != expected_revision:
        raise ArchiveGrantChanged()
    token = requested_token if purchasing else grant.get("purchaseToken")
    if not isinstance(token, str) or not 16 <= len(token) <= 4096:
        return 0
    now = int(time.time())
    if not purchasing:
        cached = cached_period(grant, catalog, testing, now, RECHECK_SECONDS)
        if cached is not None:
            return cached
    started_at = None
    try:
        verified = verify_google_play_purchase(PRODUCT, token, account_binding=catalog["accountBinding"],
                                               require_test_purchase=testing)
        started_at = verified.get("startedAt")
        through = int(verified["expiresAt"]) if (verified.get("active") and verified.get("testPurchase") == testing
                  and verified.get("accountBinding") == catalog["accountBinding"]) else 0
    except GooglePlayVerificationError as error:
        if error.safe_code not in INVALID_PURCHASE:
            if not purchasing and error.safe_code == "google_play_unavailable":
                latest = table.get_item(Key=grant_key, ConsistentRead=True).get("Item", {})
                known = cached_period(latest, catalog, testing, int(time.time()), OUTAGE_ALLOWANCE_SECONDS)
                if known:
                    return known
            raise
        through = 0
    now = int(time.time())
    through = through if through > now else 0
    if not through and purchasing and token != grant.get("purchaseToken"):
        return 0
    item = {**grant_key, "purchaseToken": token, "accountBinding": catalog["accountBinding"], "testPurchase": testing,
            "paidThrough": through, "checkedAt": now, "revision": uuid.uuid4().hex}
    if started_at is not None:
        item["startedAt"] = started_at
    elif token == grant.get("purchaseToken") and grant.get("startedAt") is not None:
        item["startedAt"] = grant["startedAt"]
    condition = {"ConditionExpression": "attribute_not_exists(revision)"}
    if "revision" in grant:
        condition = {"ConditionExpression": "revision=:previous",
                     "ExpressionAttributeValues": {":previous": grant["revision"]}}
    try:
        table.put_item(Item=item, **condition)
        return through
    except ClientError as error:
        if error.response["Error"]["Code"] != "ConditionalCheckFailedException":
            raise
        if expected_revision is not ANY_REVISION:
            raise ArchiveGrantChanged() from error
        winner = table.get_item(Key=grant_key, ConsistentRead=True).get("Item", {})
        if not purchasing or winner.get("purchaseToken") == token:
            current = cached_period(winner, catalog, testing, int(time.time()), RECHECK_SECONDS)
            if current is not None:
                return current
        raise


def recording_request(account_key, house_id, request_id):
    return {"pk": account_key, "sk": f"CAMERA_RECORDING#{house_id}#{request_id}"}


def deleted_clips(table, account_key, house_id):
    """Hide a deleted clip even if its initial, short-lived signed PUT arrives after deletion."""
    params = {
        "KeyConditionExpression": "pk=:account AND begins_with(sk,:prefix)",
        "FilterExpression": "deleted=:yes",
        "ExpressionAttributeValues": {":account": account_key, ":prefix": recording_request(account_key, house_id, "")["sk"], ":yes": True},
        "ProjectionExpression": "sk,createdAt",
        "ConsistentRead": True,
    }
    removed = set()
    while True:
        page = table.query(**params)
        for item in page["Items"]:
            removed.add(f"{item['createdAt']}-{item['sk'].rsplit('#', 1)[1]}.mp4")
        if not page.get("LastEvaluatedKey"):
            return removed
        params["ExclusiveStartKey"] = page["LastEvaluatedKey"]


def upload_permission(s3, table, account_key, house_id, device_id, bucket, prefix, body, through, response):
    size = body.get("bytes")
    if type(size) is not int or not 0 < size <= MAX_BYTES:
        return response(400, {"error": "invalid_clip_size"})
    checksum = body.get("md5")
    if not isinstance(checksum, str) or not re.fullmatch(r"[A-Za-z0-9+/]{22}==", checksum):
        return response(400, {"error": "invalid_checksum"})
    now = int(time.time())
    quota = {
        "Key": {"pk": account_key, "sk": f"CAMERA_UPLOADS#{now//86400}"},
        "UpdateExpression": "SET #expiry=:ttl ADD #requests :one",
        "ConditionExpression": "attribute_not_exists(#requests) OR #requests < :limit",
        "ExpressionAttributeNames": {"#expiry": "expiresAt", "#requests": "requests"},
        "ExpressionAttributeValues": {":ttl": now+172800, ":one": 1, ":limit": 24},
    }
    if "requestId" in body:
        if not isinstance(body["requestId"], str):
            return response(400, {"error": "invalid_request_id"})
        try:
            request_id = uuid.UUID(body["requestId"]).hex
        except ValueError:
            return response(400, {"error": "invalid_request_id"})
        request_key = recording_request(account_key, house_id, request_id)
        receipt = table.get_item(Key=request_key, ConsistentRead=True).get("Item")
        if not receipt:
            new = {**request_key, "createdAt": now, "deviceId": device_id, "bytes": size, "md5": checksum,
                   "expiresAt": now + RETENTION_DAYS * 86400}
            try:
                table.meta.client.transact_write_items(TransactItems=[
                    {"Put": {"TableName": table.name, "Item": new, "ConditionExpression": "attribute_not_exists(pk)"}},
                    {"Update": {"TableName": table.name, **quota}},
                ])
            except ClientError as error:
                if error.response["Error"]["Code"] != "TransactionCanceledException":
                    raise
                # The other phone request may have committed even if this retry hit the daily cap.
                receipt = table.get_item(Key=request_key, ConsistentRead=True).get("Item")
                if not receipt:
                    reasons = error.response.get("CancellationReasons", [])
                    if len(reasons) > 1 and reasons[1].get("Code") == "ConditionalCheckFailed":
                        return response(429, {"error": "daily_upload_limit"})
                    raise
        created = int(receipt["createdAt"]) if receipt else now
        key = prefix + f"{created}-{request_id}.mp4"
        if receipt:
            if receipt.get("deleted") or created + RETENTION_DAYS * 86400 <= now:
                return response(410, {"error": "clip_expired_or_deleted"})
            if (receipt.get("deviceId"), receipt.get("bytes"), receipt.get("md5")) != (device_id, size, checksum):
                return response(409, {"error": "clip_request_conflict"})
            try:
                s3.head_object(Bucket=bucket, Key=key)
            except ClientError as error:
                if error.response["Error"]["Code"] not in {"404", "NoSuchKey", "NotFound"}:
                    raise
            else:
                return response(200, {"alreadyUploaded": True})
    else:
        # Installed clients without a persistent recording ID have the original per-attempt quota.
        try:
            table.update_item(**quota)
        except ClientError as error:
            if error.response["Error"]["Code"] == "ConditionalCheckFailedException":
                return response(429, {"error": "daily_upload_limit"})
            raise
        created = now
        key = prefix + f"{created}-{uuid.uuid4().hex}.mp4"
    lifetime = min(120, through - now, created + RETENTION_DAYS * 86400 - now)
    if lifetime <= 0:
        return response(403, {"error": "camera_storage_required"})
    url = s3.generate_presigned_url("put_object", Params={"Bucket": bucket, "Key": key, "ContentType": "video/mp4",
                                    "ContentLength": size, "ContentMD5": checksum}, ExpiresIn=lifetime)
    return response(200, {"url": url})


def archive(event, principal, house_id, action, method, *, device_authenticated, table, get_owned, checkout_for, catalog_for, response, monitoring_enabled):
    device = get_owned("DEVICE", principal.device_id, principal.tenant_id)
    house = get_owned("GREENHOUSE", house_id, principal.tenant_id)
    server = device.get("role") == "GREENHOUSE_SERVER" and house.get("serverDeviceId") == principal.device_id and device.get("greenhouseId") == house_id
    viewer = device.get("role") == "VIEWER_CLIENT"
    if (not (server or viewer) or not device.get("enabled") or device.get("communicationSuspended")
            or house.get("enabled") is False or device.get("accountId") != principal.account_id or house.get("accountId") != principal.account_id):
        raise AccessDenied("device_not_authorized")
    if server and device.get("deviceSecretHash") and not device_authenticated:
        raise AccessDenied("device_credential_required")
    policy = table.get_item(Key={"pk": f"ACCOUNT#{principal.account_id}", "sk": "ENTITLEMENT"}, ConsistentRead=True).get("Item", {})
    if policy.get("videoAccessOverride") == "DISABLED":
        raise AccessDenied("camera_disabled")
    bucket = os.environ.get("CAMERA_ARCHIVE_BUCKET")
    production = os.environ.get("CAMERA_ARCHIVE_SALES_MODE") == "Production"
    catalog = checkout_for(principal.account_id)
    if production:
        catalog = catalog_for(principal.account_id, PRODUCT) if os.environ.get("GOOGLE_PLAY_CREDENTIALS_SECRET_ID") else None
    catalog = {**catalog, "productId": PRODUCT, "licenseTest": not production} if catalog and bucket else None
    grant_key = {"pk": f"ACCOUNT#{principal.account_id}", "sk": "CAMERA_STORAGE"}
    body = json.loads(event.get("body") or "{}")
    purchasing = action == "verify" and method == "POST" and viewer
    through = paid_period(table, grant_key, catalog, not production, body.get("purchaseToken"), purchasing=purchasing)
    active = through > time.time()
    commands = {"record", "poll-record", "record-status", "finish-record"}
    if action in commands or action == "access":
        host_id = house.get("serverDeviceId")
        host = (device if server else table.get_item(Key={"pk": "DEVICE#" + host_id, "sk": "METADATA"},
                                                    ConsistentRead=True).get("Item", {})) if host_id else {}
        availability = remote_recording.reason(host, principal, house_id, monitoring_enabled(table, principal, house_id))
    if action == "access" and method == "GET":
        if catalog and viewer:
            register_checkout_binding(table, catalog, principal)
        return response(200, {"active": active, "checkout": catalog if viewer else None,
                              "remoteRecordingAvailable": availability == "ready", "remoteRecordingReason": availability})
    prefix = f"movies/{principal.tenant_id}/{house_id}/"
    if action in commands and method == "POST":
        if server and (not device_authenticated or not device.get("deviceSecretHash")):
            raise AccessDenied("device_credential_required")
        s3 = boto3.client("s3", config=Config(signature_version="s3v4")) if action == "finish-record" else None
        return remote_recording.handle(table, s3, bucket, prefix, principal, house_id, host_id, host, server, viewer,
                                       action, body, through, availability, recording_request, response)
    if not active:
        raise AccessDenied("camera_storage_required")
    if purchasing:
        return response(200, {"active": True})
    s3 = boto3.client("s3", config=Config(signature_version="s3v4"))
    if action == "list" and method == "GET":
        clips = []
        cutoff = time.time() - RETENTION_DAYS * 86400
        for page in s3.get_paginator("list_objects_v2").paginate(Bucket=bucket, Prefix=prefix):
            for obj in page.get("Contents", []):
                clip = obj["Key"][len(prefix):]
                match = CLIP_NAME.fullmatch(clip)
                if match and int(match[1]) > cutoff:
                    clips.append({"id": clip, "time": int(match[1]) * 1000})
        if clips:
            removed = deleted_clips(table, grant_key["pk"], house_id)
            clips = [clip for clip in clips if clip["id"] not in removed]
        return response(200, {"clips": sorted(clips, key=lambda x: x["time"], reverse=True)})
    if action == "upload" and method == "POST" and server:
        if not monitoring_enabled(table, principal, house_id):
            raise AccessDenied("greenhouse_paused")
        request_id = remote_recording.request_uuid(body.get("requestId"))
        if request_id:
            job = table.get_item(Key=remote_recording.command_key(grant_key["pk"], house_id, request_id), ConsistentRead=True).get("Item")
            if job and (not device_authenticated or job.get("enrollment") != device.get("deviceSecretHash") or
                        remote_recording.command_status(job, int(time.time())) != "recording"):
                return response(409, {"error": "recording_inactive"})
        return upload_permission(s3, table, grant_key["pk"], house_id, principal.device_id, bucket,
                                 prefix, body, through, response)
    if action in {"play", "delete"} and method == "POST":
        clip = body.get("id")
        match = CLIP_NAME.fullmatch(clip) if isinstance(clip, str) else None
        if not match:
            return response(400, {"error": "invalid_clip"})
        now = int(time.time())
        created = int(match[1])
        remaining = created + RETENTION_DAYS * 86400 - now
        if remaining <= 0:
            return response(410, {"error": "clip_expired"})
        request_key = recording_request(grant_key["pk"], house_id, match[2])
        if action == "delete":
            try:
                table.update_item(Key=request_key, UpdateExpression="SET deleted=:yes",
                                  ConditionExpression="createdAt=:created", ExpressionAttributeValues={":yes": True, ":created": created})
            except ClientError as error:
                if error.response["Error"]["Code"] != "ConditionalCheckFailedException":
                    raise
            s3.delete_object(Bucket=bucket, Key=prefix+clip)
            return response(200, {"deleted": True})
        receipt = table.get_item(Key=request_key, ConsistentRead=True).get("Item", {})
        if receipt.get("deleted") and receipt.get("createdAt") == created:
            return response(410, {"error": "clip_deleted"})
        lifetime = min(180, remaining, through - now)
        if lifetime <= 0:
            return response(403, {"error": "camera_storage_required"})
        return response(200, {"url": s3.generate_presigned_url("get_object", Params={"Bucket": bucket, "Key": prefix+clip}, ExpiresIn=lifetime)})
    return response(404, {"error": "not_found"})
