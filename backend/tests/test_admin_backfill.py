import importlib.util
import pathlib
import unittest


SCRIPT = pathlib.Path(__file__).parents[2] / "scripts" / "backfill-admin-index.py"
spec = importlib.util.spec_from_file_location("backfill_admin_index", SCRIPT)
module = importlib.util.module_from_spec(spec)
assert spec.loader
spec.loader.exec_module(module)


class AdminBackfillTests(unittest.TestCase):
    def test_projects_supported_records_without_personal_data(self):
        entity, sort = module.admin_projection({
            "pk": "ACCOUNT#account-12345678", "sk": "METADATA",
            "accountId": "account-12345678", "createdAt": 100,
            "email": "private@example.test",
        })
        self.assertEqual("ACCOUNT", entity)
        self.assertEqual("00000000000000000100#account-12345678", sort)
        self.assertNotIn("private", sort)

    def test_ignores_alerts_and_pairing_secrets(self):
        self.assertIsNone(module.admin_projection({"pk": "ACCOUNT#a", "sk": "ALERT#1", "createdAt": 1}))
        self.assertIsNone(module.admin_projection({"pk": "PAIRING#secret", "sk": "CHALLENGE", "createdAt": 1}))


if __name__ == "__main__":
    unittest.main()
