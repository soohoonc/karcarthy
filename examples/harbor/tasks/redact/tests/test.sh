#!/usr/bin/env bash
set -euo pipefail

mkdir -p /logs/verifier
if python3 - <<'PY'
import sys

sys.path.insert(0, "/app")
from main import redact

payload = {
    "user": "ada",
    "Password": "secret",
    "profile": {
        "api_key": "key-123",
        "roles": ["admin"],
    },
    "sessions": [
        {"TOKEN": "token-1", "active": True},
        {"authorization": "Bearer abc", "active": False},
    ],
}

assert redact(payload) == {
    "user": "ada",
    "Password": "***",
    "profile": {
        "api_key": "***",
        "roles": ["admin"],
    },
    "sessions": [
        {"TOKEN": "***", "active": True},
        {"authorization": "***", "active": False},
    ],
}
assert redact("plain") == "plain"
PY
then
  printf '1\n' > /logs/verifier/reward.txt
else
  printf '0\n' > /logs/verifier/reward.txt
fi
