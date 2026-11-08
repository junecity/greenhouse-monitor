from __future__ import annotations

import base64
import calendar
import json
import os
import time
import uuid
from dataclasses import dataclass
from datetime import datetime, timezone
from decimal import Decimal
from typing import Iterable

import boto3
from boto3.dynamodb.conditions import Key
from botocore.exceptions import ClientError

from google_play_verifier import GooglePlayVerificationError, verify_google_play_purchase


table = boto3.resource("dynamodb").Table(os.environ["TABLE_NAME"])
cognito = boto3.client("cognito-idp")
cloudwatch = boto3.client("cloudwatch")
cost_explorer = boto3.client("ce", region_name="us-east-1")

ADMIN_INDEX = os.environ.get("ADMIN_INDEX_NAME", "admin")
CUSTOMER_USER_POOL_ID = os.environ.get("CUSTOMER_USER_POOL_ID", "")
OFFLINE_TIMEOUT_SECONDS = int(os.environ.get("OFFLINE_TIMEOUT_SECONDS", "180"))
AUDIT_RETENTION_SECONDS = int(os.environ.get("ADMIN_AUDIT_RETENTION_SECONDS", str(365 * 24 * 60 * 60)))
CURRENT_ANDROID_VERSION = os.environ.get("CURRENT_ANDROID_VERSION", "")
ROLE_PRIORITY = {"ReadOnly": 1, "Support": 2, "Owner": 3}
WRITE_ROLES = {
    "customer_state": {"Owner"},
    "complimentary": {"Owner"},
    "subscription_verify": {"Owner"},
    "device_revoke": {"Owner", "Support"},
    "account_recovery": {"Owner"},
}


class AdminAccessDenied(ValueError):
    pass


class AdminConflict(ValueError):
    pass


@dataclass(frozen=True)
class AdminPrincipal:
    subject: str
    email: str
    role: str


def _json_default(value):
    if isinstance(value, Decimal):
        return int(value) if value % 1 == 0 else float(value)
    return str(value)


def response(status: int, body: dict):
    return {
        "statusCode": status,
        "headers": {
            "content-type": "application/json",
            "cache-control": "no-store, max-age=0",
            "content-security-policy": "default-src 'none'; frame-ancestors 'none'",
            "strict-transport-security": "max-age=63072000; includeSubDomains; preload",
            "x-content-type-options": "nosniff",
            "x-frame-options": "DENY",
            "referrer-policy": "no-referrer",
        },
        "body": json.dumps(body, default=_json_default),
    }


def _groups(claim) -> set[str]:
    if isinstance(claim, list):
        return {str(value) for value in claim}
    text = str(claim or "").strip().strip("[]")
    return {value.strip().strip("'\"") for value in text.split(",") if value.strip()}


def admin_principal_from(event: dict, now: int | None = None) -> AdminPrincipal:
    claims = event.get("requestContext", {}).get("authorizer", {}).get("jwt", {}).get("claims", {})
    subject = str(claims.get("sub") or "")
    if not subject:
        raise AdminAccessDenied("unauthenticated")
    now = now or int(time.time())
    try:
        if int(claims.get("exp", 0)) <= now:
            raise AdminAccessDenied("session_expired")
    except (TypeError, ValueError):
        raise AdminAccessDenied("session_expired")
    roles = _groups(claims.get("cognito:groups")) & ROLE_PRIORITY.keys()
    if not roles:
        raise AdminAccessDenied("administrator_role_required")
    role = max(roles, key=lambda value: ROLE_PRIORITY[value])
    return AdminPrincipal(subject=subject, email=str(claims.get("email") or "")[:320], role=role)


def require_permission(principal: AdminPrincipal, permission: str) -> None:
    if principal.role not in WRITE_ROLES.get(permission, set()):
        raise AdminAccessDenied("insufficient_role")


def _query_all(**kwargs) -> list[dict]:
    items: list[dict] = []
    start_key = None
    for _ in range(20):
        call = dict(kwargs)
        if start_key:
            call["ExclusiveStartKey"] = start_key
        result = table.query(**call)
        items.extend(result.get("Items", []))
        start_key = result.get("LastEvaluatedKey")
        if not start_key:
            break
    return items


def entity_items(entity: str) -> list[dict]:
    return _query_all(
        IndexName=ADMIN_INDEX,
        KeyConditionExpression=Key("adminEntity").eq(entity),
        ScanIndexForward=False,
    )


