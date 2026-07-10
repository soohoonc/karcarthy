#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"

if [[ "${1:-}" == "--help" || "${1:-}" == "-h" ]]; then
  exec uv run --python 3.13 "$script_dir/optimize.py" "$@"
fi

if [[ "${KARCARTHY_LIVE:-}" != "1" ]]; then
  printf 'Set KARCARTHY_LIVE=1 to authorize paid Agent optimization.\n' >&2
  exit 2
fi

if [[ -z "${RESPONSES_API_KEY:-}${OPENAI_API_KEY:-}" ]]; then
  printf 'Set RESPONSES_API_KEY or OPENAI_API_KEY.\n' >&2
  exit 2
fi

"$script_dir/package.sh"
exec uv run --python 3.13 "$script_dir/optimize.py" "$@"
