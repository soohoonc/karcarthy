#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd -- "$script_dir/../.." && pwd)"
dist_dir="$script_dir/dist"
stage_dir="$dist_dir/package"

cd "$repo_root"
clojure -T:build uber

rm -rf "$stage_dir"
mkdir -p "$stage_dir"
cp target/karcarthy-0.0.2-standalone.jar \
  "$stage_dir/karcarthy-standalone.jar"
cp "$script_dir/package/karcarthy" "$stage_dir/karcarthy"
chmod +x "$stage_dir/karcarthy"

tar -C "$stage_dir" -czf "$dist_dir/karcarthy-harbor.tgz" \
  karcarthy karcarthy-standalone.jar

printf 'Built %s\n' "$dist_dir/karcarthy-harbor.tgz"
