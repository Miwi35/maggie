#!/usr/bin/env bash
# shellcheck disable=SC2015 # `cond && ok || bad`: ok and bad cannot fail
#
# Tests of scripts/e2e/nightly-report.sh, the night's e2e summary (owner's
# decision, 8 Oct.): a red journey with its area and the last commit of that
# area, the flaky ones as candidates for the quarantine, a green night in one line.
#
# Usage: infra/scripts/tests/e2e-nightly-report.test.sh

set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/../../.." && pwd)"
REPORT="$REPO/scripts/e2e/nightly-report.sh"
failures=0

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

ok()  { printf '  \033[32m✓\033[0m %s\n' "$1"; }
bad() { printf '  \033[31m✗\033[0m %s\n' "$1"; failures=$((failures + 1)); }

# A repository of its own, with a map and a history per area.
root="$work/root"
mkdir -p "$root/e2e/web/tests" "$root/e2e/mobile/flows" "$root/api/modules/finance/src" "$root/admin/src/modules/calendar"
cat >"$root/e2e/impact-map.yml" <<'MAP'
areas:
  finance: [api/modules/finance/**, 'admin/src/modules/{finance,budget}/**']
  calendar: [admin/src/modules/calendar/**]
web:
  e2e/web/tests/smoke.spec.ts: {critical: true, areas: [calendar]}
  e2e/web/tests/finance-budget.spec.ts: {areas: [finance]}
mobile:
  e2e/mobile/flows/01-core.yaml: {critical: true, areas: [calendar]}
  e2e/mobile/flows/05-shaky.yaml: {areas: [calendar], quarantine: {since: 2026-10-08, reason: flaky}}
MAP
git -C "$root" init -q
git -C "$root" config user.email test@example.com
git -C "$root" config user.name Test
touch "$root/admin/src/modules/calendar/View.tsx"
git -C "$root" add -A && git -C "$root" commit -q -m "Show the week"
echo '<?php' >"$root/api/modules/finance/src/Budget.php"
git -C "$root" add -A && git -C "$root" commit -q -m "Count the envelopes twice"
echo 'x' >>"$root/admin/src/modules/calendar/View.tsx"
git -C "$root" add -A && git -C "$root" commit -q -m "Move the week by a day"
export E2E_ROOT="$root"

verdict() { # verdict <file> <label> <id:status[:q]>…
  local file="$1" label="$2" journeys='[]'
  shift 2
  for j in "$@"; do
    IFS=: read -r id status q <<<"$j"
    journeys="$(jq -c --arg id "$id" --arg s "$status" --arg q "${q:-}" '. + [{id: $id, status: $s, quarantined: ($q == "q")}]' <<<"$journeys")"
  done
  mkdir -p "$work/verdicts/$(dirname "$file")"
  jq -n --arg lbl "$label" --argjson js "$journeys" '{platform: "x", "label": $lbl, journeys: $js}' >"$work/verdicts/$file"
}

report() {
  OUT="$("$REPORT" "$work/verdicts" "$1" 2>&1)"
  STATUS=$?
}

printf '\n\033[1mA red night\033[0m\n'
rm -rf "$work/verdicts"
verdict a/web-1.json "web shard 1/2" e2e/web/tests/smoke.spec.ts:passed e2e/web/tests/finance-budget.spec.ts:failed
verdict b/mobile-phone.json "mobile phone 1/1 (journeys)" e2e/mobile/flows/01-core.yaml:flaky
verdict c/mobile-phone-q.json "mobile phone 1/1 (quarantine)" e2e/mobile/flows/05-shaky.yaml:failed:q
report failure
[ "$STATUS" -eq 1 ] && ok "exits 1" || bad "exit $STATUS"
grep -q 'Nightly e2e — RED' <<<"$OUT" && ok "says red" || bad "$OUT"
row="$(grep 'finance-budget.spec.ts' <<<"$OUT" | head -1)"
grep -q '| finance |' <<<"$row" && ok "the red journey's area" || bad "row: $row"
grep -q 'Count the envelopes twice' <<<"$row" && ok "the last commit that touched that area (not a later one elsewhere)" || bad "row: $row"
grep -q 'web shard 1/2: failed' <<<"$row" && ok "the lot it failed in" || bad "row: $row"
grep -A3 '### Flaky' <<<"$OUT" | grep -q '01-core.yaml' && ok "a journey green on its retry is flaky, a candidate for the quarantine" || bad "$OUT"
grep -A3 '### Still failing in quarantine' <<<"$OUT" | grep -q '05-shaky.yaml' && ok "a journey in quarantine that fails is listed apart" || bad "$OUT"
grep -A6 '### Failed' <<<"$OUT" | grep -q '05-shaky' && bad "the quarantined journey is among the red ones" || ok "…and is not red"

printf '\n\033[1mA journey red on one device, green on another\033[0m\n'
rm -rf "$work/verdicts"
verdict a/phone.json "mobile phone 1/1" e2e/mobile/flows/01-core.yaml:passed
verdict b/tablet.json "mobile tablet 1/1" e2e/mobile/flows/01-core.yaml:not-run
report failure
[ "$STATUS" -eq 1 ] && grep -q 'mobile tablet 1/1: not-run' <<<"$OUT" && ok "red, on the tablet" || bad "exit $STATUS — $OUT"
grep '01-core.yaml' <<<"$OUT" | grep -q 'Move the week by a day' && ok "the last commit of its area (calendar)" || bad "$OUT"

printf '\n\033[1mThe jobs failed and no journey says why\033[0m\n'
rm -rf "$work/verdicts"
verdict a/web-1.json "web shard 1/1" e2e/web/tests/smoke.spec.ts:passed
report failure
[ "$STATUS" -eq 1 ] && grep -q 'no journey failed' <<<"$OUT" && ok "red: the stack, the APK or a runner broke" || bad "exit $STATUS — $OUT"

printf '\n\033[1mA green night\033[0m\n'
rm -rf "$work/verdicts"
verdict a/web-1.json "web shard 1/1" e2e/web/tests/smoke.spec.ts:passed e2e/web/tests/finance-budget.spec.ts:passed
verdict b/q.json "mobile phone 1/1 (quarantine)" e2e/mobile/flows/05-shaky.yaml:passed:q
report success
[ "$STATUS" -eq 0 ] && ok "exits 0" || bad "exit $STATUS — $OUT"
grep -q 'Nightly e2e — green' <<<"$OUT" && ! grep -q '###' <<<"$OUT" && ok "the summary and nothing else" || bad "$OUT"
grep -q '3 journeys played' <<<"$OUT" && ok "counts what played" || bad "$OUT"

printf '\n'
if [ "$failures" -gt 0 ]; then
  printf '\033[31m%d failure(s)\033[0m\n' "$failures"
  exit 1
fi
printf '\033[32mAll nightly report tests passed.\033[0m\n'
