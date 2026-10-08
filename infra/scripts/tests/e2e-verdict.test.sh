#!/usr/bin/env bash
# shellcheck disable=SC2015 # `cond && ok || bad`: ok and bad cannot fail
#
# Tests of scripts/e2e/verdict.sh and scripts/e2e/mobile-journeys.sh: a journey
# in quarantine (e2e/impact-map.yml) plays, but its failure does not fail its lot;
# any other failure does (owner's decision, 8 Oct.).
#
# Usage: infra/scripts/tests/e2e-verdict.test.sh

set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/../../.." && pwd)"
VERDICT="$REPO/scripts/e2e/verdict.sh"
JOURNEYS="$REPO/scripts/e2e/mobile-journeys.sh"
failures=0

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

ok()  { printf '  \033[32m✓\033[0m %s\n' "$1"; }
bad() { printf '  \033[31m✗\033[0m %s\n' "$1"; failures=$((failures + 1)); }

# A root of its own: a map with one web journey and one flow in quarantine.
root="$work/root"
mkdir -p "$root/e2e/web/tests" "$root/e2e/mobile/flows"
touch "$root/e2e/web/tests/smoke.spec.ts" "$root/e2e/web/tests/shaky.spec.ts" "$root/e2e/web/tests/solid.spec.ts"
for flow in 01-core 02-solid 05-shaky; do
  printf 'appId: com.maggie.app.e2e\nname: %s\n---\n- launchApp\n' "$flow" >"$root/e2e/mobile/flows/$flow.yaml"
