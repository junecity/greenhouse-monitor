import json
import os
import secrets
from datetime import datetime, timezone

import boto3
from boto3.dynamodb.conditions import Key
from botocore.exceptions import ClientError

from domain import AccessDenied, Principal, authorize_stream, pairing_code_hash, require_device_id
from kinesis_session import connection_config, ensure_channel


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


def create_pairing_challenge(principal: Principal, server_device_id: str):
    if principal.device_id != server_device_id:
        raise AccessDenied("device_mismatch")
    server = get_owned("DEVICE", server_device_id, principal.tenant_id)
    if server.get("role") != "GREENHOUSE_SERVER" or not server.get("enabled"):
        raise AccessDenied("server_device")
    code = secrets.token_urlsafe(24)
    code_hash = pairing_code_hash(code)
    now = int(datetime.now(timezone.utc).timestamp())
    expires_at = now + 300
    table.put_item(
        Item={
            "pk": f"PAIRING#{code_hash}", "sk": "CHALLENGE", "tenantId": principal.tenant_id,
            "accountId": principal.account_id, "serverDeviceId": server_device_id,
            "createdAt": now, "expiresAt": expires_at,
        },
        ConditionExpression="attribute_not_exists(pk)",
    )
    return response(201, {"challengeId": code_hash[:16], "displayCode": code, "serverDeviceId": server_device_id, "expiresAt": expires_at})


def claim_pairing_challenge(event, principal: Principal):
    body = json.loads(event.get("body") or "{}")
    viewer_device_id = require_device_id(body.get("viewerDeviceId", ""))
    if principal.device_id != viewer_device_id:
        raise AccessDenied("device_mismatch")
    code_hash = pairing_code_hash(body.get("pairingCode", ""))
    now = int(datetime.now(timezone.utc).timestamp())
    challenge = table.update_item(
        Key={"pk": f"PAIRING#{code_hash}", "sk": "CHALLENGE"},
        UpdateExpression="SET claimedAt=:now, claimedBy=:viewer",
        ConditionExpression="tenantId=:tenant AND accountId=:account AND expiresAt>:now AND attribute_not_exists(claimedAt)",
        ExpressionAttributeValues={
            ":now": now, ":viewer": viewer_device_id,
            ":tenant": principal.tenant_id, ":account": principal.account_id,
        },
        ReturnValues="ALL_NEW",
    ).get("Attributes", {})
    device = {
        "pk": f"DEVICE#{viewer_device_id}", "sk": "METADATA", "deviceId": viewer_device_id,
        "tenantId": principal.tenant_id, "accountId": principal.account_id,
        "role": "VIEWER_CLIENT", "enabled": True,
        "pairedServerDeviceId": challenge["serverDeviceId"], "createdAt": now,
    }
    table.put_item(
        Item=device,
        ConditionExpression="attribute_not_exists(pk) OR (tenantId=:tenant AND accountId=:account AND #role=:viewerRole)",
        ExpressionAttributeNames={"#role": "role"},
        ExpressionAttributeValues={":tenant": principal.tenant_id, ":account": principal.account_id, ":viewerRole": "VIEWER_CLIENT"},
    )
    return response(201, {"id": viewer_device_id, "role": "VIEWER_CLIENT", "enabled": True})


def create_stream_session(event, principal: Principal, greenhouse_id: str):
    body = json.loads(event.get("body") or "{}")
    viewer = get_owned("DEVICE", principal.device_id, principal.tenant_id)
    greenhouse = get_owned("GREENHOUSE", greenhouse_id, principal.tenant_id)
    server = get_owned("DEVICE", greenhouse["serverDeviceId"], principal.tenant_id)
    entitlement = table.get_item(Key={"pk": f"ACCOUNT#{principal.account_id}", "sk": "ENTITLEMENT"}, ConsistentRead=True).get("Item", {})
    authorize_stream(principal, viewer, server, greenhouse, entitlement)

    session_id = body.get("requestId")
    if not session_id or len(session_id) > 80:
        return response(400, {"error": "valid requestId required"})
    now = int(datetime.now(timezone.utc).timestamp())
    channel_arn = ensure_channel(greenhouse_id)
    viewer_connection = connection_config(channel_arn, "VIEWER", principal.device_id)
    item = {
        "pk": f"STREAM_SESSION#{session_id}", "sk": "METADATA", "tenantId": principal.tenant_id,
        "accountId": principal.account_id, "greenhouseId": greenhouse_id,
        "viewerDeviceId": principal.device_id, "serverDeviceId": server["deviceId"],
        "signalingChannelArn": channel_arn, "state": "REQUESTED", "createdAt": now, "expiresAt": now + 300,
    }
    table.put_item(Item=item, ConditionExpression="attribute_not_exists(pk)")
    table.put_item(Item={
        "pk": f"DEVICE#{server['deviceId']}", "sk": f"STREAM_SESSION#{session_id}",
        "tenantId": principal.tenant_id, "sessionId": session_id, "greenhouseId": greenhouse_id,
        "viewerDeviceId": principal.device_id, "signalingChannelArn": channel_arn,
        "createdAt": now, "expiresAt": now + 300,
    })
    return response(201, stream_response(item, viewer_connection, principal.device_id))


