"""Track each thermometer's crossings of the greenhouse's common temperature limits."""
import hashlib
import json

from boto3.dynamodb.conditions import Key
from botocore.exceptions import ClientError

from alerts import ALERT_RETENTION_SECONDS, new_alert, temperature_breach_message

RECOVERY_WINDOW_SECONDS = 24 * 60 * 60


def recovery_inbox_key(account_id, incident_id):
    return {"pk": f"ACCOUNT#{account_id}", "sk": f"TEMPERATURE_DELIVERY#{incident_id}"}


def track_for_recovery(table, alert):
    # Keep this projection independent of the original active-only inbox for older clients.
    item = recovery_inbox_key(alert["accountId"], alert["id"])
    item.update(id=alert["id"], sourceGeneration=alert["sourceGeneration"])
    if "expiresAt" in alert:
        item["expiresAt"] = alert["expiresAt"]
    return {"Put": {"TableName": table.name, "Item": item}}


def record_recovery(table, account_id, incident_id, now, resolution):
    # If already acknowledged (or created before catch-up support), this can only
    # write an expiring tombstone: it cannot restore the incident identity or payload.
    return {"Update": {
        "TableName": table.name, "Key": recovery_inbox_key(account_id, incident_id),
        "UpdateExpression": "SET resolvedAt=:now, expiresAt=:expires, resolutionKind=:kind",
        "ExpressionAttributeValues": {":now": now, ":expires": now + RECOVERY_WINDOW_SECONDS, ":kind": resolution},
    }}


def recent_recoveries(table, principal, viewer, now, incident_server):
    account_key = f"ACCOUNT#{principal.account_id}"
    cutoff = max(now - RECOVERY_WINDOW_SECONDS, int(viewer.get("createdAt", 0)),
                 int(viewer.get("reauthorizedAt", 0)))
    query = {
        "KeyConditionExpression": Key("pk").eq(account_key) & Key("sk").begins_with("TEMPERATURE_DELIVERY#"),
        "FilterExpression": "attribute_exists(id) AND resolvedAt BETWEEN :cutoff AND :now AND expiresAt > :now",
        "ExpressionAttributeValues": {":cutoff": cutoff, ":now": now},
        "ConsistentRead": True, "ScanIndexForward": False, "Limit": 100,
    }
    houses = {}
    endings = {"NORMAL": "A later check was within the configured limits.",
               "HIGH": "A later check crossed the high limit.", "LOW": "A later check crossed the low limit."}
    while True:
        page = table.query(**query)
        markers = {item["id"]: item for item in page["Items"]}
        if markers:
            # The canonical acknowledgement also covers older Lambda versions
            # that deleted only the original active projection during a rollback.
            keys = [{"pk": account_key, "sk": f"ALERT#{incident_id}"} for incident_id in markers]
            result = table.meta.client.batch_get_item(RequestItems={table.name: {"Keys": keys, "ConsistentRead": True}})
            alerts = result.get("Responses", {}).get(table.name, [])
            for key in result.get("UnprocessedKeys", {}).get(table.name, {}).get("Keys", []):
                record = table.get_item(Key=key, ConsistentRead=True).get("Item")
                if record:
                    alerts.append(record)
            for alert in alerts:
                marker = markers[alert["id"]]
                if ("acknowledgedAt" in alert or alert.get("type") != "CRITICAL_TEMPERATURE"
                        or alert.get("accountId") != principal.account_id or alert.get("tenantId") != principal.tenant_id
                        or not alert.get("sourceGeneration") or alert["sourceGeneration"] != marker.get("sourceGeneration")):
                    continue
                server = incident_server(alert)
                if not server or enrollment_key(server, alert["deviceId"]) != alert["sourceGeneration"]:
                    continue
                house_id = alert["greenhouseId"]
                if house_id not in houses:
                    houses[house_id] = table.get_item(Key={"pk": f"GREENHOUSE#{house_id}", "sk": "METADATA"},
                                                      ConsistentRead=True).get("Item", {})
                house = houses[house_id]
                if (not house.get("enabled") or house.get("serverDeviceId") != alert["deviceId"]
                        or house.get("accountId") != principal.account_id or house.get("tenantId") != principal.tenant_id):
                    continue
                ending = endings.get(marker.get("resolutionKind"))
                if ending:
                    yield dict(alert, resolvedAt=marker["resolvedAt"], message=f'{alert["message"]} {ending}')
        if not page.get("LastEvaluatedKey"):
            break
        query["ExclusiveStartKey"] = page["LastEvaluatedKey"]


