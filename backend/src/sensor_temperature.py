"""Track independent sensor crossings of the greenhouse temperature and humidity limits."""
import hashlib
import json
from typing import NamedTuple

from boto3.dynamodb.conditions import Key
from botocore.exceptions import ClientError

from alerts import ALERT_RETENTION_SECONDS, alert_record_key, new_alert, sensor_breach_message

RECOVERY_WINDOW_SECONDS = 24 * 60 * 60


class Metric(NamedTuple):
    key: str
    readings: str
    value: str
    low: str
    high: str
    minimum: int
    maximum: int
    unit: str

    @property
    def alert_type(self):
        return f"CRITICAL_{self.key}"


TEMPERATURE = Metric("TEMPERATURE", "temperatureObservations", "temperatureF", "lowF", "highF", -460, 1000, "°F")
HUMIDITY = Metric("HUMIDITY", "humidityObservations", "humidityPercent", "lowHumidity", "highHumidity", 0, 100, "% RH")


def recovery_inbox_key(account_id, incident_id, metric=TEMPERATURE):
    return {"pk": f"ACCOUNT#{account_id}", "sk": f"{metric.key}_DELIVERY#{incident_id}"}


def track_for_recovery(table, alert, metric=TEMPERATURE):
    # Keep this projection independent of the original active-only inbox for older clients.
    item = recovery_inbox_key(alert["accountId"], alert["id"], metric)
    item.update(id=alert["id"], sourceGeneration=alert["sourceGeneration"])
    if "expiresAt" in alert:
        item["expiresAt"] = alert["expiresAt"]
    return {"Put": {"TableName": table.name, "Item": item}}


def record_recovery(table, account_id, incident_id, now, resolution, metric=TEMPERATURE):
    # If already acknowledged (or created before catch-up support), this can only
    # write an expiring tombstone: it cannot restore the incident identity or payload.
    return {"Update": {
        "TableName": table.name, "Key": recovery_inbox_key(account_id, incident_id, metric),
        "UpdateExpression": "SET resolvedAt=:now, expiresAt=:expires, resolutionKind=:kind",
        "ExpressionAttributeValues": {":now": now, ":expires": now + RECOVERY_WINDOW_SECONDS, ":kind": resolution},
    }}


