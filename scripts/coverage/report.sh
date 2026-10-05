#!/usr/bin/env bash
#
# The coverage section of a pull request comment (MAG-105): one row per
# component, from the verdicts check.sh left in a directory.
#
#   scripts/coverage/report.sh <title> <marker> <dir of measure .json>
#
# <marker> is the hidden first line the workflow finds the comment by, to
# update it rather than add one per push.

set -euo pipefail

[ "$#" -eq 3 ] || { echo "usage: report.sh <title> <marker> <dir>" >&2; exit 64; }
title="$1" marker="$2" dir="$3"

echo "<!-- $marker -->"
echo "### $title"
echo
shopt -s nullglob
files=("$dir"/*.json)
if [ "${#files[@]}" -eq 0 ]; then
  echo "No component was measured on this change."
  exit 0
fi
echo "| Component | Lines covered | Coverage | Baseline | Verdict |"
echo "|---|---|---|---|---|"
jq -rs 'sort_by(.component)[] |
  "| \(.component) | \(.covered) / \(.total) | \(.percent) % | \(if .baseline == null then "—" else "\(.baseline) %" end) | " +
  ({dropped: "❌ below the baseline", improved: "⬆️ above — raise it", new: "🆕 no baseline yet", ok: "✅ held"}[.status] // "?") + " |"' "${files[@]}"
echo
echo "The CI fails when a component falls more than 0.10 point under its baseline (\`scripts/coverage/baseline.json\`). After a gain: \`task coverage:ratchet\`."
