"""One explicitly requested archive clip at a time on an enrolled camera server."""
import time
import uuid

from botocore.exceptions import ClientError

from domain import AccessDenied

MIN_SERVER_BUILD = 92
QUEUE_SECONDS = 180
CAPTURE_SECONDS = 600


def reason(server, principal, house_id, monitoring):
    if (not server or server.get("role") != "GREENHOUSE_SERVER" or not server.get("enabled")
            or server.get("communicationSuspended") or not server.get("deviceSecretHash")
            or server.get("accountId") != principal.account_id or server.get("tenantId") != principal.tenant_id
            or server.get("greenhouseId") != house_id):
        return "server_unavailable"
    if not monitoring:
        return "greenhouse_paused"
    if not 0 <= time.time() - int(server.get("lastHeartbeat", 0)) < QUEUE_SECONDS:
        return "offline"
    if int(server.get("appVersionCode") or 0) < MIN_SERVER_BUILD:
        return "update_server"
    return "ready" if server.get("remoteRecordingReady") is True else "camera_unavailable"


def pending_hint(device, now):
    return bool(device.get("archiveCommandState") == "queued" and device.get("archiveCommandUntil", 0) > now)


def request_uuid(value):
    try:
        return str(uuid.UUID(value)) if isinstance(value, str) else None
    except ValueError:
        return None


def command_key(account_key, house_id, request_id):
    return {"pk": account_key, "sk": f"CAMERA_COMMAND#{house_id}#{request_id}"}


def command_status(item, now):
    if item["status"] in {"queued", "recording"} and item["deadline"] <= now:
        return "expired"
    return item["status"]


def summary(item, now):
    return {"status": command_status(item, now), "requestId": item["requestId"]}


def stale_write(error):
    reasons = error.response.get("CancellationReasons") or []
    return error.response["Error"]["Code"] == "TransactionCanceledException" and any(
        r.get("Code") == "ConditionalCheckFailed" for r in reasons)


