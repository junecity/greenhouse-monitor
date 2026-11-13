from __future__ import annotations

import json
import hashlib
import hmac
import os
import secrets
import uuid
from datetime import datetime, timezone
from decimal import Decimal

import boto3
from boto3.dynamodb.conditions import Key
from botocore.exceptions import ClientError

from alerts import new_alert
from domain import AccessDenied, Principal, account_bootstrap_records, authorize_stream, pairing_code_hash, require_active_session_lease, require_device_id, require_resource_id
from kinesis_session import connection_config, ensure_channel
from google_play_verifier import GooglePlayVerificationError, verify_google_play_purchase


table = boto3.resource("dynamodb").Table(os.environ["TABLE_NAME"])
PENDING_SESSION_SECONDS = 300
AUTHORIZATION_LEASE_SECONDS = 120
MAXIMUM_SESSION_SECONDS = 4 * 60 * 60
MAX_ALERT_QUERY_PAGES = 5
SERVER_PAIRING_SECONDS = 120


def response(status: int, body: dict):
    return {"statusCode": status, "headers": {"content-type": "application/json", "cache-control": "no-store"}, "body": json.dumps(body, default=lambda value: int(value) if isinstance(value, Decimal) else str(value))}


def subject_from(event) -> str:
    claims = event.get("requestContext", {}).get("authorizer", {}).get("jwt", {}).get("claims", {})
    subject = claims.get("sub")
    if not subject:
        raise AccessDenied("unauthenticated")
    return subject


def principal_from(event) -> Principal:
    subject = subject_from(event)
    account = table.get_item(Key={"pk": f"USER#{subject}", "sk": "ACCOUNT"}, ConsistentRead=True).get("Item")
    if not account:
        raise AccessDenied("account_missing")
    account_metadata = table.get_item(
        Key={"pk": f"ACCOUNT#{account['accountId']}", "sk": "METADATA"}, ConsistentRead=True
    ).get("Item")
    if account_metadata and account_metadata.get("state", "ACTIVE") != "ACTIVE":
        raise AccessDenied("account_suspended")
    device_id = event.get("headers", {}).get("x-greenhouse-device-id", "")
    if not device_id:
        raise AccessDenied("device_missing")
    return Principal(account["accountId"], account["tenantId"], device_id)


def device_principal_from(event) -> Principal:
    headers = {str(key).lower(): str(value) for key, value in (event.get("headers") or {}).items()}
    device_id = require_device_id(headers.get("x-greenhouse-device-id", ""))
    device_secret = headers.get("x-greenhouse-device-secret", "")
    if len(device_secret) < 32 or len(device_secret) > 256:
        raise AccessDenied("device_credential_missing")
    device = table.get_item(Key={"pk": f"DEVICE#{device_id}", "sk": "METADATA"}, ConsistentRead=True).get("Item")
    supplied_hash = hashlib.sha256(device_secret.encode("utf-8")).hexdigest()
    if not device or not device.get("enabled") or not hmac.compare_digest(str(device.get("deviceSecretHash", "")), supplied_hash):
        raise AccessDenied("device_credential_invalid")
    account = table.get_item(Key={"pk": f"ACCOUNT#{device['accountId']}", "sk": "METADATA"}, ConsistentRead=True).get("Item")
    if not account or account.get("state", "ACTIVE") != "ACTIVE":
        raise AccessDenied("account_suspended")
    return Principal(device["accountId"], device["tenantId"], device_id)


def route_parts(event: dict) -> list[str]:
    """Return application route segments for both local and staged HTTP API events."""
    parts = [value for value in event.get("rawPath", "").split("/") if value]
    stage = str(event.get("requestContext", {}).get("stage") or "").strip("/")
    if stage and parts and parts[0] == stage:
        return parts[1:]
    return parts


def account_device_record(device: dict) -> dict:
    return {
        "pk": f"ACCOUNT#{device['accountId']}", "sk": f"DEVICE#{device['deviceId']}",
        "deviceId": device["deviceId"], "tenantId": device["tenantId"],
        "accountId": device["accountId"], "role": device["role"],
        "greenhouseId": device.get("greenhouseId"), "enabled": bool(device.get("enabled")),
        "communicationSuspended": bool(device.get("communicationSuspended")),
        "clientRole": device.get("clientRole"),
        "createdAt": int(device["createdAt"]),
        "adminEntity": "ACCOUNT_DEVICE",
        "adminSort": f"{int(device['createdAt']):020d}#{device['deviceId']}",
    }


def bootstrap_account(event):
    subject = subject_from(event)
    claims = event.get("requestContext", {}).get("authorizer", {}).get("jwt", {}).get("claims", {})
    existing = table.get_item(Key={"pk": f"USER#{subject}", "sk": "ACCOUNT"}, ConsistentRead=True).get("Item")
    if existing:
        metadata = table.get_item(
            Key={"pk": f"ACCOUNT#{existing['accountId']}", "sk": "METADATA"},
            ConsistentRead=True,
        ).get("Item") or {}
        linked_subject = str(metadata.get("cognitoSubject") or "")
        if linked_subject and linked_subject != subject:
            raise AccessDenied("account_identity_mismatch")
        created_at = int(existing.get("createdAt", int(datetime.now(timezone.utc).timestamp())))
        updates = ["cognitoSubject=:subject", "adminEntity=:entity", "adminSort=:sort"]
        values = {
            ":subject": subject, ":entity": "ACCOUNT",
            ":sort": f"{created_at:020d}#{existing['accountId']}",
        }
        if claims.get("email"):
            updates.append("email=:email")
            values[":email"] = str(claims["email"]).strip().lower()[:320]
        if claims.get("name"):
            updates.append("displayName=:name")
            values[":name"] = str(claims["name"]).strip()[:120]
        table.update_item(
            Key={"pk": f"ACCOUNT#{existing['accountId']}", "sk": "METADATA"},
            UpdateExpression=f"SET {', '.join(updates)}",
            ConditionExpression="tenantId=:tenant",
            ExpressionAttributeValues={**values, ":tenant": existing["tenantId"]},
        )
        return response(200, {"accountId": existing["accountId"], "tenantId": existing["tenantId"]})

    now = int(datetime.now(timezone.utc).timestamp())
    account_id = f"account-{uuid.uuid4().hex}"
    tenant_id = f"tenant-{uuid.uuid4().hex}"
    records = account_bootstrap_records(
        subject, account_id, tenant_id, now,
        email=claims.get("email"), display_name=claims.get("name"),
    )
    try:
        table.meta.client.transact_write_items(TransactItems=[
            {"Put": {
                "TableName": table.name,
                "Item": item,
                "ConditionExpression": "attribute_not_exists(pk)",
            }} for item in records
        ])
        return response(201, {"accountId": account_id, "tenantId": tenant_id})
    except ClientError as error:
        if error.response.get("Error", {}).get("Code") != "TransactionCanceledException":
            raise
        existing = table.get_item(Key={"pk": f"USER#{subject}", "sk": "ACCOUNT"}, ConsistentRead=True).get("Item")
        if not existing:
            raise
        return response(200, {"accountId": existing["accountId"], "tenantId": existing["tenantId"]})


