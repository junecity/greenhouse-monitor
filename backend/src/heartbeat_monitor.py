import json
import os
import time

import boto3
from boto3.dynamodb.conditions import Key


table = boto3.resource("dynamodb").Table(os.environ["TABLE_NAME"])
sns = boto3.client("sns")


def handler(_event, _context):
    cutoff = int(time.time()) - int(os.environ.get("OFFLINE_TIMEOUT_SECONDS", "180"))
    alerts = 0
    for shard in range(16):
        result = table.query(
            IndexName="heartbeats",
            KeyConditionExpression=Key("heartbeatShard").eq(shard) & Key("lastHeartbeat").lt(cutoff),
            ProjectionExpression="deviceId, greenhouseId, tenantId, lastHeartbeat, enabled",
        )
        for device in result.get("Items", []):
            if not device.get("enabled"):
                continue
            # The topic is an internal event destination. Customer delivery routing and
            # acknowledgement are resolved by a later tenant-aware notification worker.
            sns.publish(
                TopicArn=os.environ["ALERT_TOPIC_ARN"],
                Subject="Greenhouse server heartbeat missing",
                Message=json.dumps({"type": "SERVER_OFFLINE", "tenantId": device["tenantId"], "greenhouseId": device.get("greenhouseId"), "deviceId": device["deviceId"], "lastHeartbeat": int(device["lastHeartbeat"])}),
                MessageAttributes={"alertType": {"DataType": "String", "StringValue": "SERVER_OFFLINE"}},
            )
            alerts += 1
    return {"checkedAt": int(time.time()), "alerts": alerts}

