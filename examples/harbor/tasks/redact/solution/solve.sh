#!/usr/bin/env bash
set -euo pipefail

cat > /app/main.py <<'PY'
SENSITIVE_KEYS = {"password", "token", "api_key", "authorization"}


def redact(value):
    if isinstance(value, dict):
        return {
            key: "***" if str(key).lower() in SENSITIVE_KEYS else redact(item)
            for key, item in value.items()
        }
    if isinstance(value, list):
        return [redact(item) for item in value]
    return value
PY