def account_items(account_id: str, prefix: str | None = None) -> list[dict]:
    condition = Key("pk").eq(f"ACCOUNT#{account_id}")
    if prefix:
        condition &= Key("sk").begins_with(prefix)
    return _query_all(KeyConditionExpression=condition, ConsistentRead=True)


def account_record(account_id: str) -> dict:
    item = table.get_item(Key={"pk": f"ACCOUNT#{account_id}", "sk": "METADATA"}, ConsistentRead=True).get("Item")
    if not item:
        raise AdminAccessDenied("customer_not_found")
    return item


def entitlement_record(account_id: str) -> dict:
    return table.get_item(Key={"pk": f"ACCOUNT#{account_id}", "sk": "ENTITLEMENT"}, ConsistentRead=True).get("Item", {})


def direct_device(device_id: str) -> dict:
    item = table.get_item(Key={"pk": f"DEVICE#{device_id}", "sk": "METADATA"}, ConsistentRead=True).get("Item")
    if not item:
        raise AdminAccessDenied("device_not_found")
    return item


def _safe_account(item: dict, entitlement: dict | None = None) -> dict:
    entitlement = entitlement or entitlement_record(item["accountId"])
    return {
        "accountId": item["accountId"],
        "tenantId": item.get("tenantId"),
        "displayName": item.get("displayName"),
        "email": item.get("email"),
        "state": item.get("state", "ACTIVE"),
        "createdAt": int(item.get("createdAt", 0)),
        "plan": entitlement.get("plan", "FREE"),
        "subscriptionState": entitlement.get("state", "ACTIVE"),
        "subscriptionSource": entitlement.get("source", "NONE"),
        "complimentary": entitlement.get("source") == "COMPLIMENTARY_BACKEND_GRANT",
        "expiresAt": int(entitlement["expiresAt"]) if entitlement.get("expiresAt") else None,
    }


def _safe_device(item: dict, now: int | None = None) -> dict:
    now = now or int(time.time())
    heartbeat = int(item.get("lastHeartbeat", 0))
    return {
        "deviceId": item.get("deviceId"),
        "accountId": item.get("accountId"),
        "tenantId": item.get("tenantId"),
        "greenhouseId": item.get("greenhouseId"),
        "role": item.get("role"),
        "enabled": bool(item.get("enabled")),
        "online": bool(item.get("enabled")) and heartbeat >= now - OFFLINE_TIMEOUT_SECONDS,
        "lastHeartbeat": heartbeat or None,
        "appVersion": item.get("appVersion"),
        "sensorCount": int(item.get("sensorCount", 0)),
        "sensorProvider": item.get("sensorProvider"),
        "cameraStatus": item.get("cameraStatus"),
        "sensorConnectionHealthy": item.get("sensorConnectionHealthy"),
        "criticalTemperature": item.get("criticalTemperature"),
        "connectionError": item.get("connectionError"),
        "createdAt": int(item.get("createdAt", 0)),
    }


def list_customers(query: str = "") -> dict:
    normalized = query.strip().casefold()[:160]
    customers = []
    for item in entity_items("ACCOUNT"):
        safe = _safe_account(item)
        haystack = " ".join(str(safe.get(key) or "") for key in ("accountId", "displayName", "email")).casefold()
        if not normalized or normalized in haystack:
            customers.append(safe)
    return {"customers": customers, "count": len(customers)}


def customer_detail(account_id: str) -> dict:
    customer = _safe_account(account_record(account_id))
    inventory = account_items(account_id)
    devices = []
    greenhouse_ids = set()
    alerts = []
    for item in inventory:
        if item.get("sk", "").startswith("DEVICE#"):
            try:
                device = direct_device(item["deviceId"])
                devices.append(_safe_device(device))
                if device.get("greenhouseId"):
                    greenhouse_ids.add(device["greenhouseId"])
            except AdminAccessDenied:
                continue
        elif item.get("sk", "").startswith("ALERT#"):
            alerts.append({
                "id": item.get("id"), "type": item.get("type"), "priority": item.get("priority"),
                "message": item.get("message"), "createdAt": int(item.get("createdAt", 0)),
                "acknowledgedAt": int(item["acknowledgedAt"]) if item.get("acknowledgedAt") else None,
            })
    greenhouses = []
    for greenhouse_id in sorted(greenhouse_ids):
        greenhouse = table.get_item(Key={"pk": f"GREENHOUSE#{greenhouse_id}", "sk": "METADATA"}, ConsistentRead=True).get("Item", {})
        if greenhouse.get("accountId") == account_id:
            greenhouses.append({
                "greenhouseId": greenhouse_id,
                "name": greenhouse.get("name"),
                "serverDeviceId": greenhouse.get("serverDeviceId"),
                "enabled": bool(greenhouse.get("enabled")),
                "createdAt": int(greenhouse.get("createdAt", 0)),
            })
    customer["devices"] = devices
    customer["greenhouses"] = greenhouses
    customer["alerts"] = sorted(alerts, key=lambda value: value["createdAt"], reverse=True)[:25]
    customer["entitlement"] = _safe_entitlement(entitlement_record(account_id))
    return customer


