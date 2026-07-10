#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd -- "$script_dir/../.." && pwd)"
jobs_dir="$script_dir/jobs"
tasks_dir="$script_dir/tasks"
archive_path="$script_dir/dist/karcarthy-harbor.tgz"

"$script_dir/package.sh"
rm -rf "$jobs_dir"
mkdir -p "$jobs_dir"

for strategy in constant first-line target-parser; do
  PYTHONPATH="$script_dir${PYTHONPATH:+:$PYTHONPATH}" \
  uvx --python 3.13 --from harbor harbor run \
    --path "$tasks_dir" \
    --agent local_acp_agent:LocalKarcarthyAcpAgent \
    --agent-kwarg "archive_path=$archive_path" \
    --agent-kwarg auth_policy=disabled \
    --agent-env "KARCARTHY_STRATEGY=$strategy" \
    --jobs-dir "$jobs_dir" \
    --job-name "$strategy" \
    --n-concurrent 3 \
    --yes
done

cd "$repo_root"
clojure -M "$script_dir/summarize.clj" "$jobs_dir"
printf 'Harbor jobs: %s\n' "$jobs_dir"
printf 'View: uvx --python 3.13 --from harbor harbor view %q\n' "$jobs_dir"
