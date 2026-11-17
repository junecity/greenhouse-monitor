from __future__ import annotations

import math
import uuid


ALERT_RETENTION_SECONDS = 90 * 24 * 60 * 60
LEGACY_TEMPERATURE_MESSAGE = "The server reported a temperature outside its limits. This server version did not send the reading or threshold."


def temperature_breach_message(breach, now: int) -> str | None:
    if not isinstance(breach, dict):
        return None
    name = breach.get("sensorName")
    if not isinstance(name, str) or not name.strip():
        return None
    measured = breach.get("measuredAt")
    if type(measured) is not int or not now - 900 <= measured <= now + 300:
        return None
    value, limit = breach.get("temperatureF"), breach.get("limitF")
    if not all(type(v) in (int, float) and -460 <= v <= 1000 and math.isfinite(v) for v in (value, limit)):
        return None
    return sensor_breach_message(name, value, limit, breach.get("direction"), "°F")


def sensor_breach_message(name, value, limit, direction, unit):
    name = " ".join(name.split())[:96]
    if direction == "LOW" and value <= limit:
        comparison = "at" if value == limit else "below"
        boundary = "low"
    elif direction == "HIGH" and value >= limit:
        comparison = "at" if value == limit else "above"
        boundary = "high"
    else:
        return None
    return f"{name} measured {value:g}{unit}, {comparison} the {boundary} limit of {limit:g}{unit}."


def alert_record_key(alert_id):
    # Legacy inboxes scan ALERT# for routine events. Keep humidity history out of
    # that partition range even during a backend rollback or after thousands of events.
    prefix = "HUMIDITY_ALERT" if alert_id.startswith("rh-") else "ALERT"
    return f"{prefix}#{alert_id}"


def new_alert(
    *,
    account_id: str,
    tenant_id: str,
    greenhouse_id: str | None,
    device_id: str,
    alert_type: str,
    created_at: int,
    alert_id: str | None = None,
) -> dict:
    """Build an account-scoped alert record suitable for the shared DynamoDB table."""
    if alert_id is None:
        prefix = "rh-" if alert_type == "CRITICAL_HUMIDITY" else ""
        alert_id = f"{prefix}{created_at:010d}-{uuid.uuid4().hex}"
    definitions = {
        "SERVER_OFFLINE": ("HIGH", "Greenhouse server is offline", True),
        "SENSOR_COMMUNICATION_FAILURE": ("HIGH", "Greenhouse sensors are not reporting", False),
        "CRITICAL_HUMIDITY": ("CRITICAL", "Greenhouse humidity is outside the configured limits.", True),
        "CRITICAL_TEMPERATURE": ("CRITICAL", LEGACY_TEMPERATURE_MESSAGE, True),
    }
    priority, message, requires_acknowledgement = definitions[alert_type]
    return {
        "pk": f"ACCOUNT#{account_id}",
        "sk": alert_record_key(alert_id),
        "id": alert_id,
        "accountId": account_id,
        "tenantId": tenant_id,
        "greenhouseId": greenhouse_id,
        "deviceId": device_id,
        "type": alert_type,
        "priority": priority,
        "message": message,
        "requiresAcknowledgement": requires_acknowledgement,
        "createdAt": created_at,
        "expiresAt": created_at + ALERT_RETENTION_SECONDS,
    }
