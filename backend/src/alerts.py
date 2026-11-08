from __future__ import annotations

import uuid


ALERT_RETENTION_SECONDS = 90 * 24 * 60 * 60


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
        "CRITICAL_TEMPERATURE": ("CRITICAL", "Greenhouse temperature is outside configured limits", True),
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
