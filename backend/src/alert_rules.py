"""Versioned greenhouse limits. Installation-specific sound and delivery stay on each phone."""
import json

from botocore.exceptions import ClientError

from domain import AccessDenied


class RuleConflict(Exception):
    pass


LIMITS = {"lowTemperatureF": (-150, 250), "highTemperatureF": (-150, 250),
          "lowHumidity": (0, 100), "highHumidity": (0, 100)}
SWITCHES = {"temperatureAlerts", "humidityAlerts", "offlineAlerts"}


def valid_rules(rules):
    return (
        isinstance(rules, dict) and set(rules) == LIMITS.keys() | SWITCHES
        and all(type(rules[k]) is bool for k in SWITCHES)
        and all(type(rules[k]) in (float, int) and lo <= rules[k] <= hi for k, (lo, hi) in LIMITS.items())
        and rules["lowTemperatureF"] < rules["highTemperatureF"] and rules["lowHumidity"] < rules["highHumidity"]
    )


def load_rules(table, principal, greenhouse_id):
    item = table.get_item(Key={"pk": f"GREENHOUSE#{greenhouse_id}", "sk": "ALERT_RULES"}, ConsistentRead=True).get("Item")
    if not item:
        return 0, None
    if (item["tenantId"], item["accountId"]) != (principal.tenant_id, principal.account_id):
        raise AccessDenied("not_found")
    return int(item["revision"]), json.loads(item["rulesJson"])


def current_house(table, principal, greenhouse_id, device, device_authenticated):
    house = table.get_item(Key={"pk": f"GREENHOUSE#{greenhouse_id}", "sk": "METADATA"}, ConsistentRead=True).get("Item")
    if (not house or house.get("tenantId") != principal.tenant_id or house.get("accountId") != principal.account_id
            or not house.get("enabled")):
        raise AccessDenied("not_found")
    if device.get("role") == "GREENHOUSE_SERVER":
        if device.get("greenhouseId") != greenhouse_id or house.get("serverDeviceId") != principal.device_id:
            raise AccessDenied("server_device")
        if device.get("deviceSecretHash") and not device_authenticated:
            raise AccessDenied("device_credential_required")
    elif device.get("role") != "VIEWER_CLIENT" or device_authenticated:
        raise AccessDenied("client_device_required")
    return house


def save_rules(table, principal, greenhouse_id, device, expected, rules):
    # The policy has its own sort key so pairing/replacing a phone never resets it.
    # Recheck the actor and binding along with the revision in the same transaction.
    owner = {":account": principal.account_id, ":tenant": principal.tenant_id}
    enabled_owner = "accountId=:account AND tenantId=:tenant AND enabled=:on"
    house_check = enabled_owner
    house_values = {**owner, ":on": True}
    device_check = enabled_owner + " AND #r=:role AND (attribute_not_exists(communicationSuspended) OR communicationSuspended=:off)"
    device_values = {**owner, ":on": True, ":off": False, ":role": device["role"]}
    if device["role"] == "GREENHOUSE_SERVER":
        house_check += " AND serverDeviceId=:device"
        house_values[":device"] = principal.device_id
        device_check += " AND greenhouseId=:house"
        device_values[":house"] = greenhouse_id
        if device.get("deviceSecretHash"):
            device_check += " AND deviceSecretHash=:hash"
            device_values[":hash"] = device["deviceSecretHash"]
    put = {"TableName": table.name,
           "Item": {"pk": f"GREENHOUSE#{greenhouse_id}", "sk": "ALERT_RULES", "tenantId": principal.tenant_id,
                    "accountId": principal.account_id, "revision": expected + 1, "rulesJson": json.dumps(rules, separators=(",", ":"))},
           "ConditionExpression": "attribute_not_exists(pk)"}
    if expected:
        put.update(ConditionExpression="revision=:expected AND tenantId=:tenant AND accountId=:account",
                   ExpressionAttributeValues={":expected": expected, **owner})
    try:
        table.meta.client.transact_write_items(TransactItems=[
            {"ConditionCheck": {"TableName": table.name, "Key": {"pk": f"DEVICE#{principal.device_id}", "sk": "METADATA"},
                                "ConditionExpression": device_check, "ExpressionAttributeNames": {"#r": "role"},
                                "ExpressionAttributeValues": device_values}},
            {"ConditionCheck": {"TableName": table.name, "Key": {"pk": f"GREENHOUSE#{greenhouse_id}", "sk": "METADATA"},
                                "ConditionExpression": house_check, "ExpressionAttributeValues": house_values}},
            {"Put": put}])
    except ClientError as error:
        if error.response.get("Error", {}).get("Code") == "TransactionCanceledException":
            reasons = [r.get("Code") for r in error.response.get("CancellationReasons", [])]
            if "ConditionalCheckFailed" in reasons[:2]:
                raise AccessDenied("device_or_greenhouse_changed") from error
            if reasons[2:3] == ["ConditionalCheckFailed"]:
                raise RuleConflict from error
        raise


def device_applied(device, greenhouse_id):
    if device.get("appliedAlertRulesHouse") == greenhouse_id:
        return int(device.get("appliedAlertRulesRevision", 0))
    return 0


def rules_reply(revision, rules, applied):
    return {"revision": revision, "rules": rules, "appliedRevision": min(revision, applied)}


def greenhouse_rules(table, principal, greenhouse_id, device, device_authenticated, write, body, response):
    house = current_house(table, principal, greenhouse_id, device, device_authenticated)
    revision, rules = load_rules(table, principal, greenhouse_id)
    if write:
        try:
            payload = json.loads(body or "{}")
        except (TypeError, ValueError):
            payload = None
        if (not isinstance(payload, dict) or type(payload.get("expectedRevision")) is not int
                or payload["expectedRevision"] < 0 or not valid_rules(payload.get("rules"))):
            return response(400, {"error": "invalid_alert_rules"})
        if payload["expectedRevision"] != revision:
            return response(409, {"error": "alert_rules_changed"})
        try:
            save_rules(table, principal, greenhouse_id, device, revision, payload["rules"])
        except RuleConflict:
            return response(409, {"error": "alert_rules_changed"})
        revision, rules = revision + 1, payload["rules"]
    server = device if device["role"] == "GREENHOUSE_SERVER" else {}
    if house.get("serverDeviceId") and not server:
        candidate = table.get_item(Key={"pk": f"DEVICE#{house['serverDeviceId']}", "sk": "METADATA"}, ConsistentRead=True).get("Item") or {}
        if (candidate.get("accountId") == principal.account_id and candidate.get("tenantId") == principal.tenant_id
                and candidate.get("greenhouseId") == greenhouse_id and candidate.get("enabled")
                and not candidate.get("communicationSuspended") and candidate.get("role") == "GREENHOUSE_SERVER"):
            server = candidate
    return response(200, rules_reply(revision, rules, device_applied(server, greenhouse_id)))


def heartbeat_rules(table, principal, device, device_authenticated, body):
    house_id = device["greenhouseId"]
    current_house(table, principal, house_id, device, device_authenticated)
    revision, rules = load_rules(table, principal, house_id)
    local = body["alertRules"]
    seeded = False
    if revision == 0 and valid_rules(local):
        try:
            save_rules(table, principal, house_id, device, 0, local)
            revision, rules, seeded = 1, local, True
        except RuleConflict:
            revision, rules = load_rules(table, principal, house_id)
    applied = body.get("appliedAlertRulesRevision")
    if seeded or (type(applied) is int and applied == revision and valid_rules(local) and local == rules):
        acknowledged = revision
    else:
        acknowledged = 0
    return rules_reply(revision, rules, acknowledged)
