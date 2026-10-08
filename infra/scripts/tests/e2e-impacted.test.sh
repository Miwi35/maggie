#!/usr/bin/env bash
# shellcheck disable=SC2015 # `cond && ok || bad`: ok and bad cannot fail
#
# Tests of scripts/e2e/impacted.sh, the choice of the e2e journeys a pull request
# plays (owner's decision, 8 Oct.), against the real e2e/impact-map.yml and, for
# what the real map must never contain, against small ones written here.
#
# Usage: infra/scripts/tests/e2e-impacted.test.sh

set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/../../.." && pwd)"
IMPACTED="$REPO/scripts/e2e/impacted.sh"
failures=0

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

ok()  { printf '  \033[32m✓\033[0m %s\n' "$1"; }
bad() { printf '  \033[31m✗\033[0m %s\n' "$1"; failures=$((failures + 1)); }

# select <file>… — the selection for these changed files, in $SEL.
select_files() {
  SEL="$(printf '%s\n' "$@" | "$IMPACTED" select 2>"$work/stderr")"
  STATUS=$?
}
names() { jq -r --arg p "$1" '.[$p][] | split("/") | last' <<<"$SEL" | sort | tr '\n' ' ' | sed 's/ $//'; }
count() { jq --arg p "$1" '.[$p] | length' <<<"$SEL"; }

on_disk_web="$(find "$REPO/e2e/web/tests" -maxdepth 1 -name '*.spec.ts' | wc -l)"
on_disk_mobile="$(find "$REPO/e2e/mobile/flows" -maxdepth 1 -name '*.yaml' | wc -l)"
CORE_WEB='auth.spec.ts chat.spec.ts smoke.spec.ts'

printf '\n\033[1mThe real map is sound\033[0m\n'
out="$("$IMPACTED" check 2>&1)"
[ $? -eq 0 ] && ok "check passes ($out)" || bad "check fails on the real map: $out"

printf '\n\033[1mAn admin cookbook screen plays the recipe journeys and the core, no Maestro\033[0m\n'
select_files admin/src/modules/cookbook/RecipeEdit.tsx
[ "$STATUS" -eq 0 ] && ok "exits 0" || bad "exit $STATUS: $(cat "$work/stderr")"
web="$(names web)"
for spec in recipes.spec.ts recipes-ciqual.spec.ts meals-move.spec.ts $CORE_WEB; do
  [[ " $web " == *" $spec "* ]] && ok "plays $spec" || bad "does not play $spec: $web"
done
grep -qE 'finance-|agenda-|grocery-list' <<<"$web" && bad "plays journeys of other modules: $web" || ok "no finance, agenda or grocery-list journey"
[ "$(count mobile)" -eq 0 ] && ok "no Maestro flow" || bad "Maestro flows: $(names mobile)"
[ "$(jq '.mobile_lots | length' <<<"$SEL")" -eq 0 ] && ok "no Maestro lot" || bad "Maestro lots: $(jq -c .mobile_lots <<<"$SEL")"
[ "$(jq -c .web_lots <<<"$SEL")" = '[1,2]' ] && ok "two web lots for $(count web) spec files" || bad "web lots $(jq -c .web_lots <<<"$SEL") for $(count web) files"
[[ "$(jq -r .web_specs <<<"$SEL")" == *"tests/recipes.spec.ts"* ]] && ok "web_specs are relative to e2e/web" || bad "web_specs: $(jq -r .web_specs <<<"$SEL")"

printf '\n\033[1mThe core module plays everything\033[0m\n'
select_files api/modules/core/src/Security/UserProvider.php
[ "$(count web)" -eq "$on_disk_web" ] && ok "all $on_disk_web web journeys" || bad "$(count web) of $on_disk_web web journeys"
[ "$(count mobile)" -eq "$on_disk_mobile" ] && ok "all $on_disk_mobile mobile flows" || bad "$(count mobile) of $on_disk_mobile flows"
[ "$(jq -c .web_lots <<<"$SEL")" = '[1,2,3,4]' ] && ok "four web lots" || bad "web lots $(jq -c .web_lots <<<"$SEL")"
[ "$(jq '.mobile_lots | length' <<<"$SEL")" -eq "$(grep -cE '^[0-9]+:' "$REPO/e2e/mobile/shards.txt")" ] \
  && ok "one mobile lot per shard of shards.txt" || bad "mobile lots $(jq -c .mobile_lots <<<"$SEL")"