def register_server_device(event, principal: Principal):
    body = json.loads(event.get("body") or "{}")
    device_id = require_device_id(body.get("deviceId", ""))
    greenhouse_id = require_resource_id(body.get("greenhouseId", ""))
    if principal.device_id != device_id:
        raise AccessDenied("device_mismatch")
    if body.get("role") != "GREENHOUSE_SERVER":
        raise AccessDenied("invalid_device_role")
    now = int(datetime.now(timezone.utc).timestamp())
    device = {
        "pk": f"DEVICE#{device_id}", "sk": "METADATA", "deviceId": device_id,
        "tenantId": principal.tenant_id, "accountId": principal.account_id,
        "role": "GREENHOUSE_SERVER", "greenhouseId": greenhouse_id, "enabled": True, "communicationSuspended": False, "createdAt": now,
        "adminEntity": "DEVICE", "adminSort": f"{now:020d}#{device_id}",
    }
    greenhouse = {
        "pk": f"GREENHOUSE#{greenhouse_id}", "sk": "METADATA", "greenhouseId": greenhouse_id,
        "tenantId": principal.tenant_id, "accountId": principal.account_id,
        "serverDeviceId": device_id, "enabled": True, "createdAt": now,
        "adminEntity": "GREENHOUSE", "adminSort": f"{now:020d}#{greenhouse_id}",
    }
    table.meta.client.transact_write_items(TransactItems=[
        {"Put": {
            "TableName": table.name, "Item": device,
            "ConditionExpression": "attribute_not_exists(pk) OR (tenantId=:tenant AND accountId=:account AND #role=:role)",
            "ExpressionAttributeNames": {"#role": "role"},
            "ExpressionAttributeValues": {
                ":tenant": principal.tenant_id, ":account": principal.account_id,
                ":role": "GREENHOUSE_SERVER",
            },
        }},
        {"Put": {
            "TableName": table.name, "Item": greenhouse,
            "ConditionExpression": "attribute_not_exists(pk) OR (tenantId=:tenant AND accountId=:account AND serverDeviceId=:device)",
            "ExpressionAttributeValues": {
                ":tenant": principal.tenant_id, ":account": principal.account_id,
                ":device": device_id,
            },
        }},
        {"Put": {
            "TableName": table.name, "Item": account_device_record(device),
            "ConditionExpression": "attribute_not_exists(pk) OR (tenantId=:tenant AND accountId=:account)",
            "ExpressionAttributeValues": {":tenant": principal.tenant_id, ":account": principal.account_id},
        }},
    ])
    return response(201, {
        "id": device_id, "greenhouseId": greenhouse_id,
        "tenantId": principal.tenant_id, "role": "GREENHOUSE_SERVER", "enabled": True,
    })


def register_client_device(event, principal: Principal):
    body = json.loads(event.get("body") or "{}")
    device_id = require_device_id(body.get("deviceId", ""))
    if principal.device_id != device_id:
        raise AccessDenied("device_mismatch")
    if body.get("role") != "VIEWER_CLIENT":
        raise AccessDenied("invalid_device_role")
    now = int(datetime.now(timezone.utc).timestamp())
    existing = table.get_item(Key={"pk": f"DEVICE#{device_id}", "sk": "METADATA"}, ConsistentRead=True).get("Item")
    if existing and not existing.get("enabled"):
        raise AccessDenied("device_disabled")
    created_at = int(existing.get("createdAt", now)) if existing else now
    account = table.get_item(Key={"pk": f"ACCOUNT#{principal.account_id}", "sk": "METADATA"}, ConsistentRead=True).get("Item", {})
    primary_device_id = account.get("primaryClientDeviceId")
    client_role = "PRIMARY_CLIENT" if not primary_device_id or primary_device_id == device_id else "ADDITIONAL_CLIENT"
    device = {
        "pk": f"DEVICE#{device_id}", "sk": "METADATA", "deviceId": device_id,
        "tenantId": principal.tenant_id, "accountId": principal.account_id,
        "role": "VIEWER_CLIENT", "clientRole": client_role, "enabled": True, "createdAt": created_at,
        "adminEntity": "DEVICE", "adminSort": f"{created_at:020d}#{device_id}",
    }
    table.meta.client.transact_write_items(TransactItems=[
        {"Put": {
            "TableName": table.name, "Item": device,
            "ConditionExpression": "attribute_not_exists(pk) OR (tenantId=:tenant AND accountId=:account AND #role=:role)",
            "ExpressionAttributeNames": {"#role": "role"},
            "ExpressionAttributeValues": {":tenant": principal.tenant_id, ":account": principal.account_id, ":role": "VIEWER_CLIENT"},
        }},
        {"Put": {
            "TableName": table.name, "Item": account_device_record(device),
            "ConditionExpression": "attribute_not_exists(pk) OR (tenantId=:tenant AND accountId=:account)",
            "ExpressionAttributeValues": {":tenant": principal.tenant_id, ":account": principal.account_id},
        }},
        {"Update": {
            "TableName": table.name,
            "Key": {"pk": f"ACCOUNT#{principal.account_id}", "sk": "METADATA"},
            "UpdateExpression": "SET primaryClientDeviceId=if_not_exists(primaryClientDeviceId,:device)",
            "ConditionExpression": "tenantId=:tenant AND accountId=:account",
            "ExpressionAttributeValues": {":device": device_id, ":tenant": principal.tenant_id, ":account": principal.account_id},
        }},
    ])
    return response(201 if not existing else 200, {
        "id": device_id, "tenantId": principal.tenant_id, "role": "VIEWER_CLIENT", "clientRole": client_role, "enabled": True,
    })


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
    server = get_owned("DEVICE", challenge["serverDeviceId"], principal.tenant_id)
    device = {
        "pk": f"DEVICE#{viewer_device_id}", "sk": "METADATA", "deviceId": viewer_device_id,
        "tenantId": principal.tenant_id, "accountId": principal.account_id,
        "role": "VIEWER_CLIENT", "enabled": True,
        "pairedServerDeviceId": challenge["serverDeviceId"], "greenhouseId": server.get("greenhouseId"), "createdAt": now,
        "adminEntity": "DEVICE", "adminSort": f"{now:020d}#{viewer_device_id}",
    }
    table.meta.client.transact_write_items(TransactItems=[
        {"Put": {
            "TableName": table.name, "Item": device,
            "ConditionExpression": "attribute_not_exists(pk) OR (tenantId=:tenant AND accountId=:account AND #role=:viewerRole)",
            "ExpressionAttributeNames": {"#role": "role"},
            "ExpressionAttributeValues": {":tenant": principal.tenant_id, ":account": principal.account_id, ":viewerRole": "VIEWER_CLIENT"},
        }},
        {"Put": {
            "TableName": table.name, "Item": account_device_record(device),
            "ConditionExpression": "attribute_not_exists(pk) OR (tenantId=:tenant AND accountId=:account)",
            "ExpressionAttributeValues": {":tenant": principal.tenant_id, ":account": principal.account_id},
        }},
    ])
    return response(201, {"id": viewer_device_id, "tenantId": principal.tenant_id, "role": "VIEWER_CLIENT", "enabled": True, "greenhouseId": server.get("greenhouseId")})


def require_enabled_requester(principal: Principal) -> dict:
    requester = get_owned("DEVICE", principal.device_id, principal.tenant_id)
    if not requester.get("enabled") or requester.get("communicationSuspended") or requester.get("accountId") != principal.account_id:
        raise AccessDenied("device_disabled")
    return requester


def require_client_requester(principal: Principal) -> dict:
    requester = require_enabled_requester(principal)
    if requester.get("role") != "VIEWER_CLIENT":
        raise AccessDenied("client_device_required")
    return requester


def current_device(principal: Principal):
    device = get_owned("DEVICE", principal.device_id, principal.tenant_id)
    if not device.get("enabled") or device.get("accountId") != principal.account_id:
        raise AccessDenied("device_disabled")
    return response(200, {
        "id": device["deviceId"], "tenantId": device["tenantId"], "role": device["role"],
        "enabled": True, "greenhouseId": device.get("greenhouseId"),
        "communicationSuspended": bool(device.get("communicationSuspended")), "clientRole": device.get("clientRole"),
    })


def list_devices(principal: Principal):
    require_client_requester(principal)
    result = table.query(
        KeyConditionExpression=Key("pk").eq(f"ACCOUNT#{principal.account_id}") & Key("sk").begins_with("DEVICE#"),
        ConsistentRead=True,
    )
    now = int(datetime.now(timezone.utc).timestamp())
    devices = []
    for item in result.get("Items", []):
        if item.get("tenantId") != principal.tenant_id:
            continue
        primary = table.get_item(Key={"pk": f"DEVICE#{item['deviceId']}", "sk": "METADATA"}, ConsistentRead=True).get("Item", item)
        last_heartbeat = int(primary.get("lastHeartbeat", 0))
        devices.append({
            "id": item["deviceId"], "tenantId": item["tenantId"], "role": item["role"],
            "enabled": bool(primary.get("enabled")), "communicationSuspended": bool(primary.get("communicationSuspended")),
            "clientRole": primary.get("clientRole"), "greenhouseId": item.get("greenhouseId"),
            "createdAtEpochMs": int(item.get("createdAt", 0)) * 1000,
            "lastSeenAtEpochMs": last_heartbeat * 1000 if last_heartbeat else None,
            "online": bool(primary.get("enabled")) and not primary.get("communicationSuspended") and last_heartbeat > 0 and now - last_heartbeat <= 180,
        })
    return response(200, {"devices": sorted(devices, key=lambda value: value["createdAtEpochMs"])})


