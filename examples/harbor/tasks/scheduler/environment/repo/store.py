import json
import sqlite3


class Store:
    def __init__(self, path):
        self.db = sqlite3.connect(path, isolation_level=None)
        self.db.row_factory = sqlite3.Row
        self.db.execute(
            """
            CREATE TABLE IF NOT EXISTS jobs (
                id TEXT PRIMARY KEY,
                payload TEXT NOT NULL,
                run_at INTEGER NOT NULL,
                priority INTEGER NOT NULL DEFAULT 0,
                state TEXT NOT NULL DEFAULT 'pending',
                attempts INTEGER NOT NULL DEFAULT 0,
                max_attempts INTEGER NOT NULL DEFAULT 3
            )
            """
        )

    def add(self, job_id, payload, run_at, priority=0, max_attempts=3):
        self.db.execute(
            """
            INSERT INTO jobs(id, payload, run_at, priority, max_attempts)
            VALUES (?, ?, ?, ?, ?)
            """,
            (job_id, json.dumps(payload), run_at, priority, max_attempts),
        )

    def claim(self, now):
        row = self.db.execute(
            """
            SELECT * FROM jobs
            WHERE state = 'pending' AND run_at <= ?
            ORDER BY run_at ASC
            LIMIT 1
            """,
            (now,),
        ).fetchone()
        return self._job(row) if row else None

    def fail(self, job_id, now):
        self.db.execute(
            """
            UPDATE jobs
            SET state = 'pending', attempts = attempts + 1, run_at = ?
            WHERE id = ?
            """,
            (now, job_id),
        )

    def get(self, job_id):
        return self._job(
            self.db.execute("SELECT * FROM jobs WHERE id = ?", (job_id,)).fetchone()
        )

    @staticmethod
    def _job(row):
        if row is None:
            return None
        job = dict(row)
        job["payload"] = json.loads(job["payload"])
        return job
