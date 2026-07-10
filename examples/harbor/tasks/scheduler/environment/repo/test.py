import tempfile
import unittest

from store import Store


class StoreTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.path = f"{self.directory.name}/jobs.db"
        self.store = Store(self.path)

    def tearDown(self):
        self.directory.cleanup()

    def test_claim_reserves_a_job(self):
        self.store.add("email", {"to": "user@example.com"}, run_at=10)
        self.assertEqual("email", self.store.claim(10)["id"])
        self.assertIsNone(Store(self.path).claim(10))

    def test_failure_schedules_a_future_retry(self):
        self.store.add("email", {}, run_at=10)
        self.store.claim(10)
        self.store.fail("email", now=20)
        self.assertIsNone(self.store.claim(79))
        self.assertEqual("email", self.store.claim(80)["id"])


if __name__ == "__main__":
    unittest.main()
