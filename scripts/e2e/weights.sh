#!/usr/bin/env bash
#
# What each e2e journey cost tonight, in seconds — the weights that
# `impacted.sh select` balances a pull request's lots with (MAG-701).
#
# Usage:  weights.sh <directory of verdict JSON files>
#
# Reads the verdicts the e2e jobs uploaded (scripts/e2e/verdict.sh) and prints
#   {"version": 1, "generatedAt": …, "web": {"tests/x.spec.ts": s},
#    "mobile": {"08-grocery-realtime": s}}
# Web: the sum of a spec file's tests (all projects, the last attempt of each).
# Mobile: the phone's measure, the format every pull request plays. Only a
# journey that ran to the end counts: a failed one may have stopped early or
# hung, so its time says nothing about what it costs. A journey seen in several
# verdicts keeps its longest time.

set -euo pipefail

dir="${1:?usage: weights.sh <verdict directory>}"
verdicts='[]'
if [ -d "$dir" ]; then
  verdicts="$(find "$dir" -name '*.json' -type f | sort | xargs -r cat | jq -s -c '.')" || verdicts='[]'
fi

jq -n --argjson verdicts "$verdicts" --arg now "$(date -u +%Y-%m-%dT%H:%M:%SZ)" '
  def weights($platform; keep; key):
    [ $verdicts[] | select(.platform == $platform and ((.["label"] // "") | keep))
      | .journeys[]? | select((.status == "passed" or .status == "flaky") and ((.duration // 0) > 0)) ]
    | group_by(.id)
    | map({key: (.[0].id | key), value: ([.[].duration] | max)})
    | from_entries;
  {version: 1, generatedAt: $now,
   web: weights("web"; true; ltrimstr("e2e/web/")),
   mobile: weights("mobile"; startswith("mobile phone "); ltrimstr("e2e/mobile/flows/") | rtrimstr(".yaml"))}'
