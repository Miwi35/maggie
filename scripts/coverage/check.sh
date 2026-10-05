#!/usr/bin/env bash
#
# The coverage ratchet (MAG-105): a component's line coverage may not go below
# its baseline, which only moves up.
#
#   scripts/coverage/check.sh <measure.json> [baseline.json]
#
# <measure.json> is extract.sh's output. It is rewritten in place with the
# verdict (`baseline`, `status`: ok | improved | dropped | new) for report.sh.
# Exit 0 when not below the baseline, 10 when it dropped. A component with no
# baseline yet passes as `new`: update.sh records it.
#
# COVERAGE_TOLERANCE (default 0.10 points) absorbs run-to-run noise — a
# randomised test order, a test that skips on a slow runner. Lowering the
# baseline on purpose is a diff of baseline.json, which a reviewer sees.

set -euo pipefail

[ "$#" -ge 1 ] || { echo "usage: check.sh <measure.json> [baseline.json]" >&2; exit 64; }
measure="$1"
baseline_file="${2:-$(dirname "$0")/baseline.json}"
tolerance="${COVERAGE_TOLERANCE:-0.10}"

component="$(jq -r .component "$measure")"
percent="$(jq -r .percent "$measure")"
baseline="$(jq -r --arg c "$component" '.[$c] // "null"' "$baseline_file")"

status="$(jq -nr --argjson now "$percent" --argjson base "$baseline" --argjson tol "$tolerance" '
  if $base == null then "new"
  elif $now < $base - $tol then "dropped"
  elif $now > $base + $tol then "improved"
  else "ok" end')"

jq --argjson base "$baseline" --arg status "$status" '. + {baseline: $base, status: $status}' "$measure" > "$measure.tmp"
mv "$measure.tmp" "$measure"

case "$status" in
  dropped)
    echo "::error title=Coverage dropped ($component)::$component line coverage is $percent %, below its baseline of $baseline % (tolerance $tolerance). Cover what you changed, or lower scripts/coverage/baseline.json in this PR and say why."
    echo "$component: $percent % < baseline $baseline % — coverage dropped" >&2
    exit 10
    ;;
  improved) echo "$component: $percent % (baseline $baseline %) — raise it: task coverage:ratchet" ;;
  new) echo "$component: $percent % — no baseline yet: task coverage:ratchet" ;;
  *) echo "$component: $percent % (baseline $baseline %)" ;;
esac
