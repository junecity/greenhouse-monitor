from __future__ import annotations

import os
import time
import uuid

import boto3


table = boto3.resource("dynamodb").Table(os.environ["TABLE_NAME"])
AUDIT_RETENTION_SECONDS = int(os.environ.get("ADMIN_AUDIT_RETENTION_SECONDS", str(365 * 24 * 60 * 60)))


def handler(event, context):
    """Cognito PostAuthentication trigger for successful private-admin sign-ins."""
    now = int(time.time())
    subject = event.get("request", {}).get("userAttributes", {}).get("sub", "unknown")
    table.put_item(Item={
        "pk": "ADMIN_AUDIT",
        "sk": f"{now:020d}#{uuid.uuid4().hex}",
        "actorSubject": subject,
        "actorRole": "AUTHENTICATED_ADMIN",
        "action": "ADMIN_LOGIN",
        "resourceType": "ADMIN_SESSION",
        "resourceId": subject,
        "outcome": "SUCCESS",
        "requestId": getattr(context, "aws_request_id", "cognito-trigger"),
        "createdAt": now,
        "expiresAt": now + AUDIT_RETENTION_SECONDS,
    })
    return event