jq -e '.full.web and .full.mobile' >/dev/null <<<"$SEL" && ok "says it is the full suite" || bad "full: $(jq -c .full <<<"$SEL")"

printf '\n\033[1mThe safety rule: transversal changes play everything\033[0m\n'
for file in api/contract/openapi.json api/config/packages/security.yaml admin/src/auth/authProvider.ts \
  docker-compose.e2e.yml Taskfile.yml e2e/Taskfile.yml admin/package-lock.json agent/uv.lock api/composer.lock \
  mobile/gradle/libs.versions.toml .github/workflows/ci.yml e2e/clock.sh scripts/e2e/impacted.sh .docker/php/Dockerfile; do
  select_files "$file"
  [ "$(count web)" -eq "$on_disk_web" ] && [ "$(count mobile)" -eq "$on_disk_mobile" ] \
    && ok "$file" || bad "$file: $(count web) web, $(count mobile) mobile"
done

printf '\n\033[1mA file of one platform only plays that platform\033[0m\n'
select_files e2e/web/fixtures/session.ts
[ "$(count web)" -eq "$on_disk_web" ] && [ "$(count mobile)" -eq 0 ] && ok "a shared web fixture: every web journey, no flow" || bad "web fixture: $(count web) web, $(count mobile) mobile"
select_files e2e/mobile/subflows/sign-in.yaml
[ "$(count mobile)" -eq "$on_disk_mobile" ] && [ "$(count web)" -eq 0 ] && ok "the shared sign-in subflow: every flow, no web journey" || bad "sign-in subflow: $(count web) web, $(count mobile) mobile"
select_files admin/src/App.tsx
[ "$(count web)" -eq "$on_disk_web" ] && [ "$(count mobile)" -eq 0 ] && ok "the admin shell: every web journey" || bad "App.tsx: $(count web) web, $(count mobile) mobile"

printf '\n\033[1mA mobile finance screen plays the finance flows and the core, quarantine last\033[0m\n'
select_files mobile/app/src/main/java/com/maggie/app/ui/screens/finance/BudgetScreen.kt
[ "$(names mobile)" = "01-login-chat.yaml 05-deep-links.yaml 09-finance-banks.yaml 12-finance-rente.yaml" ] \
  && ok "01, 05, 09 and 12-finance-rente" || bad "flows: $(names mobile)"
[ "$(count web)" -eq 0 ] && ok "no web journey" || bad "web: $(names web)"
lot_of_05="$(jq -r '.mobile_lots[] | select(.flows | contains("05-deep-links")) | .flows' <<<"$SEL")"
[[ "$lot_of_05" == *"05-deep-links.yaml" ]] && ok "05-deep-links, in quarantine, ends its lot ($lot_of_05)" || bad "lot of 05: $lot_of_05"
[ "$(jq -c '[.mobile_lots[].index]' <<<"$SEL")" = '[1,2]' ] && ok "the lots are numbered again from 1" || bad "lots $(jq -c .mobile_lots <<<"$SEL")"
jq -e '.quarantined == ["e2e/mobile/flows/05-deep-links.yaml"]' >/dev/null <<<"$SEL" && ok "reports 05 in quarantine" || bad "quarantined: $(jq -c .quarantined <<<"$SEL")"

printf '\n\033[1mThe API of a module plays its journeys on both platforms\033[0m\n'
select_files api/modules/finance/src/Entity/Budget.php
grep -q 'finance-budget.spec.ts' <<<"$(names web)" && grep -q '09-finance-banks.yaml' <<<"$(names mobile)" \
  && ok "finance-budget.spec.ts and 09-finance-banks" || bad "web: $(names web) — mobile: $(names mobile)"
grep -q 'agenda-' <<<"$(names web)" && bad "plays agenda journeys" || ok "no agenda journey"

printf '\n\033[1mA journey edited alone plays itself and the core\033[0m\n'
select_files e2e/web/tests/finance-budget.spec.ts
[ "$(names web)" = "auth.spec.ts chat.spec.ts finance-budget.spec.ts smoke.spec.ts" ] && ok "finance-budget + core" || bad "web: $(names web)"
[ "$(count mobile)" -eq 0 ] && ok "no flow" || bad "mobile: $(names mobile)"

printf '\n\033[1mWhat plays nothing\033[0m\n'
for file in api/modules/finance/README.md e2e/impact-map.yml infra/k8s/configmap.yaml agent-os/standards/index.yml \
  admin/src/modules/cookbook/RecipeEdit.test.tsx api/modules/finance/tests/Mcp/BudgetToolsTest.php mobile/app/src/test/java/X.kt; do
  select_files "$file"
  [ "$(count web)" -eq 0 ] && [ "$(count mobile)" -eq 0 ] && ok "$file" || bad "$file: $(names web) / $(names mobile)"
