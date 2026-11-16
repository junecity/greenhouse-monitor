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
    name = " ".join(name.split())[:96]
    measured = breach.get("measuredAt")
    if type(measured) is not int or not now - 900 <= measured <= now + 300:
        return None
    value, limit = breach.get("temperatureF"), breach.get("limitF")
    if not all(type(v) in (int, float) and -460 <= v <= 1000 and math.isfinite(v) for v in (value, limit)):
        return None
    direction = breach.get("direction")
    if direction == "LOW" and value <= limit:
        comparison = "at" if value == limit else "below"
        boundary = "low"
    elif direction == "HIGH" and value >= limit:
        comparison = "at" if value == limit else "above"
        boundary = "high"
    else:
        return None
    return f"{name} measured {value:g}°F, {comparison} the {boundary} limit of {limit:g}°F."


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
    alert_id = alert_id or f"{created_at:010d}-{uuid.uuid4().hex}"
    definitions = {
        "SERVER_OFFLINE": ("HIGH", "Greenhouse server is offline", True),
        "SENSOR_COMMUNICATION_FAILURE": ("HIGH", "Greenhouse sensors are not reporting", False),
        "CRITICAL_TEMPERATURE": ("CRITICAL", LEGACY_TEMPERATURE_MESSAGE, True),
    }
    priority, message, requires_acknowledgement = definitions[alert_type]
    return {
        "pk": f"ACCOUNT#{account_id}",
        "sk": f"ALERT#{alert_id}",
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