def set_device_state(event, principal: Principal, device_id: str):
    require_device_id(device_id)
    require_client_requester(principal)
    target = get_owned("DEVICE", device_id, principal.tenant_id)
    if target.get("accountId") != principal.account_id:
        raise AccessDenied("not_found")
    body = json.loads(event.get("body") or "{}")
    if not isinstance(body.get("enabled"), bool):
        return response(400, {"error": "enabled_boolean_required"})
    enabled = body["enabled"]
    if enabled and not target.get("enabled"):
        raise AccessDenied("fresh_pairing_required")
    now = int(datetime.now(timezone.utc).timestamp())
    expression = "SET enabled=:enabled"
    if enabled:
        expression += " REMOVE revokedAt, revokedBy"
    else:
        expression += ", revokedAt=:now, revokedBy=:requester"
    values = {":enabled": enabled, ":tenant": principal.tenant_id, ":account": principal.account_id}
    if not enabled:
        values[":now"] = now
        values[":requester"] = principal.device_id
    table.meta.client.transact_write_items(TransactItems=[
        {"Update": {
            "TableName": table.name,
            "Key": {"pk": f"DEVICE#{device_id}", "sk": "METADATA"},
            "UpdateExpression": expression,
            "ConditionExpression": "tenantId=:tenant AND accountId=:account",
            "ExpressionAttributeValues": values,
        }},
        {"Update": {
            "TableName": table.name,
            "Key": {"pk": f"ACCOUNT#{principal.account_id}", "sk": f"DEVICE#{device_id}"},
            "UpdateExpression": expression,
            "ConditionExpression": "tenantId=:tenant AND accountId=:account",
            "ExpressionAttributeValues": values,
        }},
    ])
    return response(200, {
        "id": device_id, "tenantId": principal.tenant_id, "role": target["role"],
        "enabled": enabled, "communicationSuspended": bool(target.get("communicationSuspended")),
        "clientRole": target.get("clientRole"), "greenhouseId": target.get("greenhouseId"),
    })


def set_device_connection(event, principal: Principal, device_id: str):
    require_device_id(device_id)
    require_client_requester(principal)
    target = get_owned("DEVICE", device_id, principal.tenant_id)
    if target.get("accountId") != principal.account_id or target.get("role") != "GREENHOUSE_SERVER":
        raise AccessDenied("not_found")
    if not target.get("enabled"):
        raise AccessDenied("device_revoked")
    body = json.loads(event.get("body") or "{}")
    if not isinstance(body.get("connected"), bool):
        return response(400, {"error": "connected_boolean_required"})
    suspended = not body["connected"]
    now = int(datetime.now(timezone.utc).timestamp())
    expression = "SET communicationSuspended=:suspended, connectionChangedAt=:now, connectionChangedBy=:requester"
    values = {
        ":suspended": suspended, ":now": now, ":requester": principal.device_id,
        ":tenant": principal.tenant_id, ":account": principal.account_id,
    }
    table.meta.client.transact_write_items(TransactItems=[
        {"Update": {"TableName": table.name, "Key": {"pk": f"DEVICE#{device_id}", "sk": "METADATA"},
                    "UpdateExpression": expression, "ConditionExpression": "tenantId=:tenant AND accountId=:account AND enabled=:enabled",
                    "ExpressionAttributeValues": {**values, ":enabled": True}}},
        {"Update": {"TableName": table.name, "Key": {"pk": f"ACCOUNT#{principal.account_id}", "sk": f"DEVICE#{device_id}"},
                    "UpdateExpression": expression, "ConditionExpression": "tenantId=:tenant AND accountId=:account AND enabled=:enabled",
                    "ExpressionAttributeValues": {**values, ":enabled": True}}},
    ])
    return response(200, {"id": device_id, "tenantId": principal.tenant_id, "role": target["role"],
                          "enabled": True, "communicationSuspended": suspended,
                          "greenhouseId": target.get("greenhouseId")})


def create_server_invitation(event, principal: Principal):
    require_client_requester(principal)
    body = json.loads(event.get("body") or "{}")
    greenhouse_id = require_resource_id(body.get("greenhouseId", ""))
    greenhouse_name = str(body.get("greenhouseName") or "Greenhouse").strip()[:120] or "Greenhouse"
    replace_device_id = body.get("replaceServerDeviceId")
    if replace_device_id:
        replace_device_id = require_device_id(replace_device_id)
        replaced = get_owned("DEVICE", replace_device_id, principal.tenant_id)
        if replaced.get("accountId") != principal.account_id or replaced.get("role") != "GREENHOUSE_SERVER" or replaced.get("greenhouseId") != greenhouse_id:
            raise AccessDenied("invalid_replacement")
    code = secrets.token_urlsafe(24)
    code_hash = pairing_code_hash(code)
    now = int(datetime.now(timezone.utc).timestamp())
    expires_at = now + SERVER_PAIRING_SECONDS
    invitation_id = f"invite-{uuid.uuid4().hex}"
    table.put_item(Item={
        "pk": f"PAIRING#{code_hash}", "sk": "SERVER_INVITATION", "invitationId": invitation_id,
        "tenantId": principal.tenant_id, "accountId": principal.account_id,
        "greenhouseId": greenhouse_id, "greenhouseName": greenhouse_name,
        "replaceServerDeviceId": replace_device_id, "requestedByDeviceId": principal.device_id,
        "createdAt": now, "expiresAt": expires_at,
    }, ConditionExpression="attribute_not_exists(pk)")
    return response(201, {
        "invitationId": invitation_id, "displayCode": code, "greenhouseId": greenhouse_id,
        "greenhouseName": greenhouse_name, "expiresAt": expires_at,
    })


def request_server_pairing(event):
    body = json.loads(event.get("body") or "{}")
    device_id = require_device_id(body.get("serverDeviceId", ""))
    device_secret = str(body.get("deviceSecret") or "")
    if len(device_secret) < 32 or len(device_secret) > 256:
        return response(400, {"error": "valid_device_secret_required"})
    code_hash = pairing_code_hash(body.get("pairingCode", ""))
    now = int(datetime.now(timezone.utc).timestamp())
    invitation = table.get_item(Key={"pk": f"PAIRING#{code_hash}", "sk": "SERVER_INVITATION"}, ConsistentRead=True).get("Item")
    if not invitation or int(invitation.get("expiresAt", 0)) <= now or invitation.get("requestedAt"):
        raise AccessDenied("expired_used_or_invalid_pairing_code")
    request_id = f"request-{uuid.uuid4().hex}"
    request = {
        "pk": f"SERVER_PAIRING#{request_id}", "sk": "METADATA", "requestId": request_id,
        "tenantId": invitation["tenantId"], "accountId": invitation["accountId"],
        "greenhouseId": invitation["greenhouseId"], "greenhouseName": invitation.get("greenhouseName", "Greenhouse"),
        "replaceServerDeviceId": invitation.get("replaceServerDeviceId"),
        "serverDeviceId": device_id, "deviceSecretHash": hashlib.sha256(device_secret.encode("utf-8")).hexdigest(),
        "state": "PENDING_APPROVAL", "createdAt": now, "expiresAt": int(invitation["expiresAt"]),
    }
    account_request = {**request, "pk": f"ACCOUNT#{invitation['accountId']}", "sk": f"SERVER_PAIRING#{request_id}"}
    table.meta.client.transact_write_items(TransactItems=[
        {"Update": {
            "TableName": table.name, "Key": {"pk": invitation["pk"], "sk": invitation["sk"]},
            "UpdateExpression": "SET requestedAt=:now, requestId=:request, serverDeviceId=:device",
            "ConditionExpression": "expiresAt>:now AND attribute_not_exists(requestedAt)",
            "ExpressionAttributeValues": {":now": now, ":request": request_id, ":device": device_id},
        }},
        {"Put": {"TableName": table.name, "Item": request, "ConditionExpression": "attribute_not_exists(pk)"}},
        {"Put": {"TableName": table.name, "Item": account_request, "ConditionExpression": "attribute_not_exists(pk)"}},
    ])
    return response(202, {"requestId": request_id, "state": "PENDING_APPROVAL", "expiresAt": request["expiresAt"]})


