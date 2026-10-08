#!/usr/bin/env bash
#
# One lot of Maestro journeys, judged with the quarantine applied — the script
# CI hands the emulator (`E2E Mobile journeys` in ci.yml).
#
# Usage:  E2E_MOBILE_FLOWS='flows/01-login-chat.yaml flows/05-deep-links.yaml' \
#           scripts/e2e/mobile-journeys.sh
#
# The flows come from `impacted.sh select` (the `mobile_lots` output). Two runs
# of `task e2e:mobile` on the stack and the device the job prepared:
#
#   1. the journeys that block — their verdict is the script's exit status;
#   2. the journeys in quarantine (e2e/impact-map.yml), under a time limit
#      (E2E_QUARANTINE_TIMEOUT seconds, 600 by default): they play, their result
#      is in the job summary, and nothing they do — fail, or hang the emulator as
#      02-voice-overlay once did for 17 minutes — fails the lot.
#
# With E2E_RETRY_FAILED=1 (the nightly), a flow that failed or never ran plays
# once more in a run of its own; one that passes then is « flaky », which does
# not fail the lot but is reported as a candidate for the quarantine.
#
# Each run reseeds and installs the APK again (`task e2e:mobile`), so every run
# starts from the world the first started from. The reports end up side by side
# in e2e/mobile/report/<run>/, which the job uploads.
#
# E2E_VERDICT_DIR (optional) receives one verdict JSON per run — the retry folded
# into the run it retried — named after E2E_VERDICT_LABEL.

set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="${E2E_ROOT:-$(cd "$HERE/../.." && pwd)}"
# shellcheck source=scripts/e2e/lib.sh
. "$HERE/lib.sh"

read -ra flows <<<"${E2E_MOBILE_FLOWS:?E2E_MOBILE_FLOWS names the flows of this lot}"
[ "${#flows[@]}" -gt 0 ] || { echo "E2E_MOBILE_FLOWS is empty" >&2; exit 2; }
QUARANTINE_TIMEOUT="${E2E_QUARANTINE_TIMEOUT:-600}"
RETRY="${E2E_RETRY_FAILED:-}"
LABEL="${E2E_VERDICT_LABEL:-mobile}"
REPORT="$ROOT/e2e/mobile/report"
COLLECTED="$ROOT/e2e/mobile/report.collected"
# Overridable for the tests, which have no stack and no device.
RUN_JOURNEYS="${E2E_MOBILE_RUN:-task e2e:mobile --}"
SCRATCH="$(mktemp -d)"
trap 'rm -rf "$SCRATCH"' EXIT
VERDICTS="${E2E_VERDICT_DIR:-$SCRATCH/verdicts}"
mkdir -p "$VERDICTS"
SLUG="$(tr -c 'A-Za-z0-9_-' '-' <<<"$LABEL" | sed 's/-*$//')"

quarantined="$(e2e_map_json 2>/dev/null | jq -c "$E2E_JQ_DEFS"'[journeys[] | select(.quarantine != null) | .id]' 2>/dev/null || echo '[]')"
blocking=()
lenient=()
for flow in "${flows[@]}"; do
  if jq -e --arg id "e2e/mobile/$flow" 'index($id)' >/dev/null <<<"$quarantined"; then
    lenient+=("$flow")
  else
    blocking+=("$flow")
  fi
done

rm -rf "$COLLECTED"
mkdir -p "$COLLECTED"

# play <run> <timeout seconds or 0> <verdict file> <flows…> — one run of the
# flows, its report kept under <run>, its verdict written and printed. Returns
# the verdict's status.
play() {
  local name="$1" limit="$2" out="$3" status=0
  shift 3
  echo "::group::$name: $*"
  # shellcheck disable=SC2086 # RUN_JOURNEYS is a command line
  if [ "$limit" -gt 0 ]; then
    (cd "$ROOT" && timeout --kill-after=30 "$limit" $RUN_JOURNEYS "$@") || status=$?
  else
    (cd "$ROOT" && $RUN_JOURNEYS "$@") || status=$?
  fi
  echo "::endgroup::"
  [ "$status" -ne 124 ] || echo "::warning::$name: still running after ${limit}s, stopped."
  rm -rf "${COLLECTED:?}/$name"
  if [ -d "$REPORT" ]; then mv "$REPORT" "$COLLECTED/$name"; else mkdir -p "$COLLECTED/$name"; fi
  E2E_VERDICT_LABEL="$LABEL ($name)" E2E_VERDICT_OUT="$out" \
    "$HERE/verdict.sh" mobile "$COLLECTED/$name/junit.xml" "$status" "$@"
}

# judge <run> <timeout> <flows…> — play them; with the retry on, play again what
# failed and fold the second verdict into the first. Returns the final status.
judge() {
  local name="$1" limit="$2" out retry_out status
  shift 2
  out="$VERDICTS/$SLUG-$name.json"
  play "$name" "$limit" "$out" "$@"
  status=$?
  [ "$RETRY" = 1 ] && [ -s "$out" ] || return "$status"

  local again=()
  mapfile -t again < <(jq -r '.journeys[] | select(.status == "failed" or .status == "not-run") | .id | ltrimstr("e2e/mobile/")' "$out")
  [ "${#again[@]}" -gt 0 ] || return "$status"

  retry_out="$SCRATCH/$name-retry.json"
  play "$name-retry" "$limit" "$retry_out" "${again[@]}"
  [ -s "$retry_out" ] || return "$status"

  # Passed on the retry: flaky. Anything else keeps its first verdict.
  jq --slurpfile retry "$retry_out" '
    ($retry[0].journeys | map({key: .id, value: .status}) | from_entries) as $second
    | .journeys |= map(if (.status == "failed" or .status == "not-run") and ($second[.id] == "passed" or $second[.id] == "flaky")
                       then .status = "flaky" | .retried = true
                       elif (.status == "failed" or .status == "not-run") then .retried = true
                       else . end)
    | [.journeys[] | select((.status == "failed" or .status == "not-run") and (.quarantined | not))] as $bad
    | .status = (if ($bad | length) == 0 and .reason != "the run failed outside any journey (stack, seed or runner)" and .reason != "the run failed and left no report to read"
                 then "passed" else "failed" end)
    | .reason = (if .status == "passed" then "passed, some journeys only on a retry" else .reason end)
  ' "$out" >"$SCRATCH/merged.json" && mv "$SCRATCH/merged.json" "$out"

  local summary
  summary="$(jq -r '
    [.journeys[] | select(.retried)] as $r
    | "**After the retry — \(.status):** " + ($r | map("`\(.id | split("/") | last)` \(if .status == "flaky" then "passed on the retry (flaky: a candidate for the quarantine)" else "failed again" end)") | join(", "))
  ' "$out")"
  printf '%s\n' "$summary"
  [ -z "${GITHUB_STEP_SUMMARY:-}" ] || printf '%s\n\n' "$summary" >>"$GITHUB_STEP_SUMMARY"
  jq -r '.journeys[] | select(.status == "flaky") | "::warning::\(.id) passed only on a retry: flaky, a candidate for the quarantine."' "$out"
  [ "$(jq -r .status "$out")" = passed ]
}

final=0
if [ "${#blocking[@]}" -gt 0 ]; then
  judge journeys 0 "${blocking[@]}" || final=1
fi
if [ "${#lenient[@]}" -gt 0 ]; then
  # Never blocking: verdict.sh already says what failed, in the summary.
  judge quarantine "$QUARANTINE_TIMEOUT" "${lenient[@]}" || true
fi

rm -rf "$REPORT"
mv "$COLLECTED" "$REPORT"
exit "$final"