def recent_recoveries(table, principal, viewer, now, incident_server, metric=TEMPERATURE):
    account_key = f"ACCOUNT#{principal.account_id}"
    cutoff = max(now - RECOVERY_WINDOW_SECONDS, int(viewer.get("createdAt", 0)),
                 int(viewer.get("reauthorizedAt", 0)))
    query = {
        "KeyConditionExpression": Key("pk").eq(account_key) & Key("sk").begins_with(f"{metric.key}_DELIVERY#"),
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
            keys = [{"pk": account_key, "sk": alert_record_key(incident_id)} for incident_id in markers]
            result = table.meta.client.batch_get_item(RequestItems={table.name: {"Keys": keys, "ConsistentRead": True}})
            alerts = result.get("Responses", {}).get(table.name, [])
            for key in result.get("UnprocessedKeys", {}).get(table.name, {}).get("Keys", []):
                record = table.get_item(Key=key, ConsistentRead=True).get("Item")
                if record:
                    alerts.append(record)
            for alert in alerts:
                marker = markers[alert["id"]]
                if ("acknowledgedAt" in alert or alert.get("type") != metric.alert_type
                        or alert.get("accountId") != principal.account_id or alert.get("tenantId") != principal.tenant_id
                        or not alert.get("sourceGeneration") or alert["sourceGeneration"] != marker.get("sourceGeneration")):
                    continue
                server = incident_server(alert)
                if not server or enrollment_key(server, alert["deviceId"]) != alert["sourceGeneration"]:
                    continue
                if not current_sensor_house(table, principal, alert, houses):
                    continue
                ending = endings.get(marker.get("resolutionKind"))
                if ending:
                    yield dict(alert, resolvedAt=marker["resolvedAt"], message=f'{alert["message"]} {ending}')
        if not page.get("LastEvaluatedKey"):
            break
        query["ExclusiveStartKey"] = page["LastEvaluatedKey"]


def humidity_policy_key(generation):
    return {"pk": f"HUMIDITY_POLICY#{generation}", "sk": "STATE"}


def current_sensor_house(table, principal, alert, houses):
    house_id = alert["greenhouseId"]
    if house_id not in houses:
        houses[house_id] = table.get_item(Key={"pk": f"GREENHOUSE#{house_id}", "sk": "METADATA"},
                                          ConsistentRead=True).get("Item", {})
    house = houses[house_id]
    return (house.get("enabled") and house.get("serverDeviceId") == alert["deviceId"]
            and house.get("accountId") == principal.account_id and house.get("tenantId") == principal.tenant_id)


def enrollment_key(device, device_id):
    # Pairing rotates the credential. A re-paired phone must never inherit an old episode.
    identity = [device["tenantId"], device["accountId"], device["greenhouseId"],
                device_id, device.get("deviceSecretHash", ""), str(device.get("reauthorizedAt", ""))]
    return hashlib.sha256(json.dumps(identity).encode()).hexdigest()


def parse_observations(body, now, metric=TEMPERATURE):
    entries = body[metric.readings]
    reported = body.get("recordedAtEpochMs")
    if (not isinstance(entries, list) or type(reported) is not int
            or not (now - 900) * 1000 <= reported <= (now + 300) * 1000):
        raise ValueError(f"invalid_{metric.key.lower()}_observations")
    observations = {}
    seen = set()
    limits = None
    for entry in entries:
        if not isinstance(entry, dict):
            raise ValueError(f"invalid_{metric.key.lower()}_observations")
        sensor_id, name = entry.get("sensorId"), entry.get("sensorName")
        measured = entry.get("measuredAtEpochMs")
        value, low, high = (entry.get(k) for k in (metric.value, metric.low, metric.high))
        if (not isinstance(sensor_id, str) or not sensor_id.strip() or len(sensor_id.encode()) > 512
                or sensor_id in seen or not isinstance(name, str) or not name.strip()
                or type(measured) is not int or not 0 < measured <= reported
                or not all(type(n) in (int, float) and metric.minimum <= n <= metric.maximum for n in (low, high, value))
                or low >= high or (limits is not None and limits != (low, high))):
            raise ValueError(f"invalid_{metric.key.lower()}_observations")
        seen.add(sensor_id)
        limits = low, high
        if measured < (now - 900) * 1000:
            continue
        direction = "LOW" if value <= low else "HIGH" if value >= high else "NORMAL"
        message = sensor_breach_message(name, value, low if direction == "LOW" else high, direction, metric.unit)
        observations[sensor_id] = {"direction": direction, "measuredAt": measured,
                                   "evaluatedAt": reported, "message": message}
    return observations


def _sensor_changes(table, prior, sensor_id, obs, principal, device, generation, now, metric, disabled_at=0):
    measured, evaluated = obs["measuredAt"], obs["evaluatedAt"]
    direction = obs["direction"]
    previous_direction = prior.get("direction")
    previous_time = (prior.get("measuredAt", 0), prior.get("evaluatedAt", 0))
    reset = bool(disabled_at and prior.get("incidentId") and prior["evaluatedAt"] <= disabled_at)
    if not reset and (measured, evaluated) <= previous_time:
        return []
    marker = {"pk": f"{metric.key}#{generation}", "sk": sensor_id,
              "measuredAt": measured, "evaluatedAt": evaluated, "direction": direction}
    work = []
    if (reset or direction != previous_direction) and prior.get("incidentId"):
        if reset:
            # Disabling a rule retires the episode without fabricating a measured recovery.
            key = recovery_inbox_key(principal.account_id, prior["incidentId"], metric)
            work.append({"Delete": {"TableName": table.name, "Key": key}})
        else:
            work.append(record_recovery(table, principal.account_id, prior["incidentId"], now, direction, metric))
        work.append({"Delete": {"TableName": table.name,
                    "Key": {"pk": f"ACCOUNT#{principal.account_id}", "sk": f'OPEN_{metric.key}#{prior["incidentId"]}'}}})
        work.append({"Update": {"TableName": table.name,
                    "Key": {"pk": f"ACCOUNT#{principal.account_id}", "sk": alert_record_key(prior["incidentId"])},
                    "UpdateExpression": "SET resolvedAt=:now, expiresAt=:expires",
                    "ExpressionAttributeValues": {":now": now, ":expires": now + ALERT_RETENTION_SECONDS}}})
    if direction == "NORMAL":
        # Once older readings are outside the freshness window a normal marker is unnecessary.
        marker["expiresAt"] = now + 86400
    elif not reset and direction == previous_direction:
        marker["incidentId"] = prior["incidentId"]
    else:
        alert = new_alert(account_id=principal.account_id, tenant_id=principal.tenant_id,
                          greenhouse_id=device["greenhouseId"], device_id=principal.device_id,
                          alert_type=metric.alert_type, created_at=now)
        alert.update(sensorId=sensor_id, sourceGeneration=generation, message=obs["message"])
        if metric == HUMIDITY:
            alert["evaluatedAt"] = evaluated
        del alert["expiresAt"]  # Remain deliverable until recovered, even after a long excursion.
        marker["incidentId"] = alert["id"]
        work.append({"Put": {"TableName": table.name, "Item": alert, "ConditionExpression": "attribute_not_exists(pk)"}})
        # The compact inbox has just the current, unacknowledged episodes. Keeping the
        # projection in the same transaction avoids one extra network read per sensor on every poll.
        pending = dict(alert, sk=f'OPEN_{metric.key}#{alert["id"]}')
        work.append({"Put": {"TableName": table.name, "Item": pending}})
        work.append(track_for_recovery(table, alert, metric))
    put = {"TableName": table.name, "Item": marker, "ConditionExpression": "attribute_not_exists(pk)"}
    if prior:
        put.update(ConditionExpression="measuredAt=:oldMeasurement AND evaluatedAt=:oldEvaluation",
                   ExpressionAttributeValues={":oldMeasurement": previous_time[0], ":oldEvaluation": previous_time[1]})
    return [{"Put": put}, *work]


def apply_observations(table, principal, device, observations, now, metric=TEMPERATURE, humidity_report=None):
    if not observations and humidity_report is None:
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
        # Serialize humidity rule changes alongside the sensor watermarks. Retrying an
        # interrupted batch is safe even when another report intervened or the rule was toggled.
        guards = [server_check]
        policy_update = None
        disabled_at = 0
        if humidity_report is not None:
            enabled, reported = humidity_report
            key = humidity_policy_key(generation)
            prior = table.get_item(Key=key, ConsistentRead=True).get("Item", {})
            if prior and (reported < prior["reported"] or (reported == prior["reported"] and enabled != prior["enabled"])):
                return
            disabled_at = prior.get("disabledAt", 0) if enabled else reported
            policy = dict(key, reported=reported, enabled=enabled, disabledAt=disabled_at)
            condition = {"ConditionExpression": "attribute_not_exists(pk)"}
            if prior:
                condition = {"ConditionExpression": "reported=:previous",
                             "ExpressionAttributeValues": {":previous": prior["reported"]}}
            policy_update = {"Put": {"TableName": table.name, "Item": policy, **condition}}
            guards.append({"ConditionCheck": {"TableName": table.name, "Key": key,
                           "ConditionExpression": "reported=:reported AND enabled=:enabled",
                           "ExpressionAttributeValues": {":reported": reported, ":enabled": enabled}}})
        states = {}
        query = {"KeyConditionExpression": Key("pk").eq(f"{metric.key}#{generation}"), "ConsistentRead": True}
        while True:
            page = table.query(**query)
            states.update((row["sk"], row) for row in page["Items"])
            if not page.get("LastEvaluatedKey"):
                break
            query["ExclusiveStartKey"] = page["LastEvaluatedKey"]
        transaction = [server_check, policy_update] if policy_update else [server_check]
        readings = dict(observations)
        if disabled_at:
            # Never apply a reading observed before the last switch-off. Remove any
            # outstanding episodes, including sensors missing from the current report.
            readings = {sensor: row for sensor, row in readings.items() if row["measuredAt"] > disabled_at}
            for sensor, prior in states.items():
                if sensor not in readings and prior.get("incidentId") and prior["evaluatedAt"] <= disabled_at:
                    readings[sensor] = {"direction": "NORMAL", "measuredAt": disabled_at,
                                        "evaluatedAt": disabled_at}
        try:
            for sensor_id, observation in readings.items():
                changes = _sensor_changes(table, states.get(sensor_id, {}), sensor_id, observation, principal, device,
                                          generation, now, metric, disabled_at)
                # An individual recovery/creation and its watermark always commit together.
                if len(transaction) + len(changes) > 100:
                    table.meta.client.transact_write_items(TransactItems=transaction)
                    transaction = list(guards)
                transaction.extend(changes)
            if policy_update in transaction or len(transaction) > len(guards):
                table.meta.client.transact_write_items(TransactItems=transaction)
            return
        except ClientError as error:
            reasons = error.response.get("CancellationReasons") or []
            if (attempt == 2 or error.response.get("Error", {}).get("Code") != "TransactionCanceledException"
                    or (reasons and reasons[0].get("Code") == "ConditionalCheckFailed")
                    or not any(r.get("Code") in {"ConditionalCheckFailed", "TransactionConflict"} for r in reasons)):
                raise
            # A concurrent report advanced a watermark. Re-read before deciding which episodes are new.
