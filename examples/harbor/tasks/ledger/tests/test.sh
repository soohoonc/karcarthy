#!/usr/bin/env bash
set -euo pipefail

mkdir -p /logs/verifier
if python3 - <<'PY'
import sys

sys.path.insert(0, "/app")
from main import balances

transactions = [
    {"currency": "usd", "amount_cents": 1200, "status": "settled"},
    {"currency": "USD", "amount_cents": -200, "status": "settled"},
    {"currency": "eur", "amount_cents": 500, "status": "pending"},
    {"currency": "eur", "amount_cents": 700, "status": "settled"},
]

assert balances(transactions) == {"USD": 1000, "EUR": 700}
assert balances([]) == {}
PY
then
  printf '1\n' > /logs/verifier/reward.txt
else
  printf '0\n' > /logs/verifier/reward.txt
fi