def _safe_entitlement(item: dict) -> dict:
    return {
        "accountId": item.get("accountId"), "plan": item.get("plan", "FREE"),
        "state": item.get("state", "ACTIVE"), "source": item.get("source", "NONE"),
        "features": list(item.get("features", [])),
        "verifiedAt": int(item.get("verifiedAt", 0)) or None,
        "expiresAt": int(item["expiresAt"]) if item.get("expiresAt") else None,
        "renewalAt": int(item["renewalAt"]) if item.get("renewalAt") else None,
        "productId": item.get("productId"),
        "verificationStatus": item.get("verificationStatus", "NOT_CONFIGURED"),
        "verificationError": item.get("verificationError"),
        "complimentary": item.get("source") == "COMPLIMENTARY_BACKEND_GRANT",
    }


def list_devices(query: str = "") -> dict:
    normalized = query.strip().casefold()[:160]
    devices = []
    for item in entity_items("DEVICE"):
        safe = _safe_device(item)
        haystack = " ".join(str(safe.get(key) or "") for key in ("deviceId", "accountId", "greenhouseId", "appVersion", "sensorProvider")).casefold()
        if not normalized or normalized in haystack:
            devices.append(safe)
    return {"devices": devices, "count": len(devices)}


def list_subscriptions() -> dict:
    values = [_safe_entitlement(item) for item in entity_items("ENTITLEMENT")]
    return {"subscriptions": values, "count": len(values)}


def _day(epoch: int) -> str:
    return datetime.fromtimestamp(epoch, timezone.utc).date().isoformat()


def _trend(items: Iterable[dict], predicate=lambda _item: True) -> list[dict]:
    counts: dict[str, int] = {}
    for item in items:
        if predicate(item) and int(item.get("createdAt", item.get("verifiedAt", 0))) > 0:
            key = _day(int(item.get("createdAt", item.get("verifiedAt", 0))))
            counts[key] = counts.get(key, 0) + 1
    return [{"date": key, "value": counts[key]} for key in sorted(counts)]


def dashboard() -> dict:
    now = int(time.time())
    accounts = entity_items("ACCOUNT")
    devices = entity_items("DEVICE")
    entitlements = entity_items("ENTITLEMENT")
    sessions = entity_items("STREAM_SESSION")
    safe_devices = [_safe_device(item, now) for item in devices]
    active_sessions = [item for item in sessions if item.get("state") != "ENDED" and int(item.get("expiresAt", 0)) > now]
    premium = [item for item in entitlements if item.get("plan") != "FREE" and item.get("state") == "ACTIVE"]
    complimentary = [item for item in premium if item.get("source") == "COMPLIMENTARY_BACKEND_GRANT"]
    server_devices = [item for item in safe_devices if item.get("role") == "GREENHOUSE_SERVER" and item.get("enabled")]
    connected_sensors = sum(item.get("sensorCount", 0) for item in safe_devices if item.get("online"))
    return {
        "generatedAt": now,
        "metrics": {
            "totalCustomers": len(accounts),
            "activeCustomers": sum(1 for item in accounts if item.get("state", "ACTIVE") == "ACTIVE"),
            "freeAccounts": sum(1 for item in entitlements if item.get("plan", "FREE") == "FREE"),
            "premiumSubscribers": len(premium) - len(complimentary),
            "complimentaryPremium": len(complimentary),
            "activeServers": sum(1 for item in server_devices if item.get("online")),
            "offlineServers": sum(1 for item in server_devices if not item.get("online")),
            "connectedSensors": connected_sensors,
            "activeCameraSessions": len(active_sessions),
        },
        "trends": {
            "customerGrowth": _trend(accounts),
            "subscriptions": _trend(entitlements, lambda item: item.get("plan") != "FREE"),
            "reliability": [{
                "date": _day(now),
                "value": round(100 * sum(1 for item in server_devices if item.get("online")) / len(server_devices), 1),
            }] if server_devices else [],
        },
        "costs": cost_snapshot(now),
        "alerts": operation_alarms(),
    }


