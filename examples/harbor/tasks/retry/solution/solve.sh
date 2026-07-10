#!/usr/bin/env bash
set -euo pipefail

cat > /app/main.py <<'PY'
def retry_delays(attempts, base=1, cap=30):
    if attempts <= 0:
        return []
    return [min(base * (2 ** index), cap) for index in range(attempts)]
PY