def list_server_pairing_requests(principal: Principal):
    require_client_requester(principal)
    now = int(datetime.now(timezone.utc).timestamp())
    result = table.query(
        KeyConditionExpression=Key("pk").eq(f"ACCOUNT#{principal.account_id}") & Key("sk").begins_with("SERVER_PAIRING#"),
        ConsistentRead=True,
    )
    requests = [{
        "requestId": item["requestId"], "serverDeviceId": item["serverDeviceId"],
        "greenhouseId": item["greenhouseId"], "greenhouseName": item.get("greenhouseName", "Greenhouse"),
        "replaceServerDeviceId": item.get("replaceServerDeviceId"), "state": item.get("state", "PENDING_APPROVAL"),
        "createdAtEpochMs": int(item.get("createdAt", 0)) * 1000, "expiresAtEpochMs": int(item.get("expiresAt", 0)) * 1000,
    } for item in result.get("Items", []) if item.get("tenantId") == principal.tenant_id and int(item.get("expiresAt", 0)) > now and item.get("state") == "PENDING_APPROVAL"]
    return response(200, {"requests": requests})


def approve_server_pairing(principal: Principal, request_id: str):
    require_client_requester(principal)
    require_resource_id(request_id)
    now = int(datetime.now(timezone.utc).timestamp())
    request = table.get_item(Key={"pk": f"SERVER_PAIRING#{request_id}", "sk": "METADATA"}, ConsistentRead=True).get("Item")
    if not request or request.get("accountId") != principal.account_id or request.get("tenantId") != principal.tenant_id:
        raise AccessDenied("not_found")
    if request.get("state") != "PENDING_APPROVAL" or int(request.get("expiresAt", 0)) <= now:
        raise AccessDenied("expired_used_or_conflicting")
    device_id = request["serverDeviceId"]
    greenhouse_id = request["greenhouseId"]
    existing_device = table.get_item(
        Key={"pk": f"DEVICE#{device_id}", "sk": "METADATA"}, ConsistentRead=True
    ).get("Item")
    if existing_device and (
        existing_device.get("tenantId") != principal.tenant_id
        or existing_device.get("accountId") != principal.account_id
        or existing_device.get("role") != "GREENHOUSE_SERVER"
    ):
        raise AccessDenied("device_owned_by_another_account")
    existing_greenhouse = table.get_item(Key={"pk": f"GREENHOUSE#{greenhouse_id}", "sk": "METADATA"}, ConsistentRead=True).get("Item")
    created_at = int(existing_greenhouse.get("createdAt", now)) if existing_greenhouse else now
    device_created_at = int(existing_device.get("createdAt", now)) if existing_device else now
    device = {
        "pk": f"DEVICE#{device_id}", "sk": "METADATA", "deviceId": device_id,
        "tenantId": principal.tenant_id, "accountId": principal.account_id, "role": "GREENHOUSE_SERVER",
        "greenhouseId": greenhouse_id, "enabled": True, "communicationSuspended": False, "createdAt": device_created_at,
        "deviceSecretHash": request["deviceSecretHash"],
        "adminEntity": "DEVICE", "adminSort": f"{device_created_at:020d}#{device_id}",
    }
    greenhouse = {
        **(existing_greenhouse or {}), "pk": f"GREENHOUSE#{greenhouse_id}", "sk": "METADATA",
        "greenhouseId": greenhouse_id, "tenantId": principal.tenant_id, "accountId": principal.account_id,
        "name": request.get("greenhouseName", "Greenhouse"), "serverDeviceId": device_id, "enabled": True,
        "createdAt": created_at, "updatedAt": now, "adminEntity": "GREENHOUSE",
        "adminSort": f"{created_at:020d}#{greenhouse_id}",
    }
    if existing_device:
        device_write = {"Update": {
            "TableName": table.name, "Key": {"pk": f"DEVICE#{device_id}", "sk": "METADATA"},
            "UpdateExpression": (
                "SET greenhouseId=:greenhouse, enabled=:enabled, communicationSuspended=:connected, "
                "deviceSecretHash=:secret, reauthorizedAt=:now, adminEntity=:entity, adminSort=:sort "
                "REMOVE revokedAt, revokedBy, replacedAt, replacedBy"
            ),
            "ConditionExpression": "tenantId=:tenant AND accountId=:account AND #role=:role",
            "ExpressionAttributeNames": {"#role": "role"},
            "ExpressionAttributeValues": {
                ":greenhouse": greenhouse_id, ":enabled": True, ":connected": False,
                ":secret": request["deviceSecretHash"], ":now": now, ":entity": "DEVICE",
                ":sort": f"{device_created_at:020d}#{device_id}", ":tenant": principal.tenant_id,
                ":account": principal.account_id, ":role": "GREENHOUSE_SERVER",
            },
        }}
    else:
        device_write = {"Put": {
            "TableName": table.name, "Item": device, "ConditionExpression": "attribute_not_exists(pk)"
        }}
    transaction = [
        device_write,
        {"Put": {"TableName": table.name, "Item": greenhouse,
                 "ConditionExpression": "attribute_not_exists(pk) OR (tenantId=:tenant AND accountId=:account)",
                 "ExpressionAttributeValues": {":tenant": principal.tenant_id, ":account": principal.account_id}}},
        {"Put": {"TableName": table.name, "Item": account_device_record(device)}},
        {"Update": {"TableName": table.name, "Key": {"pk": request["pk"], "sk": request["sk"]},
                    "UpdateExpression": "SET #state=:approved, approvedAt=:now, approvedBy=:client",
                    "ConditionExpression": "#state=:pending AND expiresAt>:now", "ExpressionAttributeNames": {"#state": "state"},
                    "ExpressionAttributeValues": {":approved": "APPROVED", ":pending": "PENDING_APPROVAL", ":now": now, ":client": principal.device_id}}},
        {"Update": {"TableName": table.name, "Key": {"pk": f"ACCOUNT#{principal.account_id}", "sk": f"SERVER_PAIRING#{request_id}"},
                    "UpdateExpression": "SET #state=:approved, approvedAt=:now, approvedBy=:client",
                    "ConditionExpression": "#state=:pending AND expiresAt>:now", "ExpressionAttributeNames": {"#state": "state"},
                    "ExpressionAttributeValues": {":approved": "APPROVED", ":pending": "PENDING_APPROVAL", ":now": now, ":client": principal.device_id}}},
    ]
    old_device_id = request.get("replaceServerDeviceId") or (existing_greenhouse or {}).get("serverDeviceId")
    if old_device_id and old_device_id != device_id:
        for key in ({"pk": f"DEVICE#{old_device_id}", "sk": "METADATA"}, {"pk": f"ACCOUNT#{principal.account_id}", "sk": f"DEVICE#{old_device_id}"}):
            transaction.append({"Update": {
                "TableName": table.name, "Key": key, "UpdateExpression": "SET enabled=:disabled, replacedAt=:now, replacedBy=:device",
                "ConditionExpression": "tenantId=:tenant AND accountId=:account",
                "ExpressionAttributeValues": {":disabled": False, ":now": now, ":device": device_id, ":tenant": principal.tenant_id, ":account": principal.account_id},
            }})
    table.meta.client.transact_write_items(TransactItems=transaction)
    return response(200, {"requestId": request_id, "state": "APPROVED", "serverDeviceId": device_id, "greenhouseId": greenhouse_id})


def server_pairing_status(event):
    body = json.loads(event.get("body") or "{}")
    request_id = require_resource_id(body.get("requestId", ""))
    device_id = require_device_id(body.get("serverDeviceId", ""))
    secret = str(body.get("deviceSecret") or "")
    request = table.get_item(Key={"pk": f"SERVER_PAIRING#{request_id}", "sk": "METADATA"}, ConsistentRead=True).get("Item")
    secret_hash = hashlib.sha256(secret.encode("utf-8")).hexdigest()
    if not request or request.get("serverDeviceId") != device_id or not hmac.compare_digest(str(request.get("deviceSecretHash", "")), secret_hash):
        raise AccessDenied("pairing_request_invalid")
    now = int(datetime.now(timezone.utc).timestamp())
    state = request.get("state", "PENDING_APPROVAL")
    if state != "APPROVED" and int(request.get("expiresAt", 0)) <= now:
        return response(410, {"error": "pairing_request_expired"})
    body = {"requestId": request_id, "state": state, "expiresAt": int(request.get("expiresAt", 0))}
    if state == "APPROVED":
        body.update({"id": device_id, "tenantId": request["tenantId"], "role": "GREENHOUSE_SERVER", "enabled": True, "communicationSuspended": False, "greenhouseId": request["greenhouseId"]})
    return response(200, body)


