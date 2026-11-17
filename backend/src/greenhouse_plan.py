"""Account-wide greenhouse capacity. Play owns the paid period; customers choose the houses."""
import copy
import hashlib
import json
import os
import re
import time

from boto3.dynamodb.conditions import Key
from botocore.exceptions import ClientError

from camera_archive import register_checkout_binding
from domain import AccessDenied, Principal
from google_play_verifier import GREENHOUSE_FAMILY, GREENHOUSE_SKU, GooglePlayVerificationError, verify_google_play_purchase


class PlanError(RuntimeError):
    def __init__(self, status, code):
        super().__init__(code)
        self.status = status


def max_extras():
    return max(1, min(999, int(os.environ.get("GREENHOUSE_PLAN_MAX_EXTRAS", "10"))))


def sku_slots(sku):
    match = GREENHOUSE_SKU.fullmatch(sku) if isinstance(sku, str) else None
    return int(match[1]) if match else 0


def checkout_mode(account):
    mode = os.environ.get("GREENHOUSE_PLAN_SALES_MODE", "Off")
    if not os.environ.get("GOOGLE_PLAY_PACKAGE_NAME") or not os.environ.get("GOOGLE_PLAY_CREDENTIALS_SECRET_ID"):
        return None
    if mode == "Production" or (mode == "LicenseTest" and account in os.environ.get("PLAY_TEST_ACCOUNT_IDS", "").split(",")):
        return mode
    return None


def plan_key(principal):
    return {"pk": "ACCOUNT#" + principal.account_id, "sk": "GREENHOUSE_PLAN"}


def read(table, principal):
    key = plan_key(principal)
    stored = table.get_item(Key=key, ConsistentRead=True).get("Item")
    if stored:
        if stored.get("tenantId") != principal.tenant_id:
            raise AccessDenied("greenhouse_plan_owner")
        return stored
    # Only greenhouse registrations already in the server's authoritative directory
    # before migration are grandfathered. Device-provided inventories cannot claim this.
    entries = []
    query = {"KeyConditionExpression": Key("pk").eq(key["pk"]) & Key("sk").begins_with("DEVICE#"), "ConsistentRead": True}
    while True:
        page = table.query(**query)
        entries.extend(row for row in page.get("Items", []) if row.get("enabled") and
                       row.get("tenantId") == principal.tenant_id and row.get("accountId") == principal.account_id
                       and row.get("role") == "GREENHOUSE_SERVER" and row.get("greenhouseId"))
        if not page.get("LastEvaluatedKey"):
            break
        query["ExclusiveStartKey"] = page["LastEvaluatedKey"]
    houses = []
    seen = set()
    for row in sorted(entries, key=lambda item: (item.get("createdAt", 0), item["greenhouseId"])):
        house_id = row["greenhouseId"]
        if house_id in seen:
            continue
        metadata = table.get_item(Key={"pk": "GREENHOUSE#" + house_id, "sk": "METADATA"}, ConsistentRead=True).get("Item", {})
        if metadata.get("tenantId") != principal.tenant_id or metadata.get("accountId") != principal.account_id:
            continue
        seen.add(house_id)
        houses.append({"id": house_id, "name": metadata.get("name") or "Greenhouse", "free": not houses,
                       "grandfathered": bool(houses), "serverGreenhouseId": house_id})
    return {**key, "tenantId": principal.tenant_id, "revision": 0, "houses": houses}


def write_params(plan):
    rev = int(plan["revision"])
    params = {"Item": {**plan, "revision": rev + 1}, "ConditionExpression": "attribute_not_exists(pk)"}
    if rev:
        params.update(ConditionExpression="revision=:old", ExpressionAttributeValues={":old": rev})
    return params


def save(table, plan):
    params = write_params(plan)
    try:
        table.put_item(**params)
    except ClientError as error:
        if error.response["Error"]["Code"] == "ConditionalCheckFailedException":
            raise PlanError(409, "greenhouse_plan_changed") from error
        raise
    return params["Item"]


