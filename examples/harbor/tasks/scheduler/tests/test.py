import tempfile
import unittest

import sys

sys.path.insert(0, "/app")
from store import Store


class SchedulerVerifier(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.path = f"{self.directory.name}/jobs.db"
        self.store = Store(self.path)

    def tearDown(self):
        self.directory.cleanup()

    def test_claim_is_reserved_across_connections(self):
        self.store.add("one", {}, run_at=10)
        self.assertEqual("one", self.store.claim(10)["id"])
        self.assertIsNone(Store(self.path).claim(10))
        self.assertEqual("running", self.store.get("one")["state"])

    def test_priority_breaks_equal_time_ties(self):
        self.store.add("low", {}, run_at=10, priority=1)
        self.store.add("high", {}, run_at=10, priority=9)
        self.assertEqual("high", self.store.claim(10)["id"])
        self.assertEqual("low", self.store.claim(10)["id"])

    def test_retry_backoff_and_exhaustion(self):
        self.store.add("job", {}, run_at=0, max_attempts=2)
        self.store.claim(0)
        self.store.fail("job", 10)
        first = self.store.get("job")
        self.assertEqual(1, first["attempts"])
        self.assertEqual(70, first["run_at"])
        self.assertEqual("pending", first["state"])
        self.assertIsNone(self.store.claim(69))
        self.store.claim(70)
        self.store.fail("job", 80)
        final = self.store.get("job")
        self.assertEqual(2, final["attempts"])
        self.assertEqual("failed", final["state"])
        self.assertIsNone(self.store.claim(10000))


if __name__ == "__main__":
    unittest.main()
