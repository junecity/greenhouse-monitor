"""Reconcile authenticated Google Play archive notifications with the current purchase."""
import base64
import hashlib
import json
import os
import re
import time

import camera_archive
from google_play_verifier import GooglePlayVerificationError, verify_google_play_purchase


def trusted_sender(event):
    # API Gateway's Google JWT authorizer verifies the signature and token lifetime.
    # Its claim values can be strings, including the email_verified boolean.
    claims = event.get("requestContext", {}).get("authorizer", {}).get("jwt", {}).get("claims") or {}
    sender = os.environ.get("GOOGLE_PLAY_RTDN_SERVICE_ACCOUNT")
    audience = os.environ.get("GOOGLE_PLAY_RTDN_AUDIENCE")
    return bool(sender and audience and claims.get("iss") == "https://accounts.google.com"
                and claims.get("aud") == audience and claims.get("email") == sender
                and (claims.get("email_verified") is True or claims.get("email_verified") == "true"))


def pubsub_message(event):
    body = event.get("body")
    if not isinstance(body, str) or len(body) > 131072:
        raise ValueError("invalid body")
    if event.get("isBase64Encoded"):
        body = base64.b64decode(body, validate=True)
    envelope = json.loads(body)
    if not isinstance(envelope, dict) or not isinstance(envelope.get("message"), dict):
        raise ValueError("invalid envelope")
    message = envelope["message"]
    subscription = envelope.get("subscription")
    message_id = message.get("messageId")
    if not all(isinstance(field, str) and 0 < len(field) <= 512 for field in (message_id, subscription)):
        raise ValueError("invalid delivery identity")
    if not isinstance(message.get("data"), str):
        raise ValueError("missing message")
    notice = json.loads(base64.b64decode(message["data"], validate=True))
    if not isinstance(notice, dict) or not isinstance(notice.get("packageName"), str):
        raise ValueError("invalid notification")
    delivery = hashlib.sha256((subscription + "\0" + message_id).encode()).hexdigest()
    return notice, {"pk": "CAMERA_RTDN#" + delivery, "sk": "PROCESSED"}


def archive_token(notice):
    if "subscriptionNotification" in notice:
        change = notice["subscriptionNotification"]
        if not isinstance(change, dict):
            raise ValueError("invalid subscription")
        # Older payloads may contain a product ID. Current subscriptions are verified by token.
        if change.get("subscriptionId") not in (None, camera_archive.PRODUCT):
            return None
    elif "voidedPurchaseNotification" in notice:
        change = notice["voidedPurchaseNotification"]
        if not isinstance(change, dict):
            raise ValueError("invalid void")
        if change.get("productType") != 1:  # SUBSCRIPTION
            return None
    else:
        return None
    token = change.get("purchaseToken")
    if not isinstance(token, str) or not 16 <= len(token) <= 4096:
        raise ValueError("invalid token")
    return token


def lookup_purchase(token, *, recently_purchased=False):
    # Read-only discovery: the account-bound call in paid_period handles acknowledgement.
    try:
        return verify_google_play_purchase(camera_archive.PRODUCT, token)
    except GooglePlayVerificationError as error:
        if recently_purchased and error.safe_code == "google_play_purchase_invalid":
            # A freshly announced purchase may not yet be visible to subscriptionsv2.get.
            raise GooglePlayVerificationError("google_play_purchase_not_visible") from error
        if error.safe_code in camera_archive.INVALID_PURCHASE:
            return {}
        raise


def replaces_current(purchase, current_token):
    if not purchase.get("active"):
        return False
    if purchase.get("linkedPurchaseToken") == current_token:
        return True
    # A resubscribe may have no linked token. A delayed notification about an older
    # purchase must not replace a newer, still-active subscription for this account.
    current = lookup_purchase(current_token)
    return (not current.get("active") or
            (purchase.get("startedAt", 0) or 0) > (current.get("startedAt", 0) or 0))


def reconcile(table, token, catalog_for, recently_purchased):
    purchase = lookup_purchase(token, recently_purchased=recently_purchased)
    binding = purchase.get("accountBinding")
    testing = os.environ.get("CAMERA_ARCHIVE_SALES_MODE") != "Production"
    if purchase.get("testPurchase") != testing or not isinstance(binding, str) or not re.fullmatch("[0-9a-f]{64}", binding):
        return
    owner = table.get_item(Key={"pk": "CAMERA_BINDING#" + binding, "sk": "OWNER"}, ConsistentRead=True).get("Item", {})
    account = owner.get("accountId")
    if not account or (testing and account not in os.environ.get("PLAY_TEST_ACCOUNT_IDS", "").split(",")):
        return
    catalog = catalog_for(account, camera_archive.PRODUCT)
    metadata = table.get_item(Key={"pk": "ACCOUNT#" + account, "sk": "METADATA"}, ConsistentRead=True).get("Item", {})
    if (catalog["accountBinding"] != binding or not owner.get("tenantId") or
            (metadata.get("accountId"), metadata.get("tenantId")) != (account, owner["tenantId"])):
        return
    key = {"pk": "ACCOUNT#" + account, "sk": "CAMERA_STORAGE"}
    grant = table.get_item(Key=key, ConsistentRead=True).get("Item", {})
    current_token = grant.get("purchaseToken")
    if current_token and current_token != token and not replaces_current(purchase, current_token):
        return
    camera_archive.paid_period(table, key, catalog, testing, token, purchasing=True,
                               expected_revision=grant.get("revision"))


def handle(event, *, table, catalog_for, response):
    if not trusted_sender(event):
        return response(403, {"error": "untrusted_play_notification"})
    try:
        notice, delivery_key = pubsub_message(event)
        if notice["packageName"] != os.environ.get("GOOGLE_PLAY_PACKAGE_NAME"):
            return response(200, {})
        token = archive_token(notice)
        # Pub/Sub redelivers unacknowledged messages. Allow time for a newly completed
        # purchase to become queryable, bounded by the Play acknowledgement deadline.
        recently_purchased = (
            bool(token)
            and (notice.get("subscriptionNotification") or {}).get("notificationType") == 4
            and -300 <= time.time() - int(notice["eventTimeMillis"]) / 1000 < 2 * 86400
        )
    except (KeyError, ValueError, TypeError):
        return response(400, {"error": "invalid_play_notification"})
    if not token:
        return response(200, {})
    if table.get_item(Key=delivery_key, ConsistentRead=True).get("Item"):
        return response(200, {})
    try:
        reconcile(table, token, catalog_for, recently_purchased)
    except camera_archive.ArchiveGrantChanged:
        # Redelivery makes a new decision using the latest purchase and grant revision.
        return response(503, {"error": "retry_play_notification"})
    table.put_item(Item={**delivery_key, "expiresAt": int(time.time()) + 31 * 86400})
    return response(200, {})