def paid_slots(plan, now_ms):
    if plan.get("testPurchase") and checkout_mode(plan["pk"].removeprefix("ACCOUNT#")) != "LicenseTest":
        return 0
    return sku_slots(plan.get("productId")) if int(plan.get("paidThroughEpochMs", 0)) > now_ms else 0


def rows_at(plan, now_ms):
    rows = [dict(row) for row in plan["houses"] if not row.get("removed")]
    paid = [row for row in rows if not row.get("free") and not row.get("grandfathered")]
    # A staged pause only sets a preference. No monitoring stops until Play changes capacity.
    # Non-paused houses retain oldest-first priority across an external cancellation.
    slots = paid_slots(plan, now_ms)
    allocated = [row for row in paid if not row.get("suspended")][:slots]
    active = {row["id"] for row in allocated}
    preferred = sorted(allocated, key=lambda row: bool(row.get("pauseRequested")))
    target = plan.get("nextExtraSlots")
    continuing = active if target is None else {row["id"] for row in preferred[:int(target)]}
    for row in rows:
        row.pop("suspended", None)
        row.pop("removed", None)
        row.update(active=bool(row.get("free") or row.get("grandfathered") or row["id"] in active),
                   pauseAtEpochMs=plan.get("paidThroughEpochMs", 0) if row["id"] in active - continuing else 0,
                   pauseRequested=bool(row.get("pauseRequested")))
    return rows


def state(plan, principal, catalog_for, *, viewer=False):
    account = principal.account_id
    now_ms = int(time.time() * 1000)
    extras = paid_slots(plan, now_ms)
    mode = checkout_mode(account)
    token = plan.get("purchaseToken")
    receipt = {"purchaseTokenDigest": hashlib.sha256(token.encode()).hexdigest()} if viewer and extras and token else {}
    return {**receipt, "houses": rows_at(plan, now_ms), "revision": int(plan["revision"]),
            "extraSlots": extras, "maxExtraSlots": max_extras(),
            "paidThroughEpochMs": int(plan.get("paidThroughEpochMs", 0)),
            "productId": plan.get("productId") if extras else None,
            "nextExtraSlots": plan.get("nextExtraSlots") if extras else None,
            "renews": plan.get("renews") if extras else None,
            "accountBinding": catalog_for(account, GREENHOUSE_FAMILY)["accountBinding"],
            "checkoutAvailable": bool(mode), "licenseTest": mode == "LicenseTest"}


def house_id_of(value):
    if not isinstance(value, str) or not re.fullmatch(r"[a-zA-Z0-9][a-zA-Z0-9_.:-]{0,199}", value):
        raise PlanError(400, "invalid_house_id")
    return value


def mutate(plan, operation, house_id, name, now_ms):
    rows = plan["houses"]
    house_id = house_id_of(house_id)
    current = next((r for r in rows if r["id"] == house_id), None)
    if operation in {"rename", "activate"} and (not isinstance(name, str) or not 1 <= len(name.strip()) <= 120):
        raise PlanError(400, "invalid_greenhouse_name")
    if operation == "rename":
        if current is None or current.get("removed"):
            raise PlanError(404, "greenhouse_not_found")
        current["name"] = name.strip()
        return
    if current is None and operation in {"remove", "pause", "resume"}:
        if operation == "remove":
            return
        raise PlanError(404, "greenhouse_not_found")
    if operation == "remove":
        # Keep the server alias as a tombstone so an installed collector cannot
        # regain access by addressing its cloud ID directly after deletion.
        if not current.get("removed"):
            was_free = current.get("free")
            if current.get("serverGreenhouseId"):
                current.update(removed=True, free=False, grandfathered=False)
            else:
                rows.remove(current)
            if was_free:
                replacement = next((r for r in rows if not r.get("removed") and not r.get("grandfathered")), None)
                if replacement:
                    replacement.update(free=True, pauseRequested=False)
        return
    if operation == "pause":
        if current.get("free") or current.get("grandfathered"):
            raise PlanError(409, "included_greenhouse")
        current["pauseRequested"] = True
        return
    before = {r["id"] for r in rows_at(plan, now_ms) if r["active"]}
    if operation in {"activate", "resume"}:
        if current is None or current.get("removed"):
            if current is None:
                current = {"id": house_id}
                rows.append(current)
            current.update(free=not any(r.get("free") and not r.get("removed") for r in rows if r is not current),
                           grandfathered=False, removed=False)
        if name:
            current["name"] = name.strip()
        if operation == "resume" or current["id"] not in before:
            current["pauseRequested"] = False
            current.pop("suspended", None)
        after = {r["id"] for r in rows_at(plan, now_ms) if r["active"]}
        if current["id"] not in after or not before.issubset(after):
            raise PlanError(409, "greenhouse_plan_required")


