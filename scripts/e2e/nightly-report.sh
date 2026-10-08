#!/usr/bin/env bash
#
# The night's e2e report (nightly-e2e.yml): every journey of every lot, from the
# verdicts the e2e jobs uploaded (scripts/e2e/verdict.sh).
#
# Usage:  nightly-report.sh <directory of verdict JSON files> <result of the e2e jobs>
#
# Prints Markdown: for each journey that failed, its area in e2e/impact-map.yml
# and the last commit that touched that area — where to look first; the journeys
# that passed only on a retry (flaky: candidates for the quarantine); the
# journeys in quarantine that still fail. A green night prints one line.
#
# Exit status: 0 green, 1 red — a journey failed, or the e2e jobs did not succeed
# although no journey says why (the stack, the APK or a runner broke).
# Needs the history (`fetch-depth: 0`) for the last commits.

set -euo pipefail

# shellcheck source=scripts/e2e/lib.sh
. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

dir="${1:?usage: nightly-report.sh <verdict directory> <e2e result>}"
result="${2:?usage: nightly-report.sh <verdict directory> <e2e result>}"

verdicts="$(find "$dir" -name '*.json' -type f 2>/dev/null | sort | xargs -r cat | jq -s -c '.' 2>/dev/null || echo '[]')"
map="$(e2e_map_json 2>/dev/null || echo '{}')"

# Every journey once: its worst status over the lots and devices it played on.
journeys="$(jq -c '
  [ .[] | .["label"] as $lot | .journeys[]? | . + {lot: $lot} ]
  | group_by(.id)
  | map({
      id: .[0].id,
      quarantined: (.[0].quarantined // false),
      status: ([.[].status] as $s
        | if ($s | index("failed")) or ($s | index("not-run")) then "failed"
          elif ($s | index("flaky")) then "flaky" else "passed" end),
      lots: [.[] | select(.status != "passed") | "\(.lot): \(.status)"]
    })' <<<"$verdicts")"

# Globs → git pathspecs: braces expanded, `:(glob)` so that `*` stays in a directory.
pathspecs_of() {
  jq -r --arg id "$1" "$E2E_JQ_DEFS"'
    def expand: if test("\\{") then
        (capture("^(?<pre>[^{]*)\\{(?<alts>[^}]*)\\}(?<post>.*)$") | .pre as $pre | .post as $post
          | .alts | split(",")[] | ($pre + . + $post) | expand)
      else . end;
    (journeys[] | select(.id == $id) | globs_of($map)[] | expand | ":(glob)" + .) // ":(glob)" + $id
  ' --argjson map "$map" <<<"$map" | sort -u
}

areas_of() {
  jq -r --arg id "$1" "$E2E_JQ_DEFS"'[journeys[] | select(.id == $id) | .areas[]] | if length == 0 then "—" else join(", ") end' <<<"$map"
}

last_commit() {
  local specs=()
  mapfile -t specs < <(pathspecs_of "$1")
  git -C "$E2E_ROOT" log -1 --format='%h %s (%an, %ad)' --date=short -- "${specs[@]}" 2>/dev/null || true
}

played="$(jq length <<<"$journeys")"
red="$(jq -c '[.[] | select(.status == "failed" and (.quarantined | not))]' <<<"$journeys")"
flaky="$(jq -c '[.[] | select(.status == "flaky")]' <<<"$journeys")"
still="$(jq -c '[.[] | select(.status == "failed" and .quarantined)]' <<<"$journeys")"
status=0
[ "$(jq length <<<"$red")" -eq 0 ] || status=1
# The jobs failed and no journey says why: the stack, the APK or a runner.
if [ "$status" -eq 0 ] && [ "$result" != success ]; then
  status=1
  outside=1
fi

echo "## Nightly e2e — $([ "$status" -eq 0 ] && echo green || echo RED)"
echo
echo "$played journeys played (web and mobile, quarantine included), e2e jobs: $result."
echo

if [ "${outside:-0}" = 1 ]; then
  echo "**The e2e jobs did not succeed, and no journey failed:** the stack, the APK or a runner broke before or outside the journeys — read the failed job's log."
  echo
fi

if [ "$(jq length <<<"$red")" -gt 0 ]; then
  echo "### Failed"
  echo
  echo "| Journey | Area (e2e/impact-map.yml) | Last commit in that area | Where |"
  echo "|---|---|---|---|"
  while IFS= read -r row; do
    id="$(jq -r .id <<<"$row")"
    echo "| \`$(basename "$id")\` | $(areas_of "$id") | $(last_commit "$id" | sed 's/|/\\|/g') | $(jq -r '.lots | join("<br>")' <<<"$row") |"
  done < <(jq -c '.[]' <<<"$red")
  echo
fi

if [ "$(jq length <<<"$flaky")" -gt 0 ]; then
  echo "### Flaky — passed only on a retry, candidates for the quarantine"
  echo
  jq -r '.[] | "- `\(.id | split("/") | last)`\(if .quarantined then " (already in quarantine)" else "" end) — \(.lots | join(", "))"' <<<"$flaky"
  echo
fi

if [ "$(jq length <<<"$still")" -gt 0 ]; then
  echo "### Still failing in quarantine"
  echo
  jq -r '.[] | "- `\(.id | split("/") | last)` — \(.lots | join(", "))"' <<<"$still"
  echo
fi

exit "$status"
