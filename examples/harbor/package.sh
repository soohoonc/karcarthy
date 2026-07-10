#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
dist_dir="$script_dir/dist"
stage_dir="$dist_dir/package"

cd "$script_dir"
clojure -T:build uber

rm -rf "$stage_dir"
mkdir -p "$stage_dir"
cp "$dist_dir/karcarthy-harbor-example-standalone.jar" \
  "$stage_dir/karcarthy-example-standalone.jar"
cp "$script_dir/package/karcarthy" "$stage_dir/karcarthy"
chmod +x "$stage_dir/karcarthy"

tar -C "$stage_dir" -czf "$dist_dir/karcarthy-harbor.tgz" \
  karcarthy karcarthy-example-standalone.jar

printf 'Built %s\n' "$dist_dir/karcarthy-harbor.tgz"