def enrollment_key(device, device_id):
    # Pairing rotates the credential. A re-paired phone must never inherit an old episode.
    identity = [device["tenantId"], device["accountId"], device["greenhouseId"],
                device_id, device.get("deviceSecretHash", ""), str(device.get("reauthorizedAt", ""))]
    return hashlib.sha256(json.dumps(identity).encode()).hexdigest()


def parse_observations(body, now):
    entries = body["temperatureObservations"]
    reported = body.get("recordedAtEpochMs")
    if (not isinstance(entries, list) or type(reported) is not int
            or not (now - 900) * 1000 <= reported <= (now + 300) * 1000):
        raise ValueError("invalid_temperature_observations")
    observations = {}
    seen = set()
    limits = None
    for entry in entries:
        if not isinstance(entry, dict):
            raise ValueError("invalid_temperature_observations")
        sensor_id, name = entry.get("sensorId"), entry.get("sensorName")
        measured = entry.get("measuredAtEpochMs")
        value, low, high = (entry.get(k) for k in ("temperatureF", "lowF", "highF"))
        if (not isinstance(sensor_id, str) or not sensor_id.strip() or len(sensor_id.encode()) > 512
                or sensor_id in seen or not isinstance(name, str) or not name.strip()
                or type(measured) is not int or not 0 < measured <= reported
                or not all(type(n) in (int, float) and -460 <= n <= 1000 for n in (low, high, value))
                or low >= high or (limits is not None and limits != (low, high))):
            raise ValueError("invalid_temperature_observations")
        seen.add(sensor_id)
        limits = low, high
        if measured < (now - 900) * 1000:
            continue
        direction = "LOW" if value <= low else "HIGH" if value >= high else "NORMAL"
        message = temperature_breach_message(
            {"sensorName": name, "measuredAt": measured // 1000, "temperatureF": value,
             "direction": direction, "limitF": low if direction == "LOW" else high}, now)
        observations[sensor_id] = {"direction": direction, "measuredAt": measured,
                                   "evaluatedAt": reported, "message": message}
    return observations


