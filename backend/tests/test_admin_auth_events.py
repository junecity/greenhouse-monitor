import os
import pathlib
import sys
import unittest
from types import SimpleNamespace

os.environ.setdefault("AWS_ACCESS_KEY_ID", "test")
os.environ.setdefault("AWS_SECRET_ACCESS_KEY", "test")
os.environ.setdefault("AWS_DEFAULT_REGION", "us-east-1")
os.environ.setdefault("AWS_EC2_METADATA_DISABLED", "true")
os.environ.setdefault("TABLE_NAME", "greenhouse-test")
sys.path.insert(0, str(pathlib.Path(__file__).parents[1] / "src"))

import admin_auth_events


class FakeTable:
    def __init__(self):
        self.items = []

    def put_item(self, Item):
        self.items.append(Item)


class AdminAuthEventTests(unittest.TestCase):
    def test_successful_login_is_audited_without_email(self):
        previous = admin_auth_events.table
        fake = FakeTable()
        admin_auth_events.table = fake
        try:
            event = {"request": {"userAttributes": {"sub": "admin-subject", "email": "private@example.test"}}}
            returned = admin_auth_events.handler(event, SimpleNamespace(aws_request_id="request"))
        finally:
            admin_auth_events.table = previous
        self.assertIs(event, returned)
        self.assertEqual("ADMIN_LOGIN", fake.items[0]["action"])
        self.assertNotIn("email", fake.items[0])


if __name__ == "__main__":
    unittest.main()