def greenhouse_backup(event, principal: Principal, greenhouse_id: str, write: bool):
    greenhouse_id = require_resource_id(greenhouse_id)
    device = require_enabled_requester(principal)
    greenhouse = get_owned("GREENHOUSE", greenhouse_id, principal.tenant_id)
    if greenhouse.get("accountId") != principal.account_id:
        raise AccessDenied("not_found")
    authorized_server = (
        device.get("role") == "GREENHOUSE_SERVER"
        and device.get("greenhouseId") == greenhouse_id
        and greenhouse.get("serverDeviceId") == principal.device_id
    )
    if write and not authorized_server:
        raise AccessDenied("server_device")
    if not write and device.get("role") != "VIEWER_CLIENT" and not authorized_server:
        raise AccessDenied("authorized_device_required")
    key = {"pk": f"GREENHOUSE#{greenhouse_id}", "sk": "BACKUP"}
    if not write:
        item = table.get_item(Key=key, ConsistentRead=True).get("Item")
        return response(200, {"backup": json.loads(item["backupJson"]) if item else None, "updatedAt": int(item.get("updatedAt", 0)) if item else None})
    body = json.loads(event.get("body") or "{}")
    backup = body.get("backup")
    if not isinstance(backup, dict) or backup.get("schema") not in {1, 2}:
        return response(400, {"error": "valid_backup_required"})
    encoded = json.dumps(backup, separators=(",", ":"))
    if len(encoded.encode("utf-8")) > 350_000:
        return response(413, {"error": "backup_too_large"})
    now = int(datetime.now(timezone.utc).timestamp())
    table.put_item(Item={
        **key, "accountId": principal.account_id, "tenantId": principal.tenant_id,
        "greenhouseId": greenhouse_id, "backupJson": encoded, "updatedAt": now,
    })
    return response(204, {})


def effective_video_entitlement(item: dict, now: int | None = None) -> dict:
    """Apply the administrator video override without mutating billing records."""
    now = now or int(datetime.now(timezone.utc).timestamp())
    effective = dict(item)
    mode = str(item.get("videoAccessOverride") or "SUBSCRIPTION_CONTROLLED")
    override_expires = item.get("videoAccessOverrideExpiresAt")
    if mode == "COMPLIMENTARY" and (override_expires is None or int(override_expires) > now):
        effective.update({
            "plan": "COMPLIMENTARY", "state": "ACTIVE", "source": "COMPLIMENTARY_BACKEND_GRANT",
            "features": sorted(set(item.get("features", [])) | {"REMOTE_LIVE_VIDEO"}),
            "expiresAt": int(override_expires) if override_expires else None,
        })
    elif mode == "DISABLED":
        effective["features"] = [feature for feature in item.get("features", []) if feature != "REMOTE_LIVE_VIDEO"]
    effective["videoAccessMode"] = mode
    return effective


def create_stream_session(event, principal: Principal, greenhouse_id: str):
    body = json.loads(event.get("body") or "{}")
    viewer = get_owned("DEVICE", principal.device_id, principal.tenant_id)
    greenhouse = get_owned("GREENHOUSE", greenhouse_id, principal.tenant_id)
    server = get_owned("DEVICE", greenhouse["serverDeviceId"], principal.tenant_id)
    entitlement = effective_video_entitlement(table.get_item(Key={"pk": f"ACCOUNT#{principal.account_id}", "sk": "ENTITLEMENT"}, ConsistentRead=True).get("Item", {}))
    authorize_stream(principal, viewer, server, greenhouse, entitlement)

    session_id = body.get("requestId")
    if not session_id or len(session_id) > 80:
        return response(400, {"error": "valid requestId required"})
    now = int(datetime.now(timezone.utc).timestamp())
    # A greenhouse currently supports one live viewer. Retire any abandoned request before
    # publishing the new pointer so the server cannot answer a stale UUID while the current
    # viewer waits on a different signaling session.
    retire_other_stream_sessions(principal, server["deviceId"], greenhouse_id, now)
    channel_arn = ensure_channel(greenhouse_id)
    viewer_connection = connection_config(channel_arn, "VIEWER", principal.device_id)
    item = {
        "pk": f"STREAM_SESSION#{session_id}", "sk": "METADATA", "tenantId": principal.tenant_id,
        "accountId": principal.account_id, "greenhouseId": greenhouse_id,
        "viewerDeviceId": principal.device_id, "serverDeviceId": server["deviceId"],
        "signalingChannelArn": channel_arn, "state": "REQUESTED", "createdAt": now,
        "authorizationExpiresAt": now + AUTHORIZATION_LEASE_SECONDS,
        "expiresAt": now + MAXIMUM_SESSION_SECONDS,
        "adminEntity": "STREAM_SESSION", "adminSort": f"{now:020d}#{session_id}",
    }
    table.put_item(Item=item, ConditionExpression="attribute_not_exists(pk)")
    table.put_item(Item={
        "pk": f"DEVICE#{server['deviceId']}", "sk": f"STREAM_SESSION#{session_id}",
        "tenantId": principal.tenant_id, "sessionId": session_id, "greenhouseId": greenhouse_id,
        "viewerDeviceId": principal.device_id, "signalingChannelArn": channel_arn,
        "createdAt": now, "expiresAt": now + PENDING_SESSION_SECONDS,
    })
    return response(201, stream_response(item, viewer_connection, None))


def retire_stream_session_pointer(pointer: dict, session: dict | None, now: int):
    """End a stale session and remove its server queue pointer without deleting history."""
    session_id = pointer.get("sessionId")
    if not session_id:
        return
    if session and session.get("state") != "ENDED":
        table.update_item(
            Key={"pk": session["pk"], "sk": session["sk"]},
            UpdateExpression="SET #state=:state, endedAt=:now, endReason=:reason",
            ExpressionAttributeNames={"#state": "state"},
            ExpressionAttributeValues={":state": "ENDED", ":now": now, ":reason": "SUPERSEDED"},
        )
    table.delete_item(Key={"pk": pointer["pk"], "sk": pointer["sk"]})


def server_stream_pointers(server_device_id: str) -> list[dict]:
    result = table.query(
        KeyConditionExpression=Key("pk").eq(f"DEVICE#{server_device_id}") & Key("sk").begins_with("STREAM_SESSION#"),
        ScanIndexForward=False,
        Limit=50,
    )
    return result.get("Items", [])


def retire_other_stream_sessions(principal: Principal, server_device_id: str, greenhouse_id: str, now: int):
    for pointer in server_stream_pointers(server_device_id):
        session_id = pointer.get("sessionId")
        session = table.get_item(
            Key={"pk": f"STREAM_SESSION#{session_id}", "sk": "METADATA"}, ConsistentRead=True
        ).get("Item") if session_id else None
        if session and session.get("tenantId") != principal.tenant_id:
            continue
        if session and session.get("greenhouseId") != greenhouse_id:
            continue
        retire_stream_session_pointer(pointer, session, now)


def stream_response(item: dict, connection: dict, peer_client_id: str | None):
    return {
        "id": item["pk"].split("#", 1)[1], "greenhouseId": item["greenhouseId"],
        "serverDeviceId": item["serverDeviceId"], "viewerDeviceId": item["viewerDeviceId"],
        "signalingChannelArn": item["signalingChannelArn"], "region": os.environ.get("AWS_REGION", "us-east-1"),
        "iceServers": connection["iceServers"], "expiresAtEpochMs": int(item["expiresAt"]) * 1000,
        "authorizationExpiresAtEpochMs": int(item.get("authorizationExpiresAt", 0)) * 1000,
        "signalingWebSocketUrl": connection["signalingWebSocketUrl"], "peerClientId": peer_client_id,
    }