def apply_purchase(table, principal, plan, catalog_for, token, testing):
    binding = catalog_for(principal.account_id, GREENHOUSE_FAMILY)["accountBinding"]
    current = plan.get("purchaseToken")
    # Inspect without acknowledging first. Reserve the receipt atomically before the
    # irreversible Play acknowledgement: two Android phones can finish independent
    # checkouts for the same Greenhouse account at exactly the same instant.
    if current != token:
        try:
            candidate = verify_google_play_purchase(GREENHOUSE_FAMILY, token)
        except GooglePlayVerificationError as error:
            if error.safe_code in {"purchase_not_eligible", "google_play_product_mismatch", "google_play_purchase_invalid"}:
                raise PlanError(403, "purchase_not_eligible") from error
            raise
        if candidate.get("accountBinding") != binding or candidate.get("testPurchase") is not testing:
            raise PlanError(403, "purchase_not_eligible")
        if not candidate.get("active"):
            return plan
        if not 1 <= sku_slots(candidate.get("productId")) <= max_extras():
            raise PlanError(403, "purchase_not_eligible")
        reserved = plan.get("confirmingToken")
        for previous_token in {current, reserved} - {None, token, candidate.get("linkedPurchaseToken")}:
            # Ignore an older notification without ever acknowledging another active
            # order. Two unlinked active purchases with equal timestamps are a conflict.
            if previous_token == current and int(candidate.get("startedAt") or 0) < int(plan.get("startedAt") or 0):
                return plan
            try:
                previous = verify_google_play_purchase(GREENHOUSE_FAMILY, previous_token)
            except GooglePlayVerificationError as error:
                if error.safe_code != "google_play_purchase_invalid":
                    raise
            else:
                if previous.get("active"):
                    raise PlanError(409, "greenhouse_plan_purchase_conflict")
        if reserved != token:
            plan = save(table, {**plan, "confirmingToken": token})
    try:
        purchase = verify_google_play_purchase(GREENHOUSE_FAMILY, token, account_binding=binding, require_test_purchase=testing)
    except GooglePlayVerificationError as error:
        if error.safe_code == "google_play_purchase_invalid" and token == current:
            purchase = {"active": False, "testPurchase": testing, "accountBinding": binding}
        elif error.safe_code in {"purchase_not_eligible", "google_play_product_mismatch", "google_play_purchase_invalid"}:
            raise PlanError(403, "purchase_not_eligible") from error
        else:
            raise
    active = purchase.get("active") is True
    if active or token != current:
        if purchase.get("accountBinding") != binding or purchase.get("testPurchase") is not testing:
            raise PlanError(403, "purchase_not_eligible")
    if current != token and not active:
        return plan
    start = int(purchase.get("startedAt") or 0)
    product = purchase.get("productId") if active else None
    if active and (not sku_slots(product) or (current != token and sku_slots(product) > max_extras())):
        raise PlanError(403, "purchase_not_eligible")
    through = int(purchase.get("expiresAtMillis") or (purchase.get("expiresAt") or 0) * 1000) if active else 0
    next_slots = sku_slots(purchase.get("nextProductId")) or (0 if purchase.get("renews") is False else None)
    updated = {**plan, "purchaseToken": token, "productId": product, "testPurchase": testing,
               "startedAt": start or int(plan.get("startedAt") or 0), "checkedAt": int(time.time()),
               "paidThroughEpochMs": through, "renews": purchase.get("renews"), "nextExtraSlots": next_slots}
    if updated.get("confirmingToken") == token:
        updated.pop("confirmingToken")
    # A verified contraction applies to the selected houses only when the current
    # item changes (or expires). An abandoned Play checkout cannot displace a house.
    old_slots = sku_slots(plan.get("productId"))
    new_slots = sku_slots(product)
    if new_slots < old_slots and (active or purchase.get("subscriptionState") == "SUBSCRIPTION_STATE_EXPIRED"):
        candidates = [row for row in updated["houses"] if not any(row.get(k) for k in
                      ("removed", "free", "grandfathered", "suspended"))][:old_slots]
        for row in sorted(candidates, key=lambda row: bool(row.get("pauseRequested")))[new_slots:]:
            row["suspended"] = True
    return save(table, updated)


