import json
import os
from datetime import datetime, timezone

import boto3
from boto3.dynamodb.conditions import Key

from domain import AccessDenied, Principal, authorize_stream


table = boto3.resource("dynamodb").Table(os.environ["TABLE_NAME"])


def response(status: int, body: dict):
    return {"statusCode": status, "headers": {"content-type": "application/json", "cache-control": "no-store"}, "body": json.dumps(body)}


def principal_from(event) -> Principal:
    claims = event.get("requestContext", {}).get("authorizer", {}).get("jwt", {}).get("claims", {})
    subject = claims.get("sub")
    if not subject:
        raise AccessDenied("unauthenticated")
    account = table.get_item(Key={"pk": f"USER#{subject}", "sk": "ACCOUNT"}, ConsistentRead=True).get("Item")
    if not account:
        raise AccessDenied("account_missing")
    device_id = event.get("headers", {}).get("x-greenhouse-device-id", "")
    if not device_id:
        raise AccessDenied("device_missing")
    return Principal(account["accountId"], account["tenantId"], device_id)


def get_owned(kind: str, object_id: str, tenant_id: str):
    item = table.get_item(Key={"pk": f"{kind}#{object_id}", "sk": "METADATA"}, ConsistentRead=True).get("Item")
    if not item or item.get("tenantId") != tenant_id:
        raise AccessDenied("not_found")
    return item


def create_stream_session(event, principal: Principal, greenhouse_id: str):
    body = json.loads(event.get("body") or "{}")
    viewer = get_owned("DEVICE", principal.device_id, principal.tenant_id)
    greenhouse = get_owned("GREENHOUSE", greenhouse_id, principal.tenant_id)
    server = get_owned("DEVICE", greenhouse["serverDeviceId"], principal.tenant_id)
    entitlement = table.get_item(Key={"pk": f"ACCOUNT#{principal.account_id}", "sk": "ENTITLEMENT"}, ConsistentRead=True).get("Item", {})
    authorize_stream(principal, viewer, server, greenhouse, entitlement)

    # Channel creation, short-lived STS session policy, and ICE retrieval are intentionally
    # performed only after authorization. Live calls require deployed resources and are
    # completed during the deployment stage, not with developer credentials in the APK.
    session_id = body.get("requestId")
    if not session_id or len(session_id) > 80:
        return response(400, {"error": "valid requestId required"})
    now = int(datetime.now(timezone.utc).timestamp())
    item = {
        "pk": f"STREAM_SESSION#{session_id}", "sk": "METADATA", "tenantId": principal.tenant_id,
        "accountId": principal.account_id, "greenhouseId": greenhouse_id,
        "viewerDeviceId": principal.device_id, "serverDeviceId": server["deviceId"],
        "state": "REQUESTED", "createdAt": now, "expiresAt": now + 300,
    }
    table.put_item(Item=item, ConditionExpression="attribute_not_exists(pk)")
    return response(202, {"sessionId": session_id, "state": "REQUESTED", "expiresAt": item["expiresAt"]})


def heartbeat(event, principal: Principal, device_id: str):
    if principal.device_id != device_id:
        raise AccessDenied("device_mismatch")
    device = get_owned("DEVICE", device_id, principal.tenant_id)
    if device.get("role") != "GREENHOUSE_SERVER" or not device.get("enabled"):
        raise AccessDenied("server_device")
    body = json.loads(event.get("body") or "{}")
    now = int(datetime.now(timezone.utc).timestamp())
    shard = sum(device_id.encode()) % 16
    table.update_item(
        Key={"pk": f"DEVICE#{device_id}", "sk": "METADATA"},
        UpdateExpression="SET lastHeartbeat=:now, heartbeatShard=:shard, sensorConnectionHealthy=:sensor, criticalTemperature=:critical",
        ExpressionAttributeValues={":now": now, ":shard": shard, ":sensor": bool(body.get("sensorConnectionHealthy")), ":critical": bool(body.get("criticalTemperature")), ":tenant": principal.tenant_id},
        ConditionExpression="tenantId=:tenant",
    )
    return response(204, {})


def handler(event, _context):
    try:
        principal = principal_from(event)
        method = event.get("requestContext", {}).get("http", {}).get("method")
        path = event.get("rawPath", "")
        parts = [value for value in path.split("/") if value]
        if method == "POST" and len(parts) == 4 and parts[:2] == ["v1", "greenhouses"] and parts[3] == "stream-sessions":
            return create_stream_session(event, principal, parts[2])
        if method == "POST" and len(parts) == 4 and parts[:2] == ["v1", "devices"] and parts[3] == "heartbeat":
            return heartbeat(event, principal, parts[2])
        return response(404, {"error": "not_found"})
    except AccessDenied as error:
        return response(403, {"error": str(error)})
    except Exception:
        return response(500, {"error": "internal_error"})