def participating_session(principal: Principal, session_id: str) -> dict:
    session = table.get_item(Key={"pk": f"STREAM_SESSION#{session_id}", "sk": "METADATA"}, ConsistentRead=True).get("Item")
    if not session or session.get("tenantId") != principal.tenant_id:
        raise AccessDenied("not_found")
    if principal.device_id not in {session.get("viewerDeviceId"), session.get("serverDeviceId")}:
        raise AccessDenied("device_mismatch")
    return session


def authorize_or_check_session_lease(principal: Principal, session: dict, now: int, extend_viewer: bool) -> int:
    if int(session.get("expiresAt", 0)) <= now or session.get("state") == "ENDED":
        raise AccessDenied("session_expired")
    if principal.device_id == session.get("viewerDeviceId"):
        viewer = get_owned("DEVICE", principal.device_id, principal.tenant_id)
        greenhouse = get_owned("GREENHOUSE", session["greenhouseId"], principal.tenant_id)
        server = get_owned("DEVICE", session["serverDeviceId"], principal.tenant_id)
        entitlement_item = effective_video_entitlement(table.get_item(Key={"pk": f"ACCOUNT#{principal.account_id}", "sk": "ENTITLEMENT"}, ConsistentRead=True).get("Item", {}), now)
        authorize_stream(principal, viewer, server, greenhouse, entitlement_item, now)
        authorization_expires_at = now + AUTHORIZATION_LEASE_SECONDS
        if extend_viewer:
            table.update_item(
                Key={"pk": session["pk"], "sk": session["sk"]},
                UpdateExpression="SET authorizationExpiresAt=:expires",
                ConditionExpression="#state<>:ended AND expiresAt>:now",
                ExpressionAttributeNames={"#state": "state"},
                ExpressionAttributeValues={":expires": authorization_expires_at, ":ended": "ENDED", ":now": now},
            )
            session["authorizationExpiresAt"] = authorization_expires_at
        return authorization_expires_at
    server = get_owned("DEVICE", principal.device_id, principal.tenant_id)
    if not server.get("enabled") or server.get("communicationSuspended"):
        raise AccessDenied("device_disabled")
    require_active_session_lease(session, now)
    return int(session["authorizationExpiresAt"])


def renew_stream_lease(principal: Principal, session_id: str):
    now = int(datetime.now(timezone.utc).timestamp())
    session = participating_session(principal, session_id)
    expires_at = authorize_or_check_session_lease(principal, session, now, extend_viewer=True)
    return response(200, {"authorizationExpiresAtEpochMs": expires_at * 1000})


def refresh_stream_connection(principal: Principal, session_id: str):
    now = int(datetime.now(timezone.utc).timestamp())
    session = participating_session(principal, session_id)
    authorize_or_check_session_lease(principal, session, now, extend_viewer=True)
    is_viewer = principal.device_id == session.get("viewerDeviceId")
    role = "VIEWER" if is_viewer else "MASTER"
    client_id = principal.device_id if is_viewer else None
    peer_client_id = None if is_viewer else session["viewerDeviceId"]
    connection = connection_config(session["signalingChannelArn"], role, client_id)
    return response(200, stream_response(session, connection, peer_client_id))


def pending_stream_session(principal: Principal, server_device_id: str):
    if principal.device_id != server_device_id:
        raise AccessDenied("device_mismatch")
    server = get_owned("DEVICE", server_device_id, principal.tenant_id)
    if server.get("role") != "GREENHOUSE_SERVER" or not server.get("enabled") or server.get("communicationSuspended"):
        raise AccessDenied("server_device")
    now = int(datetime.now(timezone.utc).timestamp())
    valid: list[tuple[dict, dict]] = []
    for pointer in server_stream_pointers(server_device_id):
        session_id = pointer.get("sessionId")
        session = table.get_item(
            Key={"pk": f"STREAM_SESSION#{session_id}", "sk": "METADATA"}, ConsistentRead=True
        ).get("Item") if session_id else None
        usable = (
            session
            and session.get("tenantId") == principal.tenant_id
            and session.get("serverDeviceId") == server_device_id
            and session.get("state") != "ENDED"
            and int(session.get("expiresAt", 0)) > now
            and int(pointer.get("expiresAt", 0)) > now
        )
        if usable:
            valid.append((pointer, session))
        elif not session or session.get("tenantId") == principal.tenant_id:
            retire_stream_session_pointer(pointer, session, now)
    if not valid:
        return response(204, {})
    valid.sort(key=lambda pair: int(pair[1].get("createdAt", 0)), reverse=True)
    pending, session = valid[0]
    for stale_pointer, stale_session in valid[1:]:
        retire_stream_session_pointer(stale_pointer, stale_session, now)
    connection = connection_config(session["signalingChannelArn"], "MASTER", None)
    table.update_item(Key={"pk": session["pk"], "sk": session["sk"]}, UpdateExpression="SET #state=:state", ExpressionAttributeNames={"#state": "state"}, ExpressionAttributeValues={":state": "CONNECTING"})
    return response(200, stream_response(session, connection, session["viewerDeviceId"]))


def end_stream_session(principal: Principal, session_id: str):
    session = participating_session(principal, session_id)
    now = int(datetime.now(timezone.utc).timestamp())
    table.update_item(Key={"pk": session["pk"], "sk": session["sk"]}, UpdateExpression="SET #state=:state, endedAt=:now", ExpressionAttributeNames={"#state": "state"}, ExpressionAttributeValues={":state": "ENDED", ":now": now})
    table.delete_item(Key={"pk": f"DEVICE#{session['serverDeviceId']}", "sk": f"STREAM_SESSION#{session_id}"})
    return response(204, {})


def play_test_checkout(account_id: str) -> dict | None:
    testers = os.environ.get("PLAY_TEST_ACCOUNT_IDS", "").split(",")
    if account_id not in testers or not os.environ.get("GOOGLE_PLAY_CREDENTIALS_SECRET_ID"):
        return None
    return {
        "productId": "premium_monthly", "basePlanId": "monthly",
        "accountBinding": hashlib.sha256(("greenhouse-play:" + account_id).encode()).hexdigest(),
    }


def entitlement(principal: Principal):
    item = table.get_item(Key={"pk": f"ACCOUNT#{principal.account_id}", "sk": "ENTITLEMENT"}, ConsistentRead=True).get("Item", {})
    if item.get("accountId") != principal.account_id:
        raise AccessDenied("entitlement_missing")
    effective = effective_video_entitlement(item)
    return response(200, {
        "accountId": principal.account_id, "plan": effective.get("plan", "FREE"), "state": effective.get("state", "EXPIRED"),
        "source": effective.get("source", "NONE"), "features": effective.get("features", []),
        "expiresAt": effective.get("expiresAt"), "verifiedAt": effective.get("verifiedAt", 0),
        "videoAccessMode": effective.get("videoAccessMode", "SUBSCRIPTION_CONTROLLED"),
        "checkout": play_test_checkout(principal.account_id) if effective.get("videoAccessMode") != "DISABLED" else None,
    })


