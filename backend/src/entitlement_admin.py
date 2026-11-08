from __future__ import annotations

import os
import time

import boto3
from botocore.exceptions import ClientError

from domain import AccessDenied, require_resource_id


table = boto3.resource("dynamodb").Table(os.environ["TABLE_NAME"])
ALLOWED_ACTIONS = {"GRANT_COMPLIMENTARY", "REVOKE", "RESET_FREE"}


def _account_for(event: dict) -> dict:
    subject = (event.get("subject") or "").strip()
    account_id = (event.get("accountId") or "").strip()
    if bool(subject) == bool(account_id):
        raise AccessDenied("exactly_one_account_selector_required")
    if subject:
        if len(subject) > 128:
            raise AccessDenied("invalid_subject")
        account = table.get_item(Key={"pk": f"USER#{subject}", "sk": "ACCOUNT"}, ConsistentRead=True).get("Item")
    else:
        require_resource_id(account_id)
        account = table.get_item(Key={"pk": f"ACCOUNT#{account_id}", "sk": "METADATA"}, ConsistentRead=True).get("Item")
    if not account:
        raise AccessDenied("account_not_found")
    return account


def handler(event, context):
    try:
        action = (event or {}).get("action")
        if action not in ALLOWED_ACTIONS:
            return {"statusCode": 400, "error": "invalid_action"}
        account = _account_for(event)
        account_id = account["accountId"]
        tenant_id = account["tenantId"]
        now = int(time.time())
        audit_id = getattr(context, "aws_request_id", "local-invocation")
        names = {"#state": "state"}
        values = {
            ":account": account_id, ":tenant": tenant_id, ":now": now,
            ":audit": audit_id, ":one": 1,
        }
        if action == "GRANT_COMPLIMENTARY":
            names["#source"] = "source"
            expression = (
                "SET plan=:plan, #state=:state, #source=:source, features=:features, "
                "verifiedAt=:now, lastAdminRequestId=:audit, entitlementRevision=if_not_exists(entitlementRevision,:zero)+:one "
                "REMOVE expiresAt"
            )
            values.update({
                ":plan": "COMPLIMENTARY", ":state": "ACTIVE",
                ":source": "COMPLIMENTARY_BACKEND_GRANT",
                ":features": ["REMOTE_LIVE_VIDEO"], ":zero": 0,
            })
        elif action == "REVOKE":
            expression = (
                "SET #state=:state, features=:features, verifiedAt=:now, lastAdminRequestId=:audit, "
                "entitlementRevision=if_not_exists(entitlementRevision,:zero)+:one"
            )
            values.update({":state": "REVOKED", ":features": [], ":zero": 0})
        else:
            names["#source"] = "source"
            expression = (
                "SET plan=:plan, #state=:state, #source=:source, features=:features, "
                "verifiedAt=:now, lastAdminRequestId=:audit, entitlementRevision=if_not_exists(entitlementRevision,:zero)+:one "
                "REMOVE expiresAt"
            )
            values.update({
                ":plan": "FREE", ":state": "ACTIVE", ":source": "NONE",
                ":features": [], ":zero": 0,
            })
        updated = table.update_item(
            Key={"pk": f"ACCOUNT#{account_id}", "sk": "ENTITLEMENT"},
            UpdateExpression=expression,
            ConditionExpression="accountId=:account AND tenantId=:tenant",
            ExpressionAttributeNames=names,
            ExpressionAttributeValues=values,
            ReturnValues="ALL_NEW",
        ).get("Attributes", {})
        return {
            "statusCode": 200, "accountId": account_id,
            "plan": updated.get("plan"), "state": updated.get("state"),
            "source": updated.get("source"), "features": updated.get("features", []),
            "verifiedAt": int(updated.get("verifiedAt", now)),
        }
    except AccessDenied as error:
        return {"statusCode": 403, "error": str(error)}
    except ClientError:
        return {"statusCode": 503, "error": "dependency_error"}
