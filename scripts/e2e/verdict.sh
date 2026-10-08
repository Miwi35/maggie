#!/usr/bin/env bash
#
# The verdict of one lot of e2e journeys, quarantine applied (owner's decision,
# 8 Oct.). A journey in quarantine (e2e/impact-map.yml) is played like any other,
# but its failure only warns: the lot fails on the others alone.
#
# Usage:
#   verdict.sh web <playwright results.json> <exit status of the run>
#   verdict.sh mobile <maestro junit.xml> <exit status of the run> <flows/x.yaml>...
#
# A run that exited 0 passes, whatever its report says or fails to say. A run
# that did not is read journey by journey: it passes only when every journey that
# failed — or, on Maestro, never ran — is in quarantine. A failed run whose report
# is missing, or names no failure, fails: the stack, the seed or the runner broke
# outside any journey, and no quarantine covers that.
#
# Writes a table to $GITHUB_STEP_SUMMARY when set, `quarantine_failed=true|false`
# to $GITHUB_OUTPUT when set, and the verdict as JSON to $E2E_VERDICT_OUT when set
# — {platform, label, status, journeys: [{id, status, quarantined}]}, journey
# status being passed, failed, flaky (green on a retry) or not-run.

set -euo pipefail

# shellcheck source=scripts/e2e/lib.sh
. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

die() { echo "verdict.sh: $*" >&2; exit 2; }

platform="${1:-}"
report="${2:-}"
run_status="${3:-}"
[ "$platform" = web ] || [ "$platform" = mobile ] || die "usage: verdict.sh web|mobile <report> <exit status> [flows…]"
case "$run_status" in ''|*[!0-9]*) die "the exit status of the run is not a number: '$run_status'" ;; esac
shift 3
label="${E2E_VERDICT_LABEL:-$platform}"

if map="$(e2e_map_json 2>/dev/null)" && jq -e 'type == "object"' >/dev/null 2>&1 <<<"$map"; then
  quarantine="$(jq -c "$E2E_JQ_DEFS"'[journeys[] | select(.quarantine != null) | {key: .id, value: (.quarantine.reason // "")}] | from_entries' <<<"$map")"
else
  echo "::warning::e2e/impact-map.yml cannot be read: no journey is in quarantine for this verdict." >&2
  quarantine='{}'
fi