done
cat >"$root/e2e/impact-map.yml" <<'MAP'
areas:
  a: [src/**]
web:
  e2e/web/tests/smoke.spec.ts: {critical: true, areas: [a]}
  e2e/web/tests/solid.spec.ts: {areas: [a]}
  e2e/web/tests/shaky.spec.ts: {areas: [a], quarantine: {since: 2026-10-08, reason: red then green}}
mobile:
  e2e/mobile/flows/01-core.yaml: {critical: true, areas: [a]}
  e2e/mobile/flows/02-solid.yaml: {areas: [a]}
  e2e/mobile/flows/05-shaky.yaml: {areas: [a], quarantine: {since: 2026-10-08, reason: red then green}}
MAP
export E2E_ROOT="$root"

# playwright <file:status>… — a Playwright JSON report, one spec per argument.
playwright() {
  local suites='[]' file status
  for pair in "$@"; do
    file="${pair%%:*}"
    status="${pair#*:}"
    suites="$(jq -c --arg f "$file" --arg s "$status" '. + [{title: $f, file: $f, specs: [],
      suites: [{title: "describe", file: $f, specs: [{title: "it", file: $f, ok: ($s != "unexpected"), tests: [{status: $s}]}]}]}]' <<<"$suites")"
  done
  jq -n --argjson suites "$suites" '{suites: $suites, errors: []}' >"$work/results.json"
}

# junit <name:status>… — a Maestro JUnit report.
junit() {
  {
    echo '<?xml version="1.0" encoding="UTF-8"?>'
    echo '<testsuites><testsuite name="Test Suite" tests="'$#'">'
    for pair in "$@"; do
      name="${pair%%:*}"
      status="${pair#*:}"
      if [ "$status" = SUCCESS ]; then
        echo "<testcase id=\"$name\" name=\"$name\" classname=\"$name\" time=\"10\" status=\"SUCCESS\"/>"
      else
        echo "<testcase id=\"$name\" name=\"$name\" classname=\"$name\" time=\"10\" status=\"ERROR\"><failure>Assertion is false: id: dashboard is visible</failure></testcase>"
      fi
    done
    echo '</testsuite></testsuites>'
  } >"$work/junit.xml"
}

verdict() {
  OUT="$(GITHUB_STEP_SUMMARY="$work/summary" GITHUB_OUTPUT="$work/output" E2E_VERDICT_OUT="$work/verdict.json" "$VERDICT" "$@" 2>&1)"
  STATUS=$?
}

printf '\n\033[1mPlaywright\033[0m\n'
playwright smoke.spec.ts:expected solid.spec.ts:expected shaky.spec.ts:unexpected
: >"$work/output"
verdict web "$work/results.json" 1
[ "$STATUS" -eq 0 ] && ok "a journey in quarantine that fails does not fail the lot" || bad "exit $STATUS: $OUT"
grep -q 'shaky.spec.ts' "$work/summary" && grep -q 'WARNING' "$work/summary" && ok "…and is a warning in the job summary" || bad "summary: $(cat "$work/summary")"
grep -qx 'quarantine_failed=true' "$work/output" && ok "…and says so to the job (quarantine_failed=true)" || bad "output: $(cat "$work/output")"
grep -q '::warning::e2e/web/tests/shaky.spec.ts' <<<"$OUT" && ok "…and as an annotation" || bad "no annotation: $OUT"
jq -e '.status == "passed" and (.journeys[] | select(.id == "e2e/web/tests/shaky.spec.ts") | .status == "failed" and .quarantined)' "$work/verdict.json" >/dev/null \
  && ok "…and in the verdict file" || bad "verdict file: $(cat "$work/verdict.json")"

playwright smoke.spec.ts:expected solid.spec.ts:unexpected shaky.spec.ts:unexpected
verdict web "$work/results.json" 1
[ "$STATUS" -ne 0 ] && ok "any other failure fails the lot" || bad "exit 0 with solid.spec.ts red: $OUT"

playwright smoke.spec.ts:expected solid.spec.ts:flaky
verdict web "$work/results.json" 0
[ "$STATUS" -eq 0 ] && jq -e '.journeys[] | select(.id == "e2e/web/tests/solid.spec.ts") | .status == "flaky"' "$work/verdict.json" >/dev/null \
  && ok "green on a retry passes, as flaky" || bad "flaky: exit $STATUS — $(cat "$work/verdict.json")"

rm -f "$work/results.json"
verdict web "$work/results.json" 1
[ "$STATUS" -ne 0 ] && ok "a failed run without a report fails" || bad "exit 0 without a report: $OUT"

playwright smoke.spec.ts:expected
verdict web "$work/results.json" 1
[ "$STATUS" -ne 0 ] && grep -q 'outside any journey' <<<"$OUT" && ok "a failed run whose report names no failure fails" || bad "exit $STATUS: $OUT"

jq -n '{suites: [], errors: [{message: "SyntaxError", location: {file: "/e2e/tests/solid.spec.ts"}}]}' >"$work/results.json"
verdict web "$work/results.json" 1
[ "$STATUS" -ne 0 ] && ok "a spec that does not load fails" || bad "exit 0 on a load error: $OUT"

verdict web "$work/missing.json" 0
[ "$STATUS" -eq 0 ] && ok "a run that passed passes, report or not" || bad "exit $STATUS on a green run: $OUT"

printf '\n\033[1mMaestro\033[0m\n'
junit 01-core:SUCCESS 02-solid:SUCCESS 05-shaky:ERROR
verdict mobile "$work/junit.xml" 1 flows/01-core.yaml flows/02-solid.yaml flows/05-shaky.yaml
[ "$STATUS" -eq 0 ] && ok "a flow in quarantine that fails does not fail the lot" || bad "exit $STATUS: $OUT"

junit 01-core:SUCCESS 02-solid:ERROR
verdict mobile "$work/junit.xml" 1 flows/01-core.yaml flows/02-solid.yaml
[ "$STATUS" -ne 0 ] && ok "any other failure fails the lot" || bad "exit 0 with 02-solid red: $OUT"

junit 01-core:ERROR
verdict mobile "$work/junit.xml" 1 flows/01-core.yaml flows/02-solid.yaml flows/05-shaky.yaml
[ "$STATUS" -ne 0 ] && jq -e '.journeys[] | select(.id == "e2e/mobile/flows/02-solid.yaml") | .status == "not-run"' "$work/verdict.json" >/dev/null \
  && ok "a flow the report does not name never ran, and that fails the lot" || bad "exit $STATUS — $(cat "$work/verdict.json")"

junit 01-core:SUCCESS 02-solid:SUCCESS
verdict mobile "$work/junit.xml" 1 flows/01-core.yaml flows/02-solid.yaml flows/05-shaky.yaml
[ "$STATUS" -eq 0 ] && ok "a flow in quarantine that never ran does not fail the lot" || bad "exit $STATUS: $OUT"

printf '\n\033[1mThe emulator script: the flows in quarantine in a run of their own\033[0m\n'
# A runner that plays the flows it is given: each flow named in $FAIL fails, each
# named in $HANG hangs. It writes the JUnit report run.sh would.
cat >"$work/fake-run" <<'FAKE'
#!/usr/bin/env bash
mkdir -p "$E2E_ROOT/e2e/mobile/report"
echo "$*" >>"$E2E_ROOT/runs.log"
cases='' status=0
for flow in "$@"; do
  name="$(basename "$flow" .yaml)"
  case " ${HANG:-} " in *" $name "*) sleep 30 ;; esac
  case " ${FAIL:-} " in
    *" $name "*) cases="$cases<testcase name=\"$name\" status=\"ERROR\"><failure>no</failure></testcase>"; status=1 ;;
    *) cases="$cases<testcase name=\"$name\" status=\"SUCCESS\"/>" ;;
  esac
done
echo "<testsuites><testsuite>$cases</testsuite></testsuites>" >"$E2E_ROOT/e2e/mobile/report/junit.xml"
exit "$status"
FAKE
chmod +x "$work/fake-run"

journeys() {
  rm -f "$root/runs.log"
  OUT="$(E2E_MOBILE_RUN="$work/fake-run" E2E_MOBILE_FLOWS="flows/01-core.yaml flows/02-solid.yaml flows/05-shaky.yaml" \
    E2E_QUARANTINE_TIMEOUT=2 E2E_VERDICT_DIR="$work/verdicts" "$JOURNEYS" 2>&1)"
  STATUS=$?
}

FAIL='05-shaky' journeys
[ "$STATUS" -eq 0 ] && ok "05-shaky red: the lot passes" || bad "exit $STATUS: $OUT"
[ "$(sed -n 1p "$root/runs.log")" = "flows/01-core.yaml flows/02-solid.yaml" ] && [ "$(sed -n 2p "$root/runs.log")" = "flows/05-shaky.yaml" ] \
  && ok "two runs: the blocking flows, then the one in quarantine" || bad "runs: $(cat "$root/runs.log")"
[ -f "$root/e2e/mobile/report/journeys/junit.xml" ] && [ -f "$root/e2e/mobile/report/quarantine/junit.xml" ] \
  && ok "both reports kept for the upload" || bad "report: $(find "$root/e2e/mobile/report" | tr '\n' ' ')"
[ "$(find "$work/verdicts" -name '*.json' | wc -l)" -eq 2 ] && ok "one verdict file per run" || bad "verdicts: $(ls "$work/verdicts")"

FAIL='02-solid' journeys
[ "$STATUS" -ne 0 ] && ok "02-solid red: the lot fails" || bad "exit 0 with 02-solid red: $OUT"

HANG='05-shaky' journeys
[ "$STATUS" -eq 0 ] && grep -q 'still running after 2s' <<<"$OUT" && ok "a flow in quarantine that hangs is stopped, and the lot passes" || bad "hang: exit $STATUS — $OUT"

printf '\n\033[1mThe real map\033[0m\n'
unset E2E_ROOT
junit 01-login-chat:SUCCESS 04-calendar-import:SUCCESS 12-recipe-realtime:SUCCESS 05-deep-links:ERROR
verdict mobile "$work/junit.xml" 1 flows/01-login-chat.yaml flows/04-calendar-import.yaml flows/12-recipe-realtime.yaml flows/05-deep-links.yaml
[ "$STATUS" -eq 0 ] && ok "05-deep-links is in quarantine" || bad "05-deep-links blocks: $OUT"
junit 01-login-chat:ERROR
verdict mobile "$work/junit.xml" 1 flows/01-login-chat.yaml
[ "$STATUS" -ne 0 ] && ok "01-login-chat, the core, blocks" || bad "01-login-chat does not block: $OUT"

printf '\n'
if [ "$failures" -gt 0 ]; then
  printf '\033[31m%d failure(s)\033[0m\n' "$failures"
  exit 1
fi
printf '\033[32mAll e2e verdict tests passed.\033[0m\n'
