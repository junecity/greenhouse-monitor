from __future__ import annotations

import json
import os
from datetime import datetime, timezone
from urllib.parse import quote

import boto3


class GooglePlayVerificationError(RuntimeError):
    def __init__(self, safe_code: str):
        super().__init__(safe_code)
        self.safe_code = safe_code


def _epoch_seconds(value: str) -> int:
    return int(datetime.fromisoformat(value.replace("Z", "+00:00")).astimezone(timezone.utc).timestamp())


def parse_subscription(payload: dict, expected_product_id: str, *, base_plan: str | None = None) -> dict:
    line_items = payload.get("lineItems") or []
    matching = [item for item in line_items if item.get("productId") == expected_product_id]
    if not matching:
        raise GooglePlayVerificationError("google_play_product_mismatch")
    if base_plan:
        matching = [item for item in matching if (item.get("offerDetails") or {}).get("basePlanId") == base_plan]
        if not matching:
            raise GooglePlayVerificationError("purchase_not_eligible")
    state = payload.get("subscriptionState", "")
    expirations = [_epoch_seconds(item["expiryTime"]) for item in matching if item.get("expiryTime")]
    if not expirations and state not in {"SUBSCRIPTION_STATE_PENDING", "SUBSCRIPTION_STATE_PENDING_PURCHASE_CANCELED"}:
        raise GooglePlayVerificationError("google_play_expiration_missing")
    expires_at = max(expirations, default=0)
    active = state in {"SUBSCRIPTION_STATE_ACTIVE", "SUBSCRIPTION_STATE_IN_GRACE_PERIOD", "SUBSCRIPTION_STATE_CANCELED"} and expires_at > datetime.now(timezone.utc).timestamp()
    return {
        "active": active,
        "testPurchase": isinstance(payload.get("testPurchase"), dict),
        "accountBinding": (payload.get("externalAccountIdentifiers") or {}).get("obfuscatedExternalAccountId"),
        "expiresAt": expires_at,
        "startedAt": _epoch_seconds(payload["startTime"]) if payload.get("startTime") else None,
        "linkedPurchaseToken": payload.get("linkedPurchaseToken"),
        "subscriptionState": state,
        "acknowledgementState": payload.get("acknowledgementState"),
        "latestOrderId": payload.get("latestOrderId"),
    }


def verify_google_play_purchase(product_id: str, purchase_token: str, *, test_account_binding: str | None = None,
                                account_binding: str | None = None, require_test_purchase: bool = True) -> dict:
    package_name = os.environ.get("GOOGLE_PLAY_PACKAGE_NAME", "")
    secret_id = os.environ.get("GOOGLE_PLAY_CREDENTIALS_SECRET_ID", "")
    if not package_name or not secret_id:
        raise GooglePlayVerificationError("google_play_not_configured")
    if not product_id or not purchase_token:
        raise GooglePlayVerificationError("google_play_purchase_missing")
    try:
        from google.auth.transport.requests import AuthorizedSession
        from google.oauth2 import service_account

        secret = boto3.client("secretsmanager").get_secret_value(SecretId=secret_id)
        credentials_info = json.loads(secret["SecretString"])
        credentials = service_account.Credentials.from_service_account_info(
            credentials_info,
            scopes=["https://www.googleapis.com/auth/androidpublisher"],
        )
        session = AuthorizedSession(credentials)
        url = (
            "https://androidpublisher.googleapis.com/androidpublisher/v3/applications/"
            f"{quote(package_name, safe='')}/purchases/subscriptionsv2/tokens/{quote(purchase_token, safe='')}"
        )
        result = session.get(url, timeout=10)
        if result.status_code in {400, 404, 410}:
            raise GooglePlayVerificationError("google_play_purchase_invalid")
        if result.status_code == 429 or result.status_code >= 500:
            raise GooglePlayVerificationError("google_play_unavailable")
        if result.status_code != 200:
            raise GooglePlayVerificationError("google_play_verification_failed")
        payload = result.json()
        bound_account = test_account_binding or account_binding
        verified = parse_subscription(payload, product_id, base_plan="monthly" if bound_account is not None else None)
        if bound_account is not None and verified["active"]:
            if verified["testPurchase"] != require_test_purchase or verified["accountBinding"] != bound_account:
                raise GooglePlayVerificationError("purchase_not_eligible")
            if verified["acknowledgementState"] == "ACKNOWLEDGEMENT_STATE_PENDING":
                ack_url = (
                    "https://androidpublisher.googleapis.com/androidpublisher/v3/applications/"
                    f"{quote(package_name, safe='')}/purchases/subscriptions/{quote(product_id, safe='')}"
                    f"/tokens/{quote(purchase_token, safe='')}:acknowledge"
                )
                if not session.post(ack_url, json={}, timeout=10).ok:
                    raise GooglePlayVerificationError("google_play_acknowledgement_failed")
            elif verified["acknowledgementState"] != "ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED":
                raise GooglePlayVerificationError("google_play_acknowledgement_unknown")
        return verified
    except GooglePlayVerificationError:
        raise
    except Exception as error:
        raise GooglePlayVerificationError("google_play_unavailable") from error
