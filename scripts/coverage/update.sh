#!/usr/bin/env bash
#
# Move the baseline up to what CI measured (MAG-105).
#
#   scripts/coverage/update.sh <dir of measure .json> [baseline.json]
#
# Raises a component's baseline to its measure, records a new component, and
# never lowers one: to accept a drop, edit baseline.json by hand.

set -euo pipefail

[ "$#" -ge 1 ] || { echo "usage: update.sh <dir> [baseline.json]" >&2; exit 64; }
dir="$1"
baseline_file="${2:-$(dirname "$0")/baseline.json}"

shopt -s nullglob
files=("$dir"/*.json)
[ "${#files[@]}" -gt 0 ] || { echo "update.sh: no measure in $dir" >&2; exit 1; }

[ -f "$baseline_file" ] || echo '{}' > "$baseline_file"
jq -S --slurpfile base "$baseline_file" -n '
  reduce (inputs | select(.component != null)) as $m ($base[0];
    .[$m.component] = ([(.[$m.component] // 0), $m.percent] | max + 0))' "${files[@]}" > "$baseline_file.tmp"
mv "$baseline_file.tmp" "$baseline_file"
cat "$baseline_file"
