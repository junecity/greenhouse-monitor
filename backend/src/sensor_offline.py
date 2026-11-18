"""Detect individual sensor outages from a current, authenticated server snapshot."""
from alerts import new_alert
from sensor_temperature import enrollment_key


MAX_EVENT_AGE_SECONDS = 24 * 60 * 60
MAX_SNAPSHOT_AGE_MS = 5 * 60_000


def connection_sample(backup, device_id, now):
    health = backup.get("serverHealth")
    preferences = backup.get("preferences")
    sensors = backup.get("sensors")
    if not isinstance(health, dict) or not isinstance(preferences, dict) or not isinstance(sensors, list):
        return None
    observed = health.get("reportedAt")
    if (health.get("serverDeviceId") != device_id or type(observed) is not int
            or not now * 1000 - MAX_SNAPSHOT_AGE_MS <= observed <= now * 1000 + MAX_SNAPSHOT_AGE_MS):
        return None
    active_house = preferences.get("active")
    assignments = preferences.get("sensorGreenhouses", {})
    if not isinstance(active_house, str) or not active_house or not isinstance(assignments, dict):
        return None

    offline = {}
    for row in sensors:
        if not isinstance(row, dict):
            continue
        sensor_id = row.get("id")
        if not isinstance(sensor_id, str) or not 0 < len(sensor_id) <= 160:
            continue
        if assignments.get(sensor_id, row.get("greenhouseId", "main")) != active_house:
            continue
        updated = row.get("updatedAtEpochMs")
        # A device that has never returned a reading has no established connection to lose.
        if type(updated) is not int or not 0 < updated <= observed + MAX_SNAPSHOT_AGE_MS:
            continue
        timeout_ms = (10 if sensor_id.startswith(("shelly:", "ess:")) else 30) * 60_000
        online = row.get("online")
        if online is False or (online is True and observed - updated > timeout_ms):
            name = row.get("name")
            label = " ".join(name.split())[:96] if isinstance(name, str) else ""
            offline[sensor_id] = label or "Sensor"

    notifications = preferences.get("notifications")
    enabled = not isinstance(notifications, dict) or notifications.get("offline") is not False
    houses = preferences.get("greenhouses")
    if isinstance(houses, list):
        for house in houses:
            if isinstance(house, dict) and house.get("id") == active_house:
                rules = house.get("alertRules")
                if isinstance(rules, dict) and type(rules.get("offlineAlerts")) is bool:
                    enabled = rules["offlineAlerts"]
                break
    return observed, offline, enabled


def writes_for_sample(table, principal, device, backup_item, sample, now):
    """Commit the backup, transition state, and alert together so retries cannot duplicate it."""
    observed, offline, enabled = sample
    house_id = device["greenhouseId"]
    state_key = {"pk": f"GREENHOUSE#{house_id}", "sk": "SENSOR_OFFLINE_STATE"}
    previous = table.get_item(Key=state_key, ConsistentRead=True).get("Item", {})
    generation = enrollment_key(device, principal.device_id)
    same_source = previous.get("sourceGeneration") == generation
    if same_source and observed <= int(previous["observedAt"]):
        return []
    before = set(previous.get("offlineSensorIds", [])) if same_source else set()
    offline = offline if enabled else {}
    ids = sorted(offline)
    changed = [sensor_id for sensor_id in ids if sensor_id not in before]
    owner = {":account": principal.account_id, ":tenant": principal.tenant_id}
    device_condition = "accountId=:account AND tenantId=:tenant AND enabled=:on AND greenhouseId=:house AND deviceSecretHash=:secret"
    device_values = {**owner, ":on": True, ":house": house_id, ":secret": device["deviceSecretHash"]}
    device_condition += " AND (attribute_not_exists(communicationSuspended) OR communicationSuspended=:off)"
    device_values[":off"] = False
    if "reauthorizedAt" in device:
        device_condition += " AND reauthorizedAt=:enrolled"
        device_values[":enrolled"] = device["reauthorizedAt"]
    else:
        device_condition += " AND attribute_not_exists(reauthorizedAt)"

    state_write = {
        "TableName": table.name,
        "Item": {**state_key, "accountId": principal.account_id, "tenantId": principal.tenant_id,
                 "sourceGeneration": generation, "observedAt": observed, "offlineSensorIds": ids},
        "ConditionExpression": "attribute_not_exists(pk)",
    }
    if previous:
        state_write.update(
            ConditionExpression="accountId=:account AND tenantId=:tenant AND sourceGeneration=:generation AND observedAt=:previous",
            ExpressionAttributeValues={**owner, ":generation": previous["sourceGeneration"], ":previous": previous["observedAt"]},
        )
    transaction = [
        {"ConditionCheck": {"TableName": table.name, "Key": {"pk": f"DEVICE#{principal.device_id}", "sk": "METADATA"},
                            "ConditionExpression": device_condition, "ExpressionAttributeValues": device_values}},
        {"ConditionCheck": {"TableName": table.name, "Key": {"pk": state_key["pk"], "sk": "METADATA"},
                            "ConditionExpression": "accountId=:account AND tenantId=:tenant AND serverDeviceId=:server",
                            "ExpressionAttributeValues": {**owner, ":server": principal.device_id}}},
        {"Put": state_write},
        {"Put": {"TableName": table.name, "Item": backup_item}},
    ]
    if changed:
        alert = new_alert(account_id=principal.account_id, tenant_id=principal.tenant_id, greenhouse_id=house_id,
                          device_id=principal.device_id, alert_type="SENSOR_COMMUNICATION_FAILURE", created_at=now)
        names = ", ".join(offline[sensor_id] for sensor_id in changed[:5])
        if len(changed) > 5:
            names += f" and {len(changed) - 5} more"
        alert.update(message=f"{names} stopped reporting to the greenhouse phone.", sourceGeneration=generation)
        if len(changed) == 1:
            alert["sensorId"] = changed[0]
        transaction.append({"Put": {"TableName": table.name, "Item": alert, "ConditionExpression": "attribute_not_exists(pk)"}})
    return transaction