def refresh(table, principal, plan, catalog_for, *, allocating=False):
    now = time.time()
    # Recover a crash between reserving a receipt and its Play acknowledgement,
    # even when the purchaser closed their app before receiving the response.
    confirming = plan.get("confirmingToken")
    if confirming:
        mode = checkout_mode(principal.account_id)
        if mode:
            try:
                plan = apply_purchase(table, principal, plan, catalog_for, confirming, mode == "LicenseTest")
                if plan.get("confirmingToken") == confirming:
                    plan = save(table, {key: value for key, value in plan.items() if key != "confirmingToken"})
            except PlanError as error:
                if error.status != 403:
                    raise
                plan = save(table, {key: value for key, value in plan.items() if key != "confirmingToken"})
            except GooglePlayVerificationError:
                if allocating:
                    raise
    if not plan.get("purchaseToken") or (0 <= now - int(plan.get("checkedAt", 0)) < 300 and
            not (plan.get("paidThroughEpochMs", 0) and plan["paidThroughEpochMs"] <= now * 1000)):
        return plan
    try:
        return apply_purchase(table, principal, plan, catalog_for, plan["purchaseToken"], plan["testPurchase"])
    except GooglePlayVerificationError:
        # Existing monitoring may continue through the verified deadline. A stale
        # entitlement cannot authorize new paid activations while Play is unavailable.
        if allocating:
            raise
        return plan


def handle(table, principal, requester, method, operation, body, catalog_for, response, device_authenticated):
    is_server = requester.get("role") == "GREENHOUSE_SERVER"
    viewer = requester.get("role") == "VIEWER_CLIENT" and not device_authenticated
    if method == "GET" and not operation:
        plan = read(table, principal)
        if plan["revision"] == 0:
            plan = save(table, plan)
        if not is_server and checkout_mode(principal.account_id):
            register_checkout_binding(table, catalog_for(principal.account_id, GREENHOUSE_FAMILY), principal)
        return response(200, state(refresh(table, principal, plan, catalog_for), principal, catalog_for, viewer=viewer))
    if method != "POST" or operation not in {"activate", "resume", "pause", "remove", "verify", "rename"}:
        return response(404, {"error": "not_found"})
    try:
        args = json.loads(body or "{}")
        if not isinstance(args, dict):
            raise ValueError()
    except (ValueError, TypeError):
        return response(400, {"error": "invalid_body"})
    plan = read(table, principal)
    host = None
    if is_server or device_authenticated:
        host = requester.get("greenhouseId")
        house_id = house_id_of(args.get("houseId")) if operation != "verify" else None
        existing = next((r for r in plan["houses"] if r["id"] == house_id), None)
        own_root_rename = operation == "rename" and existing and existing.get("serverGreenhouseId") == host
        if not host or operation == "verify" or (existing and existing.get("hostingGreenhouseId") != host and not own_root_rename):
            raise AccessDenied("client_device_required")
        if operation == "activate" and not monitoring_enabled(table, principal, host):
            raise PlanError(409, "greenhouse_paused")
    elif requester.get("role") != "VIEWER_CLIENT":
        raise AccessDenied("client_device_required")
    if operation == "verify":
        mode = checkout_mode(principal.account_id)
        if not mode:
            raise PlanError(403, "greenhouse_checkout_unavailable")
        sku = args.get("productId")
        token = args.get("purchaseToken")
        if not isinstance(sku, str) or not 1 <= sku_slots(sku) <= max_extras() or not isinstance(token, str) or not 16 <= len(token) <= 4096:
            raise PlanError(400, "invalid_greenhouse_purchase")
        plan = apply_purchase(table, principal, plan, catalog_for, token, mode == "LicenseTest")
    else:
        plan = refresh(table, principal, plan, catalog_for, allocating=operation in {"activate", "resume"})
        old = copy.deepcopy(plan)
        mutate(plan, operation, args.get("houseId"), args.get("name") if operation in {"activate", "rename"} else None, int(time.time() * 1000))
        if host and operation == "activate":
            next(r for r in plan["houses"] if r["id"] == args["houseId"])["hostingGreenhouseId"] = host
        if plan != old or not plan["revision"]:
            plan = save(table, plan)
    return response(200, state(plan, principal, catalog_for, viewer=viewer))


