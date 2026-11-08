import json
import os
import time

import boto3
from boto3.dynamodb.conditions import Key
from botocore.exceptions import ClientError

from alerts import new_alert
from domain import is_missing_heartbeat


table = boto3.resource("dynamodb").Table(os.environ["TABLE_NAME"])
sns = boto3.client("sns")


def handler(_event, _context):
    cutoff = int(time.time()) - int(os.environ.get("OFFLINE_TIMEOUT_SECONDS", "180"))
    alerts = 0
    for shard in range(16):
        exclusive_start_key = None
        while True:
            query = {
                "IndexName": "heartbeats",
                "KeyConditionExpression": Key("heartbeatShard").eq(shard) & Key("lastHeartbeat").lt(cutoff),
                "ProjectionExpression": "pk, sk, accountId, deviceId, greenhouseId, tenantId, lastHeartbeat, enabled",
            }
            if exclusive_start_key:
                query["ExclusiveStartKey"] = exclusive_start_key
            result = table.query(**query)
            for device in result.get("Items", []):
                if not is_missing_heartbeat(device, cutoff):
                    continue
                now = int(time.time())
                alert = new_alert(
                    account_id=device["accountId"], tenant_id=device["tenantId"],
                    greenhouse_id=device.get("greenhouseId"), device_id=device["deviceId"],
                    alert_type="SERVER_OFFLINE", created_at=now,
                )
                try:
                    # Atomically claim the incident so a one-minute schedule does not
                    # repeatedly notify until a new heartbeat clears offlineAlertedAt.
                    table.meta.client.transact_write_items(TransactItems=[
                        {"Update": {
                            "TableName": table.name,
                            "Key": {"pk": device["pk"], "sk": device["sk"]},
                            "UpdateExpression": "SET offlineAlertedAt=:now",
                            "ConditionExpression": "attribute_not_exists(offlineAlertedAt) AND lastHeartbeat < :cutoff",
                            "ExpressionAttributeValues": {":now": now, ":cutoff": cutoff},
                        }},
                        {"Put": {
                            "TableName": table.name,
                            "Item": alert,
                            "ConditionExpression": "attribute_not_exists(pk)",
                        }},
                    ])
                except ClientError as error:
                    if error.response.get("Error", {}).get("Code") in {"ConditionalCheckFailedException", "TransactionCanceledException"}:
                        continue
                    raise
                # The topic is an internal event destination. Customer delivery routing and
                # acknowledgement are resolved by a later tenant-aware notification worker.
                sns.publish(
                    TopicArn=os.environ["ALERT_TOPIC_ARN"],
                    Subject="Greenhouse server heartbeat missing",
                    Message=json.dumps({"alertId": alert["id"], "type": "SERVER_OFFLINE", "tenantId": device["tenantId"], "greenhouseId": device.get("greenhouseId"), "deviceId": device["deviceId"], "lastHeartbeat": int(device["lastHeartbeat"])}),
                    MessageAttributes={"alertType": {"DataType": "String", "StringValue": "SERVER_OFFLINE"}},
                )
                alerts += 1
            exclusive_start_key = result.get("LastEvaluatedKey")
            if not exclusive_start_key:
                break
    return {"checkedAt": int(time.time()), "alerts": alerts}