def verify_google_play_subscription(event, principal: Principal):
    require_client_requester(principal)
    checkout = play_test_checkout(principal.account_id)
    if not checkout:
        raise AccessDenied("test_checkout_unavailable")
    body = json.loads(event.get("body") or "{}")
    product_id = str(body.get("productId") or "")[:160]
    purchase_token = str(body.get("purchaseToken") or "")
    if product_id != checkout["productId"] or len(purchase_token) < 16 or len(purchase_token) > 4096:
        return response(400, {"error": "valid_google_play_purchase_required"})
    verification = verify_google_play_purchase(product_id, purchase_token, test_account_binding=checkout["accountBinding"])
    now = int(datetime.now(timezone.utc).timestamp())
    if not verification.get("testPurchase") or verification.get("accountBinding") != checkout["accountBinding"]:
        raise AccessDenied("purchase_not_eligible")
    if not verification["active"] or verification["expiresAt"] <= now:
        return response(409, {"error": "purchase_inactive"})
    state = "ACTIVE"
    features = ["REMOTE_LIVE_VIDEO"]
    account = table.get_item(
        Key={"pk": f"ACCOUNT#{principal.account_id}", "sk": "METADATA"}, ConsistentRead=True
    ).get("Item", {})
    table.update_item(
        Key={"pk": f"ACCOUNT#{principal.account_id}", "sk": "ENTITLEMENT"},
        UpdateExpression=(
            "SET plan=:plan, #state=:state, #source=:source, features=:features, verifiedAt=:now, "
            "expiresAt=:expires, productId=:product, purchaseToken=:token, purchaseTokenHash=:tokenHash, "
            "verificationStatus=:verification, adminEntity=:entity, adminSort=:sort REMOVE verificationError"
        ),
        ConditionExpression="accountId=:account AND tenantId=:tenant",
        ExpressionAttributeNames={"#state": "state", "#source": "source"},
        ExpressionAttributeValues={
            ":plan": "PREMIUM", ":state": state, ":source": "GOOGLE_PLAY_VERIFIED",
            ":features": features, ":now": now, ":expires": verification["expiresAt"],
            ":product": product_id, ":token": purchase_token,
            ":tokenHash": hashlib.sha256(purchase_token.encode("utf-8")).hexdigest(),
            ":verification": "VERIFIED", ":entity": "ENTITLEMENT",
            ":sort": f"{int(account.get('createdAt', now)):020d}#{principal.account_id}",
            ":account": principal.account_id, ":tenant": principal.tenant_id,
        },
    )
    return response(200, {
        "plan": "PREMIUM", "state": state, "source": "GOOGLE_PLAY_VERIFIED",
        "features": features, "verifiedAtEpochMs": now * 1000,
        "expiresAtEpochMs": int(verification["expiresAt"]) * 1000,
    })


def pending_alerts(principal: Principal):
    device = get_owned("DEVICE", principal.device_id, principal.tenant_id)
    if not device.get("enabled") or device.get("accountId") != principal.account_id:
        raise AccessDenied("device_disabled")
    alerts = []
    server_states = {}
    def current_offline_incident(item):
        server_id = item.get("deviceId")
        if not server_id or not item.get("greenhouseId"):
            return False
        if server_id not in server_states:
            server = table.get_item(Key={"pk": f"DEVICE#{server_id}", "sk": "METADATA"}, ConsistentRead=True).get("Item", {})
            server_states[server_id] = server
        server = server_states[server_id]
        return (server.get("tenantId") == principal.tenant_id and server.get("accountId") == principal.account_id
                and server.get("greenhouseId") == item["greenhouseId"] and server.get("role") == "GREENHOUSE_SERVER"
                and server.get("enabled") and not server.get("communicationSuspended")
                and server.get("offlineAlertedAt") == item["createdAt"])

    exclusive_start_key = None
    for _ in range(MAX_ALERT_QUERY_PAGES):
        query = {
            "KeyConditionExpression": Key("pk").eq(f"ACCOUNT#{principal.account_id}") & Key("sk").begins_with("ALERT#"),
            "ScanIndexForward": False,
            "Limit": 50,
        }
        if exclusive_start_key:
            query["ExclusiveStartKey"] = exclusive_start_key
        result = table.query(**query)
        for item in result.get("Items", []):
            if item.get("tenantId") == principal.tenant_id and "acknowledgedAt" not in item:
                if item["type"] == "SERVER_OFFLINE" and not current_offline_incident(item):
                    continue
                alerts.append({
                    "id": item["id"], "greenhouseId": item.get("greenhouseId"),
                    "deviceId": item.get("deviceId"), "type": item["type"],
                    "priority": item["priority"], "message": item["message"],
                    "createdAtEpochMs": int(item["createdAt"]) * 1000,
                    "requiresAcknowledgement": bool(item.get("requiresAcknowledgement")),
                })
                if len(alerts) >= 50:
                    return response(200, {"alerts": alerts, "offlineIncidentsVerified": True, "complete": False})
        exclusive_start_key = result.get("LastEvaluatedKey")
        if not exclusive_start_key:
            break
    return response(200, {"alerts": alerts, "offlineIncidentsVerified": True, "complete": not exclusive_start_key})


def acknowledge_alert(principal: Principal, alert_id: str):
    require_resource_id(alert_id)
    device = get_owned("DEVICE", principal.device_id, principal.tenant_id)
    if not device.get("enabled") or device.get("accountId") != principal.account_id:
        raise AccessDenied("device_disabled")
    now = int(datetime.now(timezone.utc).timestamp())
    table.update_item(
        Key={"pk": f"ACCOUNT#{principal.account_id}", "sk": f"ALERT#{alert_id}"},
        UpdateExpression="SET acknowledgedAt=:now, acknowledgedBy=:device",
        ConditionExpression="tenantId=:tenant AND accountId=:account AND attribute_exists(id)",
        ExpressionAttributeValues={
            ":now": now, ":device": principal.device_id,
            ":tenant": principal.tenant_id, ":account": principal.account_id,
        },
    )
    return response(204, {})


def heartbeat(event, principal: Principal, device_id: str):
    if principal.device_id != device_id:
        raise AccessDenied("device_mismatch")
    device = get_owned("DEVICE", device_id, principal.tenant_id)
    if device.get("role") != "GREENHOUSE_SERVER" or not device.get("enabled") or device.get("communicationSuspended"):
        raise AccessDenied("server_device")
    body = json.loads(event.get("body") or "{}")
    now = int(datetime.now(timezone.utc).timestamp())
    shard = sum(device_id.encode()) % 16
    sensor_healthy = bool(body.get("sensorConnectionHealthy"))
    critical_temperature = bool(body.get("criticalTemperature"))
    app_version = str(body.get("appVersion", ""))[:32]
    sensor_count = body.get("sensorCount")
    sensor_provider = str(body.get("sensorProvider", ""))[:64]
    camera_status = str(body.get("cameraStatus", ""))[:32]
    connection_error = str(body.get("connectionError", ""))[:240]
    battery_percent = body.get("batteryPercent")
    charging = body.get("charging")
    network_type = str(body.get("networkType", ""))[:32]
    wifi_signal = body.get("wifiSignalStrength")
    last_sensor_sync_ms = body.get("lastSensorSyncEpochMs")
    last_data_upload_ms = body.get("lastDataUploadEpochMs")
    set_parts = [
        "lastHeartbeat=:now", "heartbeatShard=:shard", "sensorConnectionHealthy=:sensor",
        "criticalTemperature=:critical", "adminEntity=:adminEntity", "adminSort=:adminSort",
    ]
    remove_parts = ["offlineAlertedAt"]
    expression_values = {
        ":now": now, ":shard": shard, ":sensor": sensor_healthy,
        ":critical": critical_temperature, ":tenant": principal.tenant_id,
        ":adminEntity": "DEVICE",
        ":adminSort": f"{int(device.get('createdAt', now)):020d}#{device_id}",
    }
    if app_version:
        set_parts.append("appVersion=:appVersion")
        expression_values[":appVersion"] = app_version
    if isinstance(sensor_count, int) and 0 <= sensor_count <= 10000:
        set_parts.append("sensorCount=:sensorCount")
        expression_values[":sensorCount"] = sensor_count
    if sensor_provider:
        set_parts.append("sensorProvider=:sensorProvider")
        expression_values[":sensorProvider"] = sensor_provider
    if camera_status:
        set_parts.append("cameraStatus=:cameraStatus")
        expression_values[":cameraStatus"] = camera_status
    if isinstance(battery_percent, int) and 0 <= battery_percent <= 100:
        set_parts.append("batteryPercent=:batteryPercent")
        expression_values[":batteryPercent"] = battery_percent
    if isinstance(charging, bool):
        set_parts.append("charging=:charging")
        expression_values[":charging"] = charging
    if network_type:
        set_parts.append("networkType=:networkType")
        expression_values[":networkType"] = network_type
    if isinstance(wifi_signal, int) and -127 <= wifi_signal <= 0:
        set_parts.append("wifiSignalStrength=:wifiSignalStrength")
        expression_values[":wifiSignalStrength"] = wifi_signal
    if isinstance(last_sensor_sync_ms, int) and 0 < last_sensor_sync_ms <= (now + 300) * 1000:
        set_parts.append("lastSensorSync=:lastSensorSync")
        expression_values[":lastSensorSync"] = last_sensor_sync_ms // 1000
    if isinstance(last_data_upload_ms, int) and 0 < last_data_upload_ms <= (now + 300) * 1000:
        set_parts.append("lastDataUpload=:lastDataUpload")
        expression_values[":lastDataUpload"] = last_data_upload_ms // 1000
    if connection_error:
        set_parts.append("connectionError=:connectionError")
        expression_values[":connectionError"] = connection_error
    else:
        remove_parts.append("connectionError")
    if sensor_healthy:
        remove_parts.append("sensorAlertedAt")
    else:
        set_parts.append("sensorAlertedAt=:now")
    if critical_temperature:
        set_parts.append("criticalAlertedAt=:now")
    else:
        remove_parts.append("criticalAlertedAt")
    alert_records = []
    marker_conditions = []
    if not sensor_healthy and "sensorAlertedAt" not in device:
        marker_conditions.append("attribute_not_exists(sensorAlertedAt)")
        alert_records.append(new_alert(
            account_id=principal.account_id, tenant_id=principal.tenant_id,
            greenhouse_id=device.get("greenhouseId"), device_id=device_id,
            alert_type="SENSOR_COMMUNICATION_FAILURE", created_at=now,
        ))
    if critical_temperature and "criticalAlertedAt" not in device:
        marker_conditions.append("attribute_not_exists(criticalAlertedAt)")
        alert_records.append(new_alert(
            account_id=principal.account_id, tenant_id=principal.tenant_id,
            greenhouse_id=device.get("greenhouseId"), device_id=device_id,
            alert_type="CRITICAL_TEMPERATURE", created_at=now,
        ))
    condition = "tenantId=:tenant"
    if marker_conditions:
        condition += " AND " + " AND ".join(marker_conditions)
    transaction = [{"Update": {
        "TableName": table.name,
        "Key": {"pk": f"DEVICE#{device_id}", "sk": "METADATA"},
        "UpdateExpression": f"SET {', '.join(set_parts)} REMOVE {', '.join(remove_parts)}",
        "ExpressionAttributeValues": expression_values,
        "ConditionExpression": condition,
    }}]
    transaction.extend({"Put": {
        "TableName": table.name, "Item": alert,
        "ConditionExpression": "attribute_not_exists(pk)",
    }} for alert in alert_records)
    table.meta.client.transact_write_items(TransactItems=transaction)
    return response(204, {})