def server_allocation(table, principal, server_id, alias, name, catalog_for):
    """Return a CAS put for the SAME transaction as enrollment, or raise before pairing."""
    plan = refresh(table, principal, read(table, principal), catalog_for, allocating=True)
    mapped = next((row for row in plan["houses"] if row.get("serverGreenhouseId") == server_id), None)
    if mapped and (mapped.get("removed") or (alias and alias != mapped["id"])):
        raise PlanError(409, "greenhouse_plan_conflict")
    canonical = mapped["id"] if mapped else house_id_of(alias or server_id)
    row = next((item for item in plan["houses"] if item["id"] == canonical), None)
    if row and (row.get("hostingGreenhouseId") is not None or row.get("serverGreenhouseId") not in (None, server_id)):
        raise PlanError(409, "greenhouse_already_has_server")
    if alias and not row:
        raise PlanError(404, "greenhouse_not_found")
    mutate(plan, "activate", canonical, row["name"] if row else name, int(time.time() * 1000))
    next(item for item in plan["houses"] if item["id"] == canonical)["serverGreenhouseId"] = server_id
    return {"Put": {"TableName": table.name, **write_params(plan)}}


def monitoring_enabled(table, principal, server_id):
    # Older installations without a plan record retain their existing greenhouse.
    plan = table.get_item(Key=plan_key(principal), ConsistentRead=True).get("Item")
    if not plan:
        return True
    return plan.get("tenantId") == principal.tenant_id and any(
        row["active"] and row.get("serverGreenhouseId") == server_id for row in rows_at(plan, int(time.time() * 1000)))


def reconcile_notice(table, token, catalog_for, recently_purchased):
    try:
        purchase = verify_google_play_purchase(GREENHOUSE_FAMILY, token)
    except GooglePlayVerificationError as error:
        if recently_purchased and error.safe_code == "google_play_purchase_invalid":
            raise GooglePlayVerificationError("google_play_purchase_not_visible") from error
        if error.safe_code in {"google_play_product_mismatch", "google_play_purchase_invalid", "purchase_not_eligible"}:
            return
        raise
    binding = purchase.get("accountBinding")
    if not isinstance(binding, str) or not re.fullmatch("[a-f0-9]{64}", binding):
        return
    owner = table.get_item(Key={"pk": "CAMERA_BINDING#" + binding, "sk": "OWNER"}, ConsistentRead=True).get("Item", {})
    account = owner.get("accountId")
    tenant = owner.get("tenantId")
    if not account or not tenant or catalog_for(account, GREENHOUSE_FAMILY)["accountBinding"] != binding:
        return
    meta = table.get_item(Key={"pk": "ACCOUNT#" + account, "sk": "METADATA"}, ConsistentRead=True).get("Item", {})
    if (meta.get("accountId"), meta.get("tenantId")) != (account, tenant):
        return
    owner = Principal(account, tenant, "")
    plan = read(table, owner)
    mode = checkout_mode(account)
    if plan.get("purchaseToken") == token:
        testing = plan["testPurchase"]
    elif mode:
        testing = mode == "LicenseTest"
    else:
        return
    if purchase.get("testPurchase") is testing:
        apply_purchase(table, owner, plan, catalog_for, token, testing)