def cost_snapshot(now: int | None = None) -> dict:
    now = now or int(time.time())
    today = datetime.fromtimestamp(now, timezone.utc).date()
    start = today.replace(day=1).isoformat()
    end = today.isoformat()
    if start == end:
        end = datetime.fromtimestamp(now + 86400, timezone.utc).date().isoformat()
    try:
        result = cost_explorer.get_cost_and_usage(
            TimePeriod={"Start": start, "End": end}, Granularity="MONTHLY",
            Metrics=["UnblendedCost"],
            Filter={"Tags": {"Key": "Product", "Values": ["GreenhouseMonitor"]}},
        )
        amount = Decimal(result.get("ResultsByTime", [{}])[0].get("Total", {}).get("UnblendedCost", {}).get("Amount", "0"))
        days_elapsed = max(1, today.day - 1)
        days_in_month = calendar.monthrange(today.year, today.month)[1]
        estimate = (amount / days_elapsed) * days_in_month
        return {"actualMonthToDateUsd": round(float(amount), 2), "estimatedMonthEndUsd": round(float(estimate), 2), "status": "AVAILABLE", "actualSource": "AWS_BILLED_TAGGED", "estimateMethod": "DAILY_RUN_RATE"}
    except Exception:
        return {"actualMonthToDateUsd": None, "estimatedMonthEndUsd": None, "status": "UNAVAILABLE", "actualSource": "AWS_BILLED_TAGGED", "estimateMethod": None}


def operation_alarms() -> list[dict]:
    try:
        result = cloudwatch.describe_alarms(AlarmNamePrefix=f"greenhouse-monitor-{os.environ.get('ENVIRONMENT_NAME', 'dev')}")
        return [{
            "name": item.get("AlarmName"), "state": item.get("StateValue"),
            "reason": item.get("StateReason"),
            "updatedAt": item.get("StateUpdatedTimestamp").isoformat() if item.get("StateUpdatedTimestamp") else None,
        } for item in result.get("MetricAlarms", [])]
    except Exception:
        return []


def operations() -> dict:
    sessions = entity_items("STREAM_SESSION")
    now = int(time.time())
    active = [item for item in sessions if item.get("state") != "ENDED" and int(item.get("expiresAt", 0)) > now]
    return {
        "generatedAt": now,
        "streaming": {
            "active": len(active),
            "direct": sum(1 for item in sessions if item.get("connectionMode") == "DIRECT"),
            "turnRelayed": sum(1 for item in sessions if item.get("connectionMode") == "TURN"),
            "failures": sum(1 for item in sessions if item.get("state") == "FAILED"),
            "reconnections": sum(int(item.get("reconnectionAttempts", 0)) for item in sessions),
            "durationSeconds": sum(int(item.get("durationSeconds", 0)) for item in sessions),
            "relayBytes": sum(int(item.get("relayBytes", 0)) for item in sessions),
        },
        "costs": cost_snapshot(now),
        "alarms": operation_alarms(),
    }


def audit(principal: AdminPrincipal, action: str, resource_type: str, resource_id: str, outcome: str, request_id: str, detail: str | None = None) -> None:
    now = int(time.time())
    item = {
        "pk": "ADMIN_AUDIT", "sk": f"{now:020d}#{uuid.uuid4().hex}",
        "actorSubject": principal.subject, "actorRole": principal.role,
        "action": action, "resourceType": resource_type, "resourceId": resource_id,
        "outcome": outcome, "requestId": request_id, "createdAt": now,
        "expiresAt": now + AUDIT_RETENTION_SECONDS,
    }
    if detail:
        item["detail"] = detail[:240]
    table.put_item(Item=item)


def list_audit() -> dict:
    values = _query_all(
        KeyConditionExpression=Key("pk").eq("ADMIN_AUDIT"),
        ScanIndexForward=False,
        Limit=200,
    )
    return {"events": [{
        "id": item.get("sk"), "actorSubject": item.get("actorSubject"),
        "actorRole": item.get("actorRole"), "action": item.get("action"),
        "resourceType": item.get("resourceType"), "resourceId": item.get("resourceId"),
        "outcome": item.get("outcome"), "createdAt": int(item.get("createdAt", 0)),
        "requestId": item.get("requestId"),
    } for item in values]}