def _sensor_changes(table, prior, sensor_id, obs, principal, device, generation, now):
    measured, evaluated = obs["measuredAt"], obs["evaluatedAt"]
    direction = obs["direction"]
    previous_direction = prior.get("direction")
    previous_time = (prior.get("measuredAt", 0), prior.get("evaluatedAt", 0))
    if (measured, evaluated) <= previous_time:
        return []
    marker = {"pk": f"TEMPERATURE#{generation}", "sk": sensor_id,
              "measuredAt": measured, "evaluatedAt": evaluated, "direction": direction}
    work = []
    if direction != previous_direction and prior.get("incidentId"):
        work.append(record_recovery(table, principal.account_id, prior["incidentId"], now, direction))
        work.append({"Delete": {"TableName": table.name,
                    "Key": {"pk": f"ACCOUNT#{principal.account_id}", "sk": f'OPEN_TEMPERATURE#{prior["incidentId"]}'}}})
        work.append({"Update": {"TableName": table.name,
                    "Key": {"pk": f"ACCOUNT#{principal.account_id}", "sk": f'ALERT#{prior["incidentId"]}'},
                    "UpdateExpression": "SET resolvedAt=:now, expiresAt=:expires",
                    "ExpressionAttributeValues": {":now": now, ":expires": now + ALERT_RETENTION_SECONDS}}})
    if direction == "NORMAL":
        # Once older readings are outside the freshness window a normal marker is unnecessary.
        marker["expiresAt"] = now + 86400
    elif direction == previous_direction:
        marker["incidentId"] = prior["incidentId"]
    else:
        alert = new_alert(account_id=principal.account_id, tenant_id=principal.tenant_id,
                          greenhouse_id=device["greenhouseId"], device_id=principal.device_id,
                          alert_type="CRITICAL_TEMPERATURE", created_at=now)
        alert.update(sensorId=sensor_id, sourceGeneration=generation, message=obs["message"])
        del alert["expiresAt"]  # Remain deliverable until recovered, even after a long excursion.
        marker["incidentId"] = alert["id"]
        work.append({"Put": {"TableName": table.name, "Item": alert, "ConditionExpression": "attribute_not_exists(pk)"}})
        # The compact inbox has just the current, unacknowledged episodes. Keeping the
        # projection in the same transaction avoids one extra network read per sensor on every poll.
        pending = dict(alert, sk=f'OPEN_TEMPERATURE#{alert["id"]}')
        work.append({"Put": {"TableName": table.name, "Item": pending}})
        work.append(track_for_recovery(table, alert))
    put = {"TableName": table.name, "Item": marker, "ConditionExpression": "attribute_not_exists(pk)"}
    if prior:
        put.update(ConditionExpression="measuredAt=:oldMeasurement AND evaluatedAt=:oldEvaluation",
                   ExpressionAttributeValues={":oldMeasurement": previous_time[0], ":oldEvaluation": previous_time[1]})
    return [{"Put": put}, *work]


def apply_observations(table, principal, device, observations, now):
    if not observations:
        return
    generation = enrollment_key(device, principal.device_id)
    values = {":tenant": principal.tenant_id, ":account": principal.account_id, ":house": device["greenhouseId"],
              ":role": "GREENHOUSE_SERVER", ":on": True, ":off": False}
    condition = ("tenantId=:tenant AND accountId=:account AND greenhouseId=:house AND #role=:role AND enabled=:on "
                 "AND (attribute_not_exists(communicationSuspended) OR communicationSuspended=:off)")
    if device.get("deviceSecretHash"):
        condition += " AND deviceSecretHash=:secret"
        values[":secret"] = device["deviceSecretHash"]
    server_check = {"ConditionCheck": {"TableName": table.name, "Key": {"pk": f"DEVICE#{principal.device_id}", "sk": "METADATA"},
                    "ConditionExpression": condition, "ExpressionAttributeNames": {"#role": "role"}, "ExpressionAttributeValues": values}}
    for attempt in range(3):
        states = {}
        query = {"KeyConditionExpression": Key("pk").eq(f"TEMPERATURE#{generation}"), "ConsistentRead": True}
        while True:
            page = table.query(**query)
            states.update((row["sk"], row) for row in page["Items"])
            if not page.get("LastEvaluatedKey"):
                break
            query["ExclusiveStartKey"] = page["LastEvaluatedKey"]
        transaction = [server_check]
        try:
            for sensor_id, observation in observations.items():
                changes = _sensor_changes(table, states.get(sensor_id, {}), sensor_id, observation, principal, device,
                                          generation, now)
                # An individual recovery/creation and its watermark always commit together.
                if len(transaction) + len(changes) > 100:
                    table.meta.client.transact_write_items(TransactItems=transaction)
                    transaction = [server_check]
                transaction.extend(changes)
            if len(transaction) > 1:
                table.meta.client.transact_write_items(TransactItems=transaction)
            return
        except ClientError as error:
            reasons = error.response.get("CancellationReasons") or []
            if (attempt == 2 or error.response.get("Error", {}).get("Code") != "TransactionCanceledException"
                    or (reasons and reasons[0].get("Code") == "ConditionalCheckFailed")
                    or not any(r.get("Code") in {"ConditionalCheckFailed", "TransactionConflict"} for r in reasons)):
                raise
            # A concurrent report advanced a watermark. Re-read before deciding which episodes are new.
