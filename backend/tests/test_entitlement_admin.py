import os
import pathlib
import sys
import unittest
from types import SimpleNamespace
from unittest.mock import patch

os.environ.setdefault("AWS_ACCESS_KEY_ID", "test")
os.environ.setdefault("AWS_SECRET_ACCESS_KEY", "test")
os.environ.setdefault("AWS_DEFAULT_REGION", "us-east-1")
os.environ.setdefault("AWS_EC2_METADATA_DISABLED", "true")
os.environ.setdefault("TABLE_NAME", "greenhouse-test")
sys.path.insert(0, str(pathlib.Path(__file__).parents[1] / "src"))

import entitlement_admin


class FakeTable:
    def __init__(self):
        self.update = None

    def get_item(self, Key, **_kwargs):
        if Key == {"pk": "USER#owner-subject", "sk": "ACCOUNT"}:
            return {"Item": {"accountId": "account-12345678", "tenantId": "tenant-12345678"}}
        return {}

    def update_item(self, **kwargs):
        self.update = kwargs
        values = kwargs["ExpressionAttributeValues"]
        return {"Attributes": {
            "plan": values.get(":plan", "COMPLIMENTARY"),
            "state": values[":state"], "source": values.get(":source", "COMPLIMENTARY_BACKEND_GRANT"),
            "features": values[":features"], "verifiedAt": values[":now"],
        }}


class EntitlementAdminTests(unittest.TestCase):
    def setUp(self):
        self.old_table = entitlement_admin.table
        self.fake = FakeTable()
        entitlement_admin.table = self.fake

    def tearDown(self):
        entitlement_admin.table = self.old_table

    def test_complimentary_grant_is_iam_only_backend_state(self):
        with patch("entitlement_admin.time.time", return_value=100):
            result = entitlement_admin.handler(
                {"action": "GRANT_COMPLIMENTARY", "subject": "owner-subject"},
                SimpleNamespace(aws_request_id="request-123"),
            )

        self.assertEqual(200, result["statusCode"])
        self.assertEqual("COMPLIMENTARY", result["plan"])
        self.assertEqual(["REMOTE_LIVE_VIDEO"], result["features"])
        self.assertEqual("request-123", self.fake.update["ExpressionAttributeValues"][":audit"])
        self.assertIn("REMOVE expiresAt", self.fake.update["UpdateExpression"])

    def test_revoke_removes_remote_video(self):
        result = entitlement_admin.handler(
            {"action": "REVOKE", "subject": "owner-subject"},
            SimpleNamespace(aws_request_id="request-456"),
        )

        self.assertEqual(200, result["statusCode"])
        self.assertEqual("REVOKED", result["state"])
        self.assertEqual([], result["features"])
        self.assertEqual({"#state": "state"}, self.fake.update["ExpressionAttributeNames"])

    def test_invalid_action_and_selector_fail_closed(self):
        self.assertEqual(400, entitlement_admin.handler({"action": "UNKNOWN"}, None)["statusCode"])
        result = entitlement_admin.handler({
            "action": "GRANT_COMPLIMENTARY", "subject": "owner-subject", "accountId": "account-12345678",
        }, None)
        self.assertEqual(403, result["statusCode"])


if __name__ == "__main__":
    unittest.main()
