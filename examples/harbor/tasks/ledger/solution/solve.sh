#!/usr/bin/env bash
set -euo pipefail

cat > /app/main.py <<'PY'
def balances(transactions):
    result = {}
    for item in transactions:
        if item.get("status") != "settled":
            continue
        currency = item["currency"].upper()
        result[currency] = result.get(currency, 0) + item["amount_cents"]
    return result
PY