def set_customer_state(principal: AdminPrincipal, account_id: str, state: str, request_id: str) -> dict:
    require_permission(principal, "customer_state")
    if state not in {"ACTIVE", "SUSPENDED"}:
        raise AdminConflict("invalid_customer_state")
    account = account_record(account_id)
    subject = account.get("cognitoSubject")
    if subject and CUSTOMER_USER_POOL_ID:
        operation = cognito.admin_enable_user if state == "ACTIVE" else cognito.admin_disable_user
        operation(UserPoolId=CUSTOMER_USER_POOL_ID, Username=subject)
    table.update_item(
        Key={"pk": f"ACCOUNT#{account_id}", "sk": "METADATA"},
        UpdateExpression="SET #state=:state, stateChangedAt=:now, stateChangedBy=:actor",
        ConditionExpression="attribute_exists(accountId)",
        ExpressionAttributeNames={"#state": "state"},
        ExpressionAttributeValues={":state": state, ":now": int(time.time()), ":actor": principal.subject},
    )
    audit(principal, "CUSTOMER_STATE_CHANGED", "CUSTOMER", account_id, "SUCCESS", request_id, state)
    return {"accountId": account_id, "state": state}


def set_complimentary(principal: AdminPrincipal, account_id: str, expires_at: int | None, request_id: str) -> dict:
    require_permission(principal, "complimentary")
    account = account_record(account_id)
    current = entitlement_record(account_id)
    if current.get("source") == "GOOGLE_PLAY_VERIFIED" and current.get("state") == "ACTIVE":
        raise AdminConflict("google_play_subscription_is_authoritative")
    now = int(time.time())
    if expires_at is not None and expires_at <= now:
        raise AdminConflict("expiration_must_be_future")
    expression = (
        "SET plan=:plan, #state=:state, #source=:source, features=:features, verifiedAt=:now, "
        "adminEntity=:entity, adminSort=:sort, grantedBy=:actor, entitlementRevision=if_not_exists(entitlementRevision,:zero)+:one"
    )
    values = {
        ":plan": "COMPLIMENTARY", ":state": "ACTIVE", ":source": "COMPLIMENTARY_BACKEND_GRANT",
        ":features": ["REMOTE_LIVE_VIDEO"], ":now": now, ":entity": "ENTITLEMENT",
        ":sort": f"{int(account.get('createdAt', now)):020d}#{account_id}", ":actor": principal.subject,
        ":zero": 0, ":one": 1,
    }
    if expires_at:
        expression += ", expiresAt=:expires"
        values[":expires"] = expires_at
    else:
        expression += " REMOVE expiresAt"
    table.update_item(
        Key={"pk": f"ACCOUNT#{account_id}", "sk": "ENTITLEMENT"},
        UpdateExpression=expression,
        ConditionExpression="accountId=:account AND tenantId=:tenant",
        ExpressionAttributeNames={"#state": "state", "#source": "source"},
        ExpressionAttributeValues={**values, ":account": account_id, ":tenant": account["tenantId"]},
    )
    audit(principal, "COMPLIMENTARY_PREMIUM_GRANTED", "ENTITLEMENT", account_id, "SUCCESS", request_id)
    return _safe_entitlement(entitlement_record(account_id) or {**current, **values})


def revoke_complimentary(principal: AdminPrincipal, account_id: str, request_id: str) -> dict:
    require_permission(principal, "complimentary")
    current = entitlement_record(account_id)
    if current.get("source") != "COMPLIMENTARY_BACKEND_GRANT":
        raise AdminConflict("complimentary_grant_not_active")
    table.update_item(
        Key={"pk": f"ACCOUNT#{account_id}", "sk": "ENTITLEMENT"},
        UpdateExpression="SET plan=:plan, #state=:state, #source=:source, features=:features, verifiedAt=:now, revokedBy=:actor REMOVE expiresAt",
        ConditionExpression="#source=:expected",
        ExpressionAttributeNames={"#state": "state", "#source": "source"},
        ExpressionAttributeValues={
            ":plan": "FREE", ":state": "ACTIVE", ":source": "NONE", ":features": [],
            ":now": int(time.time()), ":actor": principal.subject,
            ":expected": "COMPLIMENTARY_BACKEND_GRANT",
        },
    )
    audit(principal, "COMPLIMENTARY_PREMIUM_REVOKED", "ENTITLEMENT", account_id, "SUCCESS", request_id)
    return {"accountId": account_id, "plan": "FREE", "state": "ACTIVE", "source": "NONE"}