done

printf '\n\033[1mA file the map does not know plays its platform\033[0m\n'
select_files api/modules/newmodule/src/Thing.php
[ "$(count web)" -eq "$on_disk_web" ] && [ "$(count mobile)" -eq "$on_disk_mobile" ] && ok "a new API module: everything" || bad "new module: $(count web) web, $(count mobile) mobile"
select_files admin/src/modules/newmodule/Thing.tsx
[ "$(count web)" -eq "$on_disk_web" ] && [ "$(count mobile)" -eq 0 ] && ok "a new admin module: every web journey" || bad "new admin module: $(count web) web, $(count mobile) mobile"

printf '\n\033[1mA file of one platform that only the other platform maps is unknown to it\033[0m\n'
M=mobile/app/src/main/java/com/maggie/app
select_files "$M/ui/theme/Color.kt"
[ "$(count mobile)" -eq "$on_disk_mobile" ] && [ "$(count web)" -eq 0 ] && ok "the app's theme: every flow" || bad "theme: $(count web) web, $(count mobile) mobile"
select_files "$M/ui/screens/settings/SettingsScreen.kt"
[ "$(count mobile)" -eq "$on_disk_mobile" ] && ok "an Android screen no flow maps (settings): every flow, never nothing" || bad "settings screen: $(count web) web, $(count mobile) mobile"
select_files admin/src/hooks/useVoiceRecorder.ts
grep -q 'chat.spec.ts' <<<"$(names web)" && grep -q 'chat-client-leaves.spec.ts' <<<"$(names web)" && [ "$(count mobile)" -eq 0 ] \
  && ok "the admin's dictation: the chat journeys" || bad "useVoiceRecorder: $(names web) / $(names mobile)"

printf '\n\033[1mA lot of flows in quarantine alone joins another lot\033[0m\n'
select_files agent/app/llm/streaming.py
lots="$(jq -c '.mobile_lots' <<<"$SEL")"
jq -e 'all(.[]; .flows | split(" ") | any(. != "flows/02-voice-overlay.yaml" and . != "flows/05-deep-links.yaml" and . != "flows/11-calendar-all-day-series.yaml"))' >/dev/null <<<"$lots" \
  && ok "every lot holds a flow that can block ($lots)" || bad "a quarantine-only lot: $lots"
grep -q '02-voice-overlay' <<<"$lots" && ok "02-voice-overlay still plays" || bad "02 dropped: $lots"

printf '\n\033[1mThe night plays everything\033[0m\n'
SEL="$("$IMPACTED" select --all </dev/null)"
[ "$(count web)" -eq "$on_disk_web" ] && [ "$(count mobile)" -eq "$on_disk_mobile" ] && ok "--all" || bad "--all: $(count web) web, $(count mobile) mobile"
[ "$(jq '.quarantined | length' <<<"$SEL")" -ge 1 ] && ok "quarantine included ($(jq -r '.quarantined | map(split("/") | last) | join(", ")' <<<"$SEL"))" || bad "no journey in quarantine on --all"

printf '\n\033[1mThe job outputs\033[0m\n'
: >"$work/output"
echo admin/src/modules/cookbook/RecipeEdit.tsx | GITHUB_OUTPUT="$work/output" GITHUB_STEP_SUMMARY="$work/summary" "$IMPACTED" select --github-output >/dev/null
grep -qx 'e2e=true' "$work/output" && grep -qx 'mobile=false' "$work/output" && grep -qx 'web_lots=\[1,2\]' "$work/output" \
  && grep -qx 'mobile_lots=\[\]' "$work/output" && grep -qx 'web_lot_count=2' "$work/output" && grep -qx 'mobile_lot_count=0' "$work/output" \
  && ok "e2e, mobile, lots and counts" || bad "outputs: $(cat "$work/output")"
grep -q 'recipes.spec.ts' "$work/summary" && ok "the summary names the journeys" || bad "summary: $(cat "$work/summary")"