def handler(event, _context):
    try:
        method = event.get("requestContext", {}).get("http", {}).get("method")
        parts = route_parts(event)
        if method == "POST" and parts == ["v1", "public-server-pairing", "requests"]:
            return request_server_pairing(event)
        if method == "POST" and parts == ["v1", "public-server-pairing", "status"]:
            return server_pairing_status(event)
        if method == "POST" and parts == ["v1", "bootstrap"]:
            return bootstrap_account(event)
        device_authenticated = len(parts) >= 2 and parts[:2] == ["v1", "device"]
        if device_authenticated:
            parts = ["v1", *parts[2:]]
            principal = device_principal_from(event)
        else:
            principal = principal_from(event)
        if method == "POST" and parts == ["v1", "devices"]:
            role = json.loads(event.get("body") or "{}").get("role")
            return register_client_device(event, principal) if role == "VIEWER_CLIENT" else register_server_device(event, principal)
        if method == "GET" and parts == ["v1", "devices", "current"]:
            return current_device(principal)
        if device_authenticated and not (
            (method == "GET" and len(parts) == 4 and parts[:2] == ["v1", "devices"] and parts[3] == "stream-sessions") or
            (method == "POST" and len(parts) == 4 and parts[:2] == ["v1", "devices"] and parts[3] == "heartbeat") or
            (method in {"POST", "DELETE"} and len(parts) in {3, 4} and parts[:2] == ["v1", "stream-sessions"]) or
            (method == "GET" and parts == ["v1", "alerts"]) or
            (method == "POST" and len(parts) == 4 and parts[:2] == ["v1", "alerts"] and parts[3] == "acknowledge") or
            (method in {"GET", "POST"} and len(parts) == 4 and parts[:2] == ["v1", "greenhouses"] and parts[3] == "backup")
        ):
            raise AccessDenied("device_route_not_allowed")
        if method == "GET" and parts == ["v1", "devices"]:
            return list_devices(principal)
        if method == "POST" and len(parts) == 4 and parts[:2] == ["v1", "devices"] and parts[3] == "state":
            return set_device_state(event, principal, parts[2])
        if method == "POST" and len(parts) == 4 and parts[:2] == ["v1", "devices"] and parts[3] == "connection":
            return set_device_connection(event, principal, parts[2])
        if method == "POST" and len(parts) == 4 and parts[:2] == ["v1", "devices"] and parts[3] == "pairing-challenges":
            return create_pairing_challenge(principal, parts[2])
        if method == "POST" and parts == ["v1", "pairing-challenges", "claim"]:
            return claim_pairing_challenge(event, principal)
        if method == "POST" and parts == ["v1", "server-pairing", "invitations"]:
            return create_server_invitation(event, principal)
        if method == "GET" and parts == ["v1", "server-pairing", "requests"]:
            return list_server_pairing_requests(principal)
        if method == "POST" and len(parts) == 5 and parts[:3] == ["v1", "server-pairing", "requests"] and parts[4] == "approve":
            return approve_server_pairing(principal, parts[3])
        if method == "POST" and len(parts) == 4 and parts[:2] == ["v1", "greenhouses"] and parts[3] == "stream-sessions":
            return create_stream_session(event, principal, parts[2])
        if method in {"GET", "POST"} and len(parts) == 4 and parts[:2] == ["v1", "greenhouses"] and parts[3] == "backup":
            return greenhouse_backup(event, principal, parts[2], write=method == "POST")
        if method == "GET" and len(parts) == 4 and parts[:2] == ["v1", "devices"] and parts[3] == "stream-sessions":
            return pending_stream_session(principal, parts[2])
        if method == "DELETE" and len(parts) == 3 and parts[:2] == ["v1", "stream-sessions"]:
            return end_stream_session(principal, parts[2])
        if method == "POST" and len(parts) == 4 and parts[:2] == ["v1", "stream-sessions"] and parts[3] == "lease":
            return renew_stream_lease(principal, parts[2])
        if method == "POST" and len(parts) == 4 and parts[:2] == ["v1", "stream-sessions"] and parts[3] == "connection":
            return refresh_stream_connection(principal, parts[2])
        if method == "GET" and parts == ["v1", "entitlement"]:
            return entitlement(principal)
        if method == "POST" and parts == ["v1", "subscriptions", "google-play", "verify"]:
            return verify_google_play_subscription(event, principal)
        if method == "GET" and parts == ["v1", "alerts"]:
            return pending_alerts(principal)
        if method == "POST" and len(parts) == 4 and parts[:2] == ["v1", "alerts"] and parts[3] == "acknowledge":
            return acknowledge_alert(principal, parts[2])
        if method == "POST" and len(parts) == 4 and parts[:2] == ["v1", "devices"] and parts[3] == "heartbeat":
            return heartbeat(event, principal, parts[2])
        return response(404, {"error": "not_found"})
    except AccessDenied as error:
        print(json.dumps({
            "event": "authorization_denied", "reason": str(error),
            "routeKey": event.get("routeKey", "unknown"),
        }))
        return response(403, {"error": str(error)})
    except ClientError as error:
        aws_error = error.response.get("Error", {})
        if aws_error.get("Code") == "ConditionalCheckFailedException":
            return response(409, {"error": "expired_used_or_conflicting"})
        print(json.dumps({
            "level": "ERROR",
            "event": "aws_dependency_error",
            "route": "/".join(route_parts(event)),
            "code": str(aws_error.get("Code") or "Unknown"),
            "message": str(aws_error.get("Message") or "")[:300],
            "cancellationReasons": [
                {
                    "code": str(reason.get("Code") or "Unknown"),
                    "message": str(reason.get("Message") or "")[:300],
                }
                for reason in error.response.get("CancellationReasons", [])
            ],
        }))
        return response(503, {"error": "dependency_error"})
    except GooglePlayVerificationError as error:
        return response(503, {"error": error.safe_code})
    except Exception:
        return response(500, {"error": "internal_error"})