def revoke_device(principal: AdminPrincipal, device_id: str, request_id: str) -> dict:
    require_permission(principal, "device_revoke")
    device = direct_device(device_id)
    now = int(time.time())
    values = {":enabled": False, ":now": now, ":actor": principal.subject, ":account": device["accountId"], ":tenant": device["tenantId"]}
    table.meta.client.transact_write_items(TransactItems=[
        {"Update": {
            "TableName": table.name, "Key": {"pk": f"DEVICE#{device_id}", "sk": "METADATA"},
            "UpdateExpression": "SET enabled=:enabled, revokedAt=:now, revokedBy=:actor",
            "ConditionExpression": "accountId=:account AND tenantId=:tenant",
            "ExpressionAttributeValues": values,
        }},
        {"Update": {
            "TableName": table.name, "Key": {"pk": f"ACCOUNT#{device['accountId']}", "sk": f"DEVICE#{device_id}"},
            "UpdateExpression": "SET enabled=:enabled, revokedAt=:now, revokedBy=:actor",
            "ConditionExpression": "accountId=:account AND tenantId=:tenant",
            "ExpressionAttributeValues": values,
        }},
    ])
    audit(principal, "DEVICE_SESSION_REVOKED", "DEVICE", device_id, "SUCCESS", request_id)
    return {"deviceId": device_id, "enabled": False}


def initiate_recovery(principal: AdminPrincipal, account_id: str, request_id: str) -> dict:
    require_permission(principal, "account_recovery")
    account = account_record(account_id)
    subject = account.get("cognitoSubject")
    if not subject or not CUSTOMER_USER_POOL_ID:
        raise AdminConflict("customer_identity_not_linked")
    cognito.admin_user_global_sign_out(UserPoolId=CUSTOMER_USER_POOL_ID, Username=subject)
    cognito.admin_reset_user_password(UserPoolId=CUSTOMER_USER_POOL_ID, Username=subject)
    audit(principal, "ACCOUNT_RECOVERY_STARTED", "CUSTOMER", account_id, "SUCCESS", request_id)
    return {"accountId": account_id, "recovery": "PASSWORD_RESET_REQUIRED", "sessionsRevoked": True}


def reverify_subscription(principal: AdminPrincipal, account_id: str, request_id: str) -> dict:
    require_permission(principal, "subscription_verify")
    current = entitlement_record(account_id)
    token = current.get("purchaseToken")
    product_id = current.get("productId")
    if not token or not product_id:
        raise AdminConflict("google_play_purchase_not_linked")
    verification = verify_google_play_purchase(product_id=product_id, purchase_token=token)
    now = int(time.time())
    state = "ACTIVE" if verification["active"] else "EXPIRED"
    features = ["REMOTE_LIVE_VIDEO"] if verification["active"] else []
    table.update_item(
        Key={"pk": f"ACCOUNT#{account_id}", "sk": "ENTITLEMENT"},
        UpdateExpression="SET plan=:plan, #state=:state, #source=:source, features=:features, verifiedAt=:now, expiresAt=:expires, verificationStatus=:status REMOVE verificationError",
        ExpressionAttributeNames={"#state": "state", "#source": "source"},
        ExpressionAttributeValues={
            ":plan": "PREMIUM", ":state": state, ":source": "GOOGLE_PLAY_VERIFIED",
            ":features": features, ":now": now, ":expires": verification["expiresAt"], ":status": "VERIFIED",
        },
    )
    audit(principal, "GOOGLE_PLAY_SUBSCRIPTION_VERIFIED", "ENTITLEMENT", account_id, "SUCCESS", request_id)
    return {"accountId": account_id, "state": state, "source": "GOOGLE_PLAY_VERIFIED", "expiresAt": verification["expiresAt"]}


