#!/usr/bin/env bash
set -euo pipefail

mkdir -p /logs/verifier
if python3 - <<'PY'
import sys

sys.path.insert(0, "/app")
from main import retry_delays

assert retry_delays(0) == []
assert retry_delays(-2) == []
assert retry_delays(3) == [1, 2, 4]
assert retry_delays(4, base=2, cap=5) == [2, 4, 5, 5]
PY
then
  printf '1\n' > /logs/verifier/reward.txt
else
  printf '0\n' > /logs/verifier/reward.txt
fi