def stream_response(item: dict, connection: dict, peer_client_id: str | None):
    return {
        "id": item["pk"].split("#", 1)[1], "greenhouseId": item["greenhouseId"],
        "serverDeviceId": item["serverDeviceId"], "viewerDeviceId": item["viewerDeviceId"],
        "signalingChannelArn": item["signalingChannelArn"], "region": os.environ.get("AWS_REGION", "us-east-1"),
        "iceServers": connection["iceServers"], "expiresAtEpochMs": int(item["expiresAt"]) * 1000,
        "signalingWebSocketUrl": connection["signalingWebSocketUrl"], "peerClientId": peer_client_id,
    }


def pending_stream_session(principal: Principal, server_device_id: str):
    if principal.device_id != server_device_id:
        raise AccessDenied("device_mismatch")
    server = get_owned("DEVICE", server_device_id, principal.tenant_id)
    if server.get("role") != "GREENHOUSE_SERVER" or not server.get("enabled"):
        raise AccessDenied("server_device")
    now = int(datetime.now(timezone.utc).timestamp())
    result = table.query(
        KeyConditionExpression=Key("pk").eq(f"DEVICE#{server_device_id}") & Key("sk").begins_with("STREAM_SESSION#"),
        ScanIndexForward=True, Limit=5,
    )
    pending = next((value for value in result.get("Items", []) if int(value.get("expiresAt", 0)) > now), None)
    if not pending:
        return response(204, {})
    session = table.get_item(Key={"pk": f"STREAM_SESSION#{pending['sessionId']}", "sk": "METADATA"}, ConsistentRead=True).get("Item")
    if not session or session.get("tenantId") != principal.tenant_id:
        raise AccessDenied("not_found")
    connection = connection_config(session["signalingChannelArn"], "MASTER", None)
    table.update_item(Key={"pk": session["pk"], "sk": session["sk"]}, UpdateExpression="SET #state=:state", ExpressionAttributeNames={"#state": "state"}, ExpressionAttributeValues={":state": "CONNECTING"})
    return response(200, stream_response(session, connection, None))


def end_stream_session(principal: Principal, session_id: str):
    session = table.get_item(Key={"pk": f"STREAM_SESSION#{session_id}", "sk": "METADATA"}, ConsistentRead=True).get("Item")
    if not session or session.get("tenantId") != principal.tenant_id:
        raise AccessDenied("not_found")
    if principal.device_id not in {session.get("viewerDeviceId"), session.get("serverDeviceId")}:
        raise AccessDenied("device_mismatch")
    now = int(datetime.now(timezone.utc).timestamp())
    table.update_item(Key={"pk": session["pk"], "sk": session["sk"]}, UpdateExpression="SET #state=:state, endedAt=:now", ExpressionAttributeNames={"#state": "state"}, ExpressionAttributeValues={":state": "ENDED", ":now": now})
    table.delete_item(Key={"pk": f"DEVICE#{session['serverDeviceId']}", "sk": f"STREAM_SESSION#{session_id}"})
    return response(204, {})


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
        UpdateExpression="SET lastHeartbeat=:now, heartbeatShard=:shard, sensorConnectionHealthy=:sensor, criticalTemperature=:critical REMOVE offlineAlertedAt",
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
        if method == "POST" and len(parts) == 4 and parts[:2] == ["v1", "devices"] and parts[3] == "pairing-challenges":
            return create_pairing_challenge(principal, parts[2])
        if method == "POST" and parts == ["v1", "pairing-challenges", "claim"]:
            return claim_pairing_challenge(event, principal)
        if method == "POST" and len(parts) == 4 and parts[:2] == ["v1", "greenhouses"] and parts[3] == "stream-sessions":
            return create_stream_session(event, principal, parts[2])
        if method == "GET" and len(parts) == 4 and parts[:2] == ["v1", "devices"] and parts[3] == "stream-sessions":
            return pending_stream_session(principal, parts[2])
        if method == "DELETE" and len(parts) == 3 and parts[:2] == ["v1", "stream-sessions"]:
            return end_stream_session(principal, parts[2])
        if method == "POST" and len(parts) == 4 and parts[:2] == ["v1", "devices"] and parts[3] == "heartbeat":
            return heartbeat(event, principal, parts[2])
        return response(404, {"error": "not_found"})
    except AccessDenied as error:
        return response(403, {"error": str(error)})
    except ClientError as error:
        if error.response.get("Error", {}).get("Code") == "ConditionalCheckFailedException":
            return response(409, {"error": "expired_used_or_conflicting"})
        return response(503, {"error": "dependency_error"})
    except Exception:
        return response(500, {"error": "internal_error"})

