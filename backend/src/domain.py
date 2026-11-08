from dataclasses import dataclass
from enum import Enum
from time import time
from typing import Optional


class AccessDenied(ValueError):
    pass


class EntitlementState(str, Enum):
    ACTIVE = "ACTIVE"
    EXPIRED = "EXPIRED"
    REVOKED = "REVOKED"
    PENDING = "PENDING"


@dataclass(frozen=True)
class Principal:
    account_id: str
    tenant_id: str
    device_id: str


def require_same_tenant(principal: Principal, *tenant_ids: str) -> None:
    if not tenant_ids or any(value != principal.tenant_id for value in tenant_ids):
        raise AccessDenied("cross_tenant")


def require_remote_video_entitlement(principal: Principal, entitlement: dict, now_epoch: Optional[int] = None) -> None:
    now_epoch = now_epoch or int(time())
    if entitlement.get("accountId") != principal.account_id:
        raise AccessDenied("cross_tenant")
    state = entitlement.get("state")
    if state == EntitlementState.REVOKED.value:
        raise AccessDenied("revoked")
    if state != EntitlementState.ACTIVE.value:
        raise AccessDenied("inactive")
    expires_at = entitlement.get("expiresAt")
    if expires_at is not None and int(expires_at) <= now_epoch:
        raise AccessDenied("expired")
    if "REMOTE_LIVE_VIDEO" not in entitlement.get("features", []):
        raise AccessDenied("premium_required")
    if entitlement.get("source") not in {"GOOGLE_PLAY_VERIFIED", "COMPLIMENTARY_BACKEND_GRANT"}:
        raise AccessDenied("unverified")


def authorize_stream(principal: Principal, viewer: dict, server: dict, greenhouse: dict, entitlement: dict, now_epoch: Optional[int] = None) -> None:
    require_same_tenant(principal, viewer["tenantId"], server["tenantId"], greenhouse["tenantId"])
    if viewer.get("deviceId") != principal.device_id or viewer.get("role") != "VIEWER_CLIENT":
        raise AccessDenied("viewer_device")
    if server.get("role") != "GREENHOUSE_SERVER":
        raise AccessDenied("server_device")
    if not viewer.get("enabled", False) or not server.get("enabled", False):
        raise AccessDenied("disabled")
    require_remote_video_entitlement(principal, entitlement, now_epoch)


def is_missing_heartbeat(device: dict, cutoff_epoch: int) -> bool:
    """Pure predicate used by the scheduled monitor and its unit tests."""
    return bool(device.get("enabled")) and int(device.get("lastHeartbeat", 0)) < cutoff_epoch