# ---------------------------------------------------------------------------
# Small maps, for what the real one must never hold.
# ---------------------------------------------------------------------------
fixture() {
  root="$work/root-$1"
  mkdir -p "$root/e2e/web/tests" "$root/e2e/mobile/flows"
  touch "$root/e2e/web/tests/smoke.spec.ts" "$root/e2e/web/tests/a.spec.ts"
  printf 'appId: x\nname: 01-core\n---\n' >"$root/e2e/mobile/flows/01-core.yaml"
  printf 'appId: x\nname: 02-b\n---\n' >"$root/e2e/mobile/flows/02-b.yaml"
  printf '1: 01-core 02-b\n' >"$root/e2e/mobile/shards.txt"
  cat >"$root/e2e/impact-map.yml"
}
check_fixture() {
  OUT="$(E2E_ROOT="$root" "$IMPACTED" check 2>&1)"
  STATUS=$?
}
GOOD_MAP='areas:
  a: [src/a/**]
web:
  e2e/web/tests/smoke.spec.ts: {critical: true, areas: [a]}
  e2e/web/tests/a.spec.ts: {areas: [a]}
mobile:
  e2e/mobile/flows/01-core.yaml: {critical: true, areas: [a]}
  e2e/mobile/flows/02-b.yaml: {areas: [a], quarantine: {since: 2026-10-08, reason: flaky}}'

printf '\n\033[1mThe map check\033[0m\n'
fixture good <<<"$GOOD_MAP"
check_fixture
[ "$STATUS" -eq 0 ] && ok "a sound map passes" || bad "a sound map fails: $OUT"

fixture core-quarantined <<<"${GOOD_MAP/'{critical: true, areas: [a]}'/'{critical: true, areas: [a], quarantine: {since: 2026-10-08, reason: x}}'}"
check_fixture
[ "$STATUS" -ne 0 ] && grep -q 'critical and in quarantine' <<<"$OUT" && ok "the core cannot be in quarantine" || bad "core in quarantine: exit $STATUS — $OUT"

fixture forgotten <<<"$GOOD_MAP"
touch "$root/e2e/web/tests/new.spec.ts"
check_fixture
[ "$STATUS" -ne 0 ] && grep -q 'new.spec.ts is not in the map' <<<"$OUT" && ok "a journey missing from the map fails" || bad "forgotten journey: exit $STATUS — $OUT"
SEL="$(echo src/a/x.ts | E2E_ROOT="$root" "$IMPACTED" select 2>/dev/null)"
grep -q 'new.spec.ts' <<<"$(names web)" && ok "…and plays on every change until mapped" || bad "forgotten journey not played: $(names web)"

fixture gone <<<"$GOOD_MAP"
rm "$root/e2e/web/tests/a.spec.ts"
check_fixture
[ "$STATUS" -ne 0 ] && grep -q 'a.spec.ts is in the map but not on disk' <<<"$OUT" && ok "a journey of the map gone from disk fails" || bad "gone journey: exit $STATUS — $OUT"

fixture no-area <<<"${GOOD_MAP/'{areas: [a]}'/'{areas: [nope]}'}"
check_fixture
[ "$STATUS" -ne 0 ] && grep -q 'area nope' <<<"$OUT" && ok "an undefined area fails" || bad "undefined area: exit $STATUS — $OUT"

fixture no-reason <<<"${GOOD_MAP/'reason: flaky'/'reason: ""'}"
check_fixture
[ "$STATUS" -ne 0 ] && grep -q 'needs since' <<<"$OUT" && ok "a quarantine without a reason fails" || bad "no reason: exit $STATUS — $OUT"

fixture no-core <<<"${GOOD_MAP/'e2e/mobile/flows/01-core.yaml: {critical: true, areas: [a]}'/'e2e/mobile/flows/01-core.yaml: {areas: [a]}'}"
check_fixture
[ "$STATUS" -ne 0 ] && grep -q 'no critical mobile journey' <<<"$OUT" && ok "a platform without a core fails" || bad "no core: exit $STATUS — $OUT"

fixture broken <<<'web: [unclosed'
SEL="$(echo src/a/x.ts | E2E_ROOT="$root" "$IMPACTED" select 2>"$work/stderr")"
[ "$(count web)" -eq 2 ] && [ "$(count mobile)" -eq 2 ] && grep -q 'cannot be read' "$work/stderr" \
  && ok "a map that cannot be read plays every journey, with a warning" || bad "broken map: $(count web) web, $(count mobile) mobile — $(cat "$work/stderr")"

printf '\n'
if [ "$failures" -gt 0 ]; then
  printf '\033[31m%d failure(s)\033[0m\n' "$failures"
  exit 1
fi
printf '\033[32mAll e2e selection tests passed.\033[0m\n'
