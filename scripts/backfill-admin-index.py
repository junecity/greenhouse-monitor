#!/usr/bin/env python3
"""Backfill the admin GSI projection after the approval-gated table update.

Dry-run is the default. No email, subject, token, or other customer value is printed.
"""

from __future__ import annotations

import argparse
import time

import boto3


def admin_projection(item: dict) -> tuple[str, str] | None:
    pk, sk = item.get("pk", ""), item.get("sk", "")
    created = int(item.get("createdAt", item.get("verifiedAt", int(time.time()))))
    if pk.startswith("ACCOUNT#") and sk == "METADATA":
        return "ACCOUNT", f"{created:020d}#{item.get('accountId', pk.split('#', 1)[1])}"
    if pk.startswith("ACCOUNT#") and sk == "ENTITLEMENT":
        return "ENTITLEMENT", f"{created:020d}#{item.get('accountId', pk.split('#', 1)[1])}"
    if pk.startswith("ACCOUNT#") and sk.startswith("DEVICE#"):
        return "ACCOUNT_DEVICE", f"{created:020d}#{item.get('deviceId', sk.split('#', 1)[1])}"
    if pk.startswith("DEVICE#") and sk == "METADATA":
        return "DEVICE", f"{created:020d}#{item.get('deviceId', pk.split('#', 1)[1])}"
    if pk.startswith("GREENHOUSE#") and sk == "METADATA":
        return "GREENHOUSE", f"{created:020d}#{item.get('greenhouseId', pk.split('#', 1)[1])}"
    if pk.startswith("STREAM_SESSION#") and sk == "METADATA":
        return "STREAM_SESSION", f"{created:020d}#{pk.split('#', 1)[1]}"
    return None


def scan_all(table) -> list[dict]:
    items, start = [], None
    while True:
        kwargs = {"ExclusiveStartKey": start} if start else {}
        result = table.scan(**kwargs)
        items.extend(result.get("Items", []))
        start = result.get("LastEvaluatedKey")
        if not start:
            return items


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--table-name", required=True)
    parser.add_argument("--region", required=True)
    parser.add_argument("--apply", action="store_true", help="Write the projection. Without this flag, perform a dry run.")
    args = parser.parse_args()
    table = boto3.resource("dynamodb", region_name=args.region).Table(args.table_name)
    records = [(item, admin_projection(item)) for item in scan_all(table)]
    projected = [(item, projection) for item, projection in records if projection]
    print(f"Eligible records: {len(projected)}")
    print(f"Mode: {'APPLY' if args.apply else 'DRY RUN'}")
    if not args.apply:
        return
    for item, (entity, sort) in projected:
        table.update_item(
            Key={"pk": item["pk"], "sk": item["sk"]},
            UpdateExpression="SET adminEntity=:entity, adminSort=:sort",
            ConditionExpression="attribute_exists(pk) AND attribute_exists(sk)",
            ExpressionAttributeValues={":entity": entity, ":sort": sort},
        )
    print(f"Updated records: {len(projected)}")


if __name__ == "__main__":
    main()