# What the report says, as [{id, status}]; `null` when there is no report to read.
journeys='null'
if [ "$platform" = web ]; then
  # `skipped` counts as passed: Playwright marks so a test it never started, which
  # is safe only while the config sets no `maxFailures` nor `globalTimeout` — with
  # either, a failure in quarantine could stop a run before the tests that block.
  if [ -s "$report" ] && jq -e . "$report" >/dev/null 2>&1; then
    journeys="$(jq -c '
      . as $report
      | [.. | objects | select(has("specs")) | .specs[] | {file, statuses: [.tests[].status]}]
      | group_by(.file)
      | map({id: ("e2e/web/tests/" + .[0].file),
             status: ([.[].statuses[]] as $s
               | if ($s | index("unexpected")) then "failed"
                 elif ($s | index("flaky")) then "flaky"
                 else "passed" end)})
      # An error outside any test (a spec that does not load) fails the run.
      + [($report.errors // [])[] | {id: ("e2e/web/tests/" + (.location.file // "?" | split("/") | last)), status: "failed"}]
    ' "$report")"
  fi
else
  if [ -s "$report" ] && command -v yq >/dev/null 2>&1 && parsed="$(yq -p=xml -o=json '.' "$report" 2>/dev/null)"; then
    names='{}'
    for flow in "$@"; do
      names="$(jq -c --arg name "$(e2e_flow_name "e2e/mobile/$flow")" --arg id "e2e/mobile/$flow" '. + {($name): $id}' <<<"$names")"
    done
    journeys="$(jq -c --argjson names "$names" '
      [.. | objects | select(has("testcase")) | .testcase | if type == "array" then .[] else . end]
      | map((.["+@name"] // "") as $name
        | {id: ($names[$name] // ("e2e/mobile/flows/" + $name + ".yaml")),
           status: (if has("failure") or has("error") or ((.["+@status"] // "") | test("ERROR|FAIL"; "i")) then "failed"
                    elif ((.["+@status"] // "") | test("CANCEL|SKIP"; "i")) then "not-run"
                    else "passed" end)})
    ' <<<"$parsed")"
    # A flow of the lot the report does not name never ran.
    for flow in "$@"; do
      id="e2e/mobile/$flow"
      if ! jq -e --arg id "$id" 'any(.[]; .id == $id)' >/dev/null <<<"$journeys"; then
        journeys="$(jq -c --arg id "$id" '. + [{id: $id, status: "not-run"}]' <<<"$journeys")"
      fi
    done
  fi
fi

verdict="$(jq -n --arg platform "$platform" --arg lbl "$label" --argjson run "$run_status" \
  --argjson journeys "$journeys" --argjson quarantine "$quarantine" '
  ($journeys // []) as $js
  | ($js | map(. as $j | $j + {quarantined: ($quarantine | has($j.id))})) as $js
  | [$js[] | select(.status == "failed" or .status == "not-run")] as $bad
  | {platform: $platform, "label": $lbl, run_status: $run, journeys: $js,
     reason: (if $run == 0 then "the run passed"
              elif $journeys == null then "the run failed and left no report to read"
              elif ($bad | length) == 0 then "the run failed outside any journey (stack, seed or runner)"
              elif all($bad[]; .quarantined) then "only journeys in quarantine failed"
              else "a journey failed" end)}
  | .status = (if $run == 0 or (($bad | length) > 0 and all($bad[]; .quarantined)) then "passed" else "failed" end)
  | .quarantine_failed = any($bad[]; .quarantined)
  | .quarantine_reasons = $quarantine')"

if [ -n "${E2E_VERDICT_OUT:-}" ]; then
  mkdir -p "$(dirname "$E2E_VERDICT_OUT")"
  jq 'del(.quarantine_reasons)' <<<"$verdict" >"$E2E_VERDICT_OUT"
fi
if [ -n "${GITHUB_OUTPUT:-}" ]; then
  echo "quarantine_failed=$(jq '.quarantine_failed' <<<"$verdict")" >>"$GITHUB_OUTPUT"
fi

summary="$(jq -r '
  def icon: {passed: "✅", failed: "❌", flaky: "⚠️", "not-run": "⏭️"}[.] // .;
  "### E2E \(.label) — \(if .status == "passed" then "passed" else "FAILED" end)",
  "",
  "\(.reason[0:1] | ascii_upcase)\(.reason[1:]).",
  "",
  ([.journeys[] | select(.status != "passed" or .quarantined)] as $notable
   | if ($notable | length) == 0 then (if (.journeys | length) > 0 then "All \(.journeys | length) journeys passed." else empty end)
     else
       "| Journey | Result | Quarantine |",
       "|---|---|---|",
       ($notable[] | "| `\(.id | split("/") | last)` | \(.status | icon) \(.status) | \(if .quarantined then "yes — failure does not block" else "" end) |")
     end),
  ([.journeys[] | select(.quarantined and (.status == "failed" or .status == "not-run"))] as $q
   | if ($q | length) > 0 then
       "",
       "> [!WARNING]",
       "> In quarantine, so not blocking: \($q | map("`\(.id | split("/") | last)`") | join(", ")). Reason in e2e/impact-map.yml."
     else empty end)
' <<<"$verdict")"
printf '%s\n' "$summary"
if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
  printf '%s\n\n' "$summary" >>"$GITHUB_STEP_SUMMARY"
fi
jq -r '.journeys[] | select(.quarantined and (.status == "failed" or .status == "not-run"))
  | "::warning::\(.id) failed but is in quarantine (e2e/impact-map.yml): not blocking."' <<<"$verdict"

[ "$(jq -r '.status' <<<"$verdict")" = passed ]
