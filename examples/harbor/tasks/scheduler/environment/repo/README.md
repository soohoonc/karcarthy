# Scheduler

This repository contains a small SQLite-backed delivery scheduler.

`Store.claim(now)` must reserve and return at most one due job. Jobs with the
same due time are ordered by descending priority. `Store.fail(id, now)` applies
exponential retry backoff and permanently fails jobs that exhaust their
attempts.

Run the public tests with:

```bash
python3 -m unittest -v
```
