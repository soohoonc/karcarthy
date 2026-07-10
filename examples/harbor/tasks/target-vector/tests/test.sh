#!/usr/bin/env bash
set -euo pipefail

mkdir -p /logs/verifier
if [[ -f /app/answer.txt ]] && [[ "$(cat /app/answer.txt)" == "vector_7" ]]; then
  printf '1\n' > /logs/verifier/reward.txt
else
  printf '0\n' > /logs/verifier/reward.txt
  printf 'expected vector_7, found: ' > /logs/verifier/details.txt
  if [[ -f /app/answer.txt ]]; then
    cat /app/answer.txt >> /logs/verifier/details.txt
  else
    printf '<missing>\n' >> /logs/verifier/details.txt
  fi
fi
