#!/usr/bin/env bash
set -euo pipefail

python3 - <<'PY'
from pathlib import Path

path = Path("/app/store.py")
text = path.read_text()
start = text.index("    def claim(self, now):")
end = text.index("    def get(self, job_id):")
replacement = '''    def claim(self, now):
        self.db.execute("BEGIN IMMEDIATE")
        try:
            row = self.db.execute(
                """
                SELECT * FROM jobs
                WHERE state = 'pending' AND run_at <= ?
                ORDER BY run_at ASC, priority DESC, id ASC
                LIMIT 1
                """,
                (now,),
            ).fetchone()
            if row is not None:
                self.db.execute(
                    "UPDATE jobs SET state = 'running' WHERE id = ?",
                    (row["id"],),
                )
            self.db.execute("COMMIT")
            return self._job(row) if row else None
        except BaseException:
            self.db.execute("ROLLBACK")
            raise

    def fail(self, job_id, now):
        row = self.db.execute(
            "SELECT attempts, max_attempts FROM jobs WHERE id = ?",
            (job_id,),
        ).fetchone()
        if row is None:
            raise KeyError(job_id)
        attempts = row["attempts"] + 1
        if attempts >= row["max_attempts"]:
            self.db.execute(
                "UPDATE jobs SET state = 'failed', attempts = ? WHERE id = ?",
                (attempts, job_id),
            )
        else:
            run_at = now + 60 * (2 ** (attempts - 1))
            self.db.execute(
                """
                UPDATE jobs
                SET state = 'pending', attempts = ?, run_at = ?
                WHERE id = ?
                """,
                (attempts, run_at, job_id),
            )

'''
path.write_text(text[:start] + replacement + text[end:])
PY
