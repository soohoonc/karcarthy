#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
jobs_dir="$script_dir/jobs"
archive_path="$script_dir/dist/karcarthy-harbor.tgz"
model="${KARCARTHY_OPENAI_MODEL:-gpt-5.6}"
attempts="${KARCARTHY_ATTEMPTS:-1}"

if [[ "${KARCARTHY_LIVE:-}" != "1" ]]; then
  printf 'Set KARCARTHY_LIVE=1 to authorize this paid live evaluation.\n' >&2
  exit 2
fi

if [[ -n "${RESPONSES_API_KEY:-}" ]]; then
  auth_name="RESPONSES_API_KEY"
  auth_value="$RESPONSES_API_KEY"
elif [[ -n "${OPENAI_API_KEY:-}" ]]; then
  auth_name="OPENAI_API_KEY"
  auth_value="$OPENAI_API_KEY"
else
  printf 'Set RESPONSES_API_KEY or OPENAI_API_KEY.\n' >&2
  exit 2
fi

"$script_dir/package.sh"
rm -rf "$jobs_dir"
mkdir -p "$jobs_dir"

for specialist in 0 1; do
  if [[ "$specialist" == "0" ]]; then
    candidate="direct"
  else
    candidate="specialist"
  fi

  PYTHONPATH="$script_dir${PYTHONPATH:+:$PYTHONPATH}" \
  uvx --python 3.13 --from harbor harbor run \
    --path "$script_dir/tasks/scheduler" \
    --agent agent:Agent \
    --agent-kwarg "archive_path=$archive_path" \
    --agent-kwarg auth_policy=disabled \
    --agent-env "$auth_name=$auth_value" \
    --agent-env "KARCARTHY_OPENAI_MODEL=$model" \
    --agent-env "KARCARTHY_SPECIALIST=$specialist" \
    --jobs-dir "$jobs_dir" \
    --job-name "$candidate" \
    --n-attempts "$attempts" \
    --n-concurrent 1 \
    --yes
done

cd "$script_dir/../.."
clojure -M "$script_dir/summarize.clj" "$jobs_dir"

printf 'Harbor jobs: %s\n' "$jobs_dir"
printf 'Scoreboard: %s\n' "$jobs_dir/scoreboard.json"
printf 'View: uvx --python 3.13 --from harbor harbor view %q\n' "$jobs_dir"