def handle(table, s3, bucket, prefix, principal, house_id, host_id, host, server, viewer, action, body,
           through, availability, upload_key_for, response):
    if ((action == "record" and not viewer) or
            (action in {"poll-record", "finish-record"} and not server)):
        raise AccessDenied("device_not_authorized")
    now = int(time.time())
    account_key = "ACCOUNT#" + principal.account_id
    request_id = request_uuid(host.get("archiveCommandId") if action == "poll-record" else body.get("requestId"))
    if not request_id:
        return response(200, {}) if action == "poll-record" else response(400, {"error": "invalid_request_id"})
    key = command_key(account_key, house_id, request_id)
    job = table.get_item(Key=key, ConsistentRead=True).get("Item")
    if job and (job.get("tenantId") != principal.tenant_id or job.get("serverDeviceId") != host_id
                or (server and job.get("enrollment") != host.get("deviceSecretHash"))):
        return response(200, {"status": "expired", "requestId": request_id}) if viewer else response(403, {"error": "recording_enrollment_changed"})
    if action == "record-status":
        if not job:
            return response(404, {"error": "recording_not_found"})
        result = summary(job, now)
        if result["status"] in {"queued", "recording"} and (through <= now or availability in {"greenhouse_paused", "server_unavailable"}):
            result["status"] = "expired"
        return response(200, result)
    if action == "record" and job:
        return response(200, summary(job, now))
    if action == "finish-record":
        result = body.get("status")
        if not job or result not in {"uploaded", "failed"}:
            return response(400, {"error": "invalid_recording_result"})
        if command_status(job, now) == result:
            return response(200, summary(job, now))
        if command_status(job, now) != "recording":
            return response(409, {"error": "recording_inactive"})
    if action == "poll-record" and (not pending_hint(host, now) or not job or command_status(job, now) != "queued"):
        return response(200, {})
    ready = availability == "ready" or (action == "finish-record" and availability in {"offline", "camera_unavailable"})
    if (through <= now or not ready) and not (action == "finish-record" and result == "failed"):
        return response(403, {"error": "camera_storage_required"}) if through <= now else response(409, {"error": availability})
    # Bind all changes to this exact enrollment. Re-pairing rotates the secret even on the same phone.
    device_key = {"pk": "DEVICE#" + host_id, "sk": "METADATA"}
    identity = {"TableName": table.name, "Key": device_key,
                "ConditionExpression": "deviceSecretHash=:generation AND greenhouseId=:house AND enabled=:yes "
                                       "AND (attribute_not_exists(communicationSuspended) OR communicationSuspended=:no)",
                "ExpressionAttributeValues": {":generation": host["deviceSecretHash"], ":house": house_id,
                                              ":yes": True, ":no": False}}
    if action == "record":
        deadline = min(now + QUEUE_SECONDS, through)
        item = {**key, "requestId": request_id, "serverDeviceId": host_id, "enrollment": host["deviceSecretHash"],
                "tenantId": principal.tenant_id, "status": "queued", "deadline": deadline, "expiresAt": now + 7*86400}
        identity["ConditionExpression"] += (" AND (attribute_not_exists(archiveCommandUntil) OR archiveCommandUntil<=:now)"
                                            " AND remoteRecordingReady=:yes AND lastHeartbeat>:cutoff")
        identity.update(UpdateExpression="SET archiveCommandUntil=:until, archiveCommandId=:id, archiveCommandState=:queued")
        identity["ExpressionAttributeValues"].update({":now": now, ":until": deadline, ":id": request_id,
                                                     ":queued": "queued", ":cutoff": now-QUEUE_SECONDS})
        changes = [{"Put": {"TableName": table.name, "Item": item, "ConditionExpression": "attribute_not_exists(pk)"}}]
    else:
        # A recording is never claimed twice, even when an HTTP response is lost.
        poll = action == "poll-record"
        expected = "queued" if poll else "recording"
        new_state = "recording" if poll else result
        deadline = min(now + CAPTURE_SECONDS, through) if poll else now
        if new_state == "uploaded":
            receipt = table.get_item(Key=upload_key_for(account_key, house_id, uuid.UUID(request_id).hex), ConsistentRead=True).get("Item", {})
            if receipt.get("deviceId") != host_id or receipt.get("deleted"):
                return response(409, {"error": "upload_incomplete"})
            clip = prefix + f"{receipt['createdAt']}-{uuid.UUID(request_id).hex}.mp4"
            try:
                stored = s3.head_object(Bucket=bucket, Key=clip)
            except ClientError as error:
                if error.response["Error"]["Code"] not in {"404", "NoSuchKey", "NotFound"}:
                    raise
                return response(409, {"error": "upload_incomplete"})
            if stored["ContentLength"] != receipt["bytes"]:
                return response(409, {"error": "upload_incomplete"})
        changes = [{"Update": {"TableName": table.name, "Key": key,
                    "UpdateExpression": "SET #status=:next, deadline=:until",
                    "ConditionExpression": "#status=:prior AND deadline>:now AND enrollment=:generation",
                    "ExpressionAttributeNames": {"#status": "status"},
                    "ExpressionAttributeValues": {":next": new_state, ":until": deadline, ":prior": expected,
                                                  ":now": now, ":generation": host["deviceSecretHash"]}}}]
        identity["ConditionExpression"] += " AND archiveCommandId=:id AND archiveCommandState=:prior AND archiveCommandUntil>:now"
        identity["ExpressionAttributeValues"].update({":id": request_id, ":prior": expected, ":now": now})
        if poll:
            identity["ConditionExpression"] += " AND remoteRecordingReady=:yes"
            identity["UpdateExpression"] = "SET archiveCommandUntil=:until, archiveCommandState=:next"
            identity["ExpressionAttributeValues"].update({":until": deadline, ":next": new_state})
        else:
            identity["UpdateExpression"] = "REMOVE archiveCommandUntil, archiveCommandId, archiveCommandState"
    changes.append({"Update": identity})
    try:
        table.meta.client.transact_write_items(TransactItems=changes)
    except ClientError as error:
        if not stale_write(error):
            raise
        if action == "poll-record":
            return response(200, {})
        latest = table.get_item(Key=key, ConsistentRead=True).get("Item")
        if latest and (action == "record" or latest.get("status") == body.get("status")):
            return response(200, summary(latest, now))
        return response(409, {"error": "recording_busy" if action == "record" else "recording_inactive"})
    if action == "poll-record":
        return response(200, {"requestId": request_id})
    return response(200, {"requestId": request_id, "status": "queued" if action == "record" else result})