def support_workspace(query: str) -> dict:
    customers = list_customers(query)["customers"]
    customer_ids = {item["accountId"] for item in customers}
    devices = [item for item in list_devices(query)["devices"] if not customer_ids or item.get("accountId") in customer_ids]
    return {
        "customers": customers,
        "devices": devices,
        "connectionFailures": [item for item in devices if item.get("connectionError")],
        "outdatedDevices": [item for item in devices if CURRENT_ANDROID_VERSION and item.get("appVersion") and item.get("appVersion") != CURRENT_ANDROID_VERSION],
    }


def _body(event: dict) -> dict:
    try:
        return json.loads(event.get("body") or "{}")
    except json.JSONDecodeError:
        raise AdminConflict("invalid_json")


def _query(event: dict, name: str) -> str:
    return str((event.get("queryStringParameters") or {}).get(name) or "")


def route_parts(event: dict) -> list[str]:
    """Return administrator route segments for both local and staged HTTP API events."""
    parts = [value for value in event.get("rawPath", "").split("/") if value]
    stage = str(event.get("requestContext", {}).get("stage") or "").strip("/")
    if stage and parts and parts[0] == stage:
        return parts[1:]
    return parts


def handler(event, context):
    principal = None
    request_id = getattr(context, "aws_request_id", None) or event.get("requestContext", {}).get("requestId", "local")
    try:
        method = event.get("requestContext", {}).get("http", {}).get("method")
        # The browser's CORS preflight is intentionally unauthenticated. API
        # Gateway applies the configured CORS response headers to this 204.
        if method == "OPTIONS":
            return response(204, {})
        principal = admin_principal_from(event)
        parts = route_parts(event)
        if method == "GET" and parts == ["admin", "v1", "session"]:
            return response(200, {"role": principal.role, "email": principal.email})
        if method == "GET" and parts == ["admin", "v1", "dashboard"]:
            return response(200, dashboard())
        if method == "GET" and parts == ["admin", "v1", "customers"]:
            return response(200, list_customers(_query(event, "query")))
        if method == "GET" and len(parts) == 4 and parts[:3] == ["admin", "v1", "customers"]:
            return response(200, customer_detail(parts[3]))
        if method == "POST" and len(parts) == 5 and parts[:3] == ["admin", "v1", "customers"] and parts[4] == "state":
            return response(200, set_customer_state(principal, parts[3], str(_body(event).get("state")), request_id))
        if method == "POST" and len(parts) == 5 and parts[:3] == ["admin", "v1", "customers"] and parts[4] == "complimentary":
            expires = _body(event).get("expiresAt")
            return response(200, set_complimentary(principal, parts[3], int(expires) if expires else None, request_id))
        if method == "DELETE" and len(parts) == 5 and parts[:3] == ["admin", "v1", "customers"] and parts[4] == "complimentary":
            return response(200, revoke_complimentary(principal, parts[3], request_id))
        if method == "POST" and len(parts) == 5 and parts[:3] == ["admin", "v1", "customers"] and parts[4] == "recovery":
            return response(202, initiate_recovery(principal, parts[3], request_id))
        if method == "GET" and parts == ["admin", "v1", "devices"]:
            return response(200, list_devices(_query(event, "query")))
        if method == "POST" and len(parts) == 5 and parts[:3] == ["admin", "v1", "devices"] and parts[4] == "revoke":
            return response(200, revoke_device(principal, parts[3], request_id))
        if method == "GET" and parts == ["admin", "v1", "subscriptions"]:
            return response(200, list_subscriptions())
        if method == "POST" and len(parts) == 5 and parts[:3] == ["admin", "v1", "subscriptions"] and parts[4] == "verify":
            return response(200, reverify_subscription(principal, parts[3], request_id))
        if method == "GET" and parts == ["admin", "v1", "operations"]:
            return response(200, operations())
        if method == "GET" and parts == ["admin", "v1", "audit"]:
            return response(200, list_audit())
        if method == "GET" and parts == ["admin", "v1", "support"]:
            return response(200, support_workspace(_query(event, "query")))
        return response(404, {"error": "not_found"})
    except AdminAccessDenied as error:
        if principal:
            try:
                audit(principal, "AUTHORIZATION_DENIED", "ADMIN_API", event.get("rawPath", ""), "DENIED", request_id, str(error))
            except Exception:
                pass
        return response(403, {"error": str(error)})
    except AdminConflict as error:
        return response(409, {"error": str(error)})
    except GooglePlayVerificationError as error:
        return response(503, {"error": error.safe_code})
    except ClientError:
        return response(503, {"error": "dependency_error"})
    except Exception:
        return response(500, {"error": "internal_error"})
