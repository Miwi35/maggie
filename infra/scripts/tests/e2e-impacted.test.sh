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
for file in api/contract/openapi.json api/config/packages/security.yaml \
  docker-compose.e2e.yml Taskfile.yml e2e/Taskfile.yml agent/uv.lock api/composer.lock \
  .github/workflows/ci.yml e2e/clock.sh scripts/e2e/impacted.sh .docker/php/Dockerfile; do
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
# The app's API client plays the mobile critical core, not every flow (9 Oct.).
select_files mobile/app/src/main/java/com/maggie/app/data/api/MaggieApiService.kt
core_mobile="$(jq -r '[.mobile[] | select(test("01-login-chat"))] | length' <<<"$SEL")"
[ "$(count mobile)" -lt "$on_disk_mobile" ] && [ "$core_mobile" -eq 1 ] && [ "$(count web)" -eq 0 ] \
  && ok "MaggieApiService.kt: the mobile core, not every flow" || bad "MaggieApiService.kt: $(count web) web, $(count mobile) mobile"
# A client's own dependencies and login play that client only (8 Oct.).
for file in admin/package-lock.json e2e/web/package.json admin/src/auth/authProvider.ts admin/Taskfile.yml; do
  select_files "$file"
  [ "$(count web)" -eq "$on_disk_web" ] && [ "$(count mobile)" -eq 0 ] && ok "$file: every web journey, no flow" || bad "$file: $(count web) web, $(count mobile) mobile"
done
for file in mobile/gradle/libs.versions.toml mobile/app/build.gradle.kts mobile/gradle.properties mobile/Taskfile.yml \
  mobile/app/src/main/java/com/maggie/app/ui/screens/login/LoginScreen.kt; do
  select_files "$file"
  [ "$(count mobile)" -eq "$on_disk_mobile" ] && [ "$(count web)" -eq 0 ] && ok "$file: every flow, no web journey" || bad "$file: $(count web) web, $(count mobile) mobile"
done

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

# ---------------------------------------------------------------------------
# The line map (spec « Sélection e2e par couverture »): built from raw files
# here, read against the real e2e/impact-map.yml.
# ---------------------------------------------------------------------------
BUILD_MAP="$REPO/scripts/e2e/coverage/build-map.py"
API_SERVICE=mobile/app/src/main/java/com/maggie/app/data/api/MaggieApiService.kt
RECIPE_EDIT=admin/src/modules/cookbook/RecipeEdit.tsx
FINANCE_PHP=api/modules/finance/src/Service/BudgetCalculator.php
raw="$work/raw"
mkdir -p "$raw/mobile" "$raw/admin" "$raw/api" "$raw/agent"
# Two voice and chat flows run lines 100–110 of the app's API service, the finance
# flow 200–210; a journey since removed from disk ran line 100 too.
printf '{"journey":"e2e/mobile/flows/02-voice-overlay.yaml","files":{"%s":[100,101,102,103,104,105,106,107,108,109,110]}}' "$API_SERVICE" >"$raw/mobile/02-voice-overlay.json"
printf '{"journey":"e2e/mobile/flows/13-chat-opens-on-latest.yaml","files":{"%s":[110,109,108,107,106,105,104,103,102,101,100,100]}}' "$API_SERVICE" >"$raw/mobile/13-chat.json"
printf '{"journey":"e2e/mobile/flows/09-finance-banks.yaml","files":{"%s":[200,201,202,203,204,205,206,207,208,209,210]}}' "$API_SERVICE" >"$raw/mobile/09-finance.json"
printf '{"journey":"e2e/web/tests/gone.spec.ts","files":{"%s":[100]}}' "$API_SERVICE" >"$raw/mobile/gone.json"
# The recipe screen: lines 20–40 by the recipes journey only.
printf '{"journey":"e2e/web/tests/recipes.spec.ts","files":{"%s":[20,21,22,23,24,25,26,27,28,29,30,31,32,33,34,35,36,37,38,39,40]}}' "$RECIPE_EDIT" >"$raw/admin/recipes.json"
# An API line both platforms reach: the same journey id across two components merges.
printf '{"journey":"e2e/web/tests/finance-budget.spec.ts","files":{"%s":[10,11,12]}}' "$FINANCE_PHP" >"$raw/api/finance-budget.json"
printf '{"journey":"e2e/mobile/flows/09-finance-banks.yaml","files":{"./%s":[10,11,12]}}' "$FINANCE_PHP" >"$raw/api/09-finance.json"
printf '{"journey": 12}' >"$raw/agent/broken.json"
printf 'not json' >"$raw/agent/garbage.json"

printf '\n\033[1mThe map builder\033[0m\n'
"$BUILD_MAP" --raw "$raw" --out "$work/map.json" --commit abc1234def --now 2026-10-09T03:30:00Z >"$work/build.out" 2>"$work/build.err"
STATUS=$?
[ "$STATUS" -eq 0 ] && ok "builds ($(cat "$work/build.out"))" || bad "exit $STATUS: $(cat "$work/build.err")"
grep -q 'broken.json skipped' "$work/build.err" && grep -q 'garbage.json skipped' "$work/build.err" \
  && ok "a malformed raw file is skipped with a warning" || bad "warnings: $(cat "$work/build.err")"
jq -e '.version == 1 and .commit == "abc1234def" and .generatedAt == "2026-10-09T03:30:00Z"' "$work/map.json" >/dev/null \
  && ok "version, commit and date" || bad "header: $(jq -c 'del(.files)' "$work/map.json")"
jq -e '.journeys == ["e2e/mobile/flows/02-voice-overlay.yaml","e2e/mobile/flows/09-finance-banks.yaml","e2e/mobile/flows/13-chat-opens-on-latest.yaml","e2e/web/tests/finance-budget.spec.ts","e2e/web/tests/gone.spec.ts","e2e/web/tests/recipes.spec.ts"]' "$work/map.json" >/dev/null \
  && ok "the journeys, sorted, each once" || bad "journeys: $(jq -c .journeys "$work/map.json")"
# 02 = bit 0, 09 = bit 1, 13 = bit 2, finance-budget = bit 3, gone = bit 4, recipes = bit 5.
jq -e --arg f "$API_SERVICE" '.files[$f] == [[100,100,"15"],[101,110,"5"],[200,210,"2"]]' "$work/map.json" >/dev/null \
  && ok "consecutive lines with the same journeys are one range, a bitmask in hex" || bad "ranges: $(jq -c --arg f "$API_SERVICE" '.files[$f]' "$work/map.json")"
jq -e --arg f "$FINANCE_PHP" '.files[$f] == [[10,12,"a"]]' "$work/map.json" >/dev/null \
  && ok "one path from two components, ./ dropped, merged" || bad "finance: $(jq -c --arg f "$FINANCE_PHP" '.files[$f]' "$work/map.json")"
mkdir -p "$work/raw-empty"
"$BUILD_MAP" --raw "$work/raw-empty" --out "$work/none.json" >/dev/null 2>&1
STATUS=$?
[ "$STATUS" -eq 3 ] && [ ! -e "$work/none.json" ] && ok "no raw file: exit 3, no map" || bad "no raw file: exit $STATUS"

# select_diff <diff file> <file>… — the selection with the line map, in $SEL.
NOW=$(jq -n '"2026-10-10T12:00:00Z" | fromdateiso8601')
select_diff() {
  local diff="$1"; shift
  SEL="$(printf '%s\n' "$@" | E2E_COVERAGE_NOW="$NOW" "$IMPACTED" select --coverage-map "$work/map.json" --diff "$diff" 2>"$work/stderr")"
  STATUS=$?
}
hunk() { # hunk <path> <@@ header>… — a git diff -U0 of one file
  local path="$1"; shift
  printf 'diff --git a/%s b/%s\nindex 1111111..2222222 100644\n--- a/%s\n+++ b/%s\n' "$path" "$path" "$path" "$path"
  for h in "$@"; do printf '%s\n-old\n+new\n' "$h"; done
}

printf '\n\033[1mA line two flows ran plays those two and the core, even in a transversal file\033[0m\n'
hunk "$API_SERVICE" '@@ -105 +105 @@' >"$work/d1"
select_diff "$work/d1" "$API_SERVICE"
[ "$STATUS" -eq 0 ] && ok "exits 0" || bad "exit $STATUS: $(cat "$work/stderr")"
[ "$(names mobile)" = "01-login-chat.yaml 02-voice-overlay.yaml 13-chat-opens-on-latest.yaml" ] \
  && ok "02-voice-overlay, 13-chat and the core 01" || bad "flows: $(names mobile)"
[ "$(count web)" -eq 0 ] && ok "no web journey (the journey gone from disk is dropped)" || bad "web: $(names web)"
jq -e '.files[0].why == "coverage" and .files[0].lines == [[105,105]] and (.full.mobile | not)' >/dev/null <<<"$SEL" \
  && ok "why: coverage, with the lines" || bad "files: $(jq -c .files <<<"$SEL")"
jq -e '.coverage == {used: true, commit: "abc1234def", generatedAt: "2026-10-09T03:30:00Z"}' >/dev/null <<<"$SEL" \
  && ok "says which map it used" || bad "coverage: $(jq -c .coverage <<<"$SEL")"

printf '\n\033[1mA deleted range and a pure addition are read on the old side\033[0m\n'
hunk "$API_SERVICE" '@@ -199,3 +198,0 @@' >"$work/d2"
select_diff "$work/d2" "$API_SERVICE"
[ "$(names mobile)" = "01-login-chat.yaml 09-finance-banks.yaml" ] && ok "lines 199–201 deleted: the finance flow" || bad "flows: $(names mobile)"
hunk "$API_SERVICE" '@@ -110,0 +111,4 @@' >"$work/d3"
select_diff "$work/d3" "$API_SERVICE"
[ "$(names mobile)" = "01-login-chat.yaml 02-voice-overlay.yaml 13-chat-opens-on-latest.yaml" ] \
  && jq -e '.files[0].lines == [[110,111]]' >/dev/null <<<"$SEL" && ok "lines added after 110: the flows of 110–111" || bad "flows: $(names mobile) $(jq -c .files <<<"$SEL")"
# A deleted line that starts with `-- ` is a line, not a header.
printf 'diff --git a/%s b/%s\n--- a/%s\n+++ b/%s\n@@ -205 +205 @@\n--- old comment\n+++ new comment\n' \
  "$API_SERVICE" "$API_SERVICE" "$API_SERVICE" "$API_SERVICE" >"$work/d4"
select_diff "$work/d4" "$API_SERVICE"
[ "$(names mobile)" = "01-login-chat.yaml 09-finance-banks.yaml" ] && ok "a removed '-- ' line" || bad "flows: $(names mobile)"

printf '\n\033[1mAn API line both platforms ran plays both, each with its core\033[0m\n'
hunk "$FINANCE_PHP" '@@ -11 +11 @@' >"$work/d5"
select_diff "$work/d5" "$FINANCE_PHP"
[ "$(names web)" = "auth.spec.ts chat.spec.ts finance-budget.spec.ts smoke.spec.ts" ] && ok "web: finance-budget + core" || bad "web: $(names web)"
[ "$(names mobile)" = "01-login-chat.yaml 09-finance-banks.yaml" ] && ok "mobile: 09 + core" || bad "mobile: $(names mobile)"

printf '\n\033[1mA line no journey ran falls back to its zone\033[0m\n'
select_files "$RECIPE_EDIT"
zone="$(jq -c '{web, mobile}' <<<"$SEL")"
hunk "$RECIPE_EDIT" '@@ -100 +100 @@' >"$work/d6"
select_diff "$work/d6" "$RECIPE_EDIT"
[ "$(jq -c '{web, mobile}' <<<"$SEL")" = "$zone" ] && jq -e '.files[0].why == "journeys" and .files[0].lines == null' >/dev/null <<<"$SEL" \
  && ok "the cookbook zone, as without the map" || bad "selection: $(jq -c '{web, files}' <<<"$SEL")"
hunk "$RECIPE_EDIT" '@@ -30 +30 @@' '@@ -100 +100 @@' >"$work/d7"
select_diff "$work/d7" "$RECIPE_EDIT"
[ "$(jq -c '{web, mobile}' <<<"$SEL")" = "$zone" ] && jq -e '.files[0].lines == [[30,30]] and .files[0].uncovered == [[100,100]] and .files[0].line_journeys == ["e2e/web/tests/recipes.spec.ts"]' >/dev/null <<<"$SEL" \
  && ok "one hunk covered, one not: the zone, the covered lines reported" || bad "selection: $(jq -c '.files' <<<"$SEL")"
hunk "$RECIPE_EDIT" '@@ -30 +30 @@' >"$work/d8"
select_diff "$work/d8" "$RECIPE_EDIT"
[ "$(names web)" = "auth.spec.ts chat.spec.ts recipes.spec.ts smoke.spec.ts" ] && ok "a covered line alone: recipes + core" || bad "web: $(names web)"

printf '\n\033[1mWhat the map cannot see keeps today'"'"'s rules\033[0m\n'
MIGRATION=api/migrations/Version20261008210000.php
hunk "$MIGRATION" '@@ -5 +5 @@' >"$work/d9"
select_diff "$work/d9" "$MIGRATION"
[ "$(count web)" -eq "$on_disk_web" ] && [ "$(count mobile)" -eq "$on_disk_mobile" ] && jq -e '.files[0].why == "transversal"' >/dev/null <<<"$SEL" \
  && ok "a migration: everything" || bad "migration: $(count web) web, $(count mobile) mobile"
NEWFILE=admin/src/modules/cookbook/NewThing.tsx
printf 'diff --git a/%s b/%s\nnew file mode 100644\n--- /dev/null\n+++ b/%s\n@@ -0,0 +1,3 @@\n+a\n+b\n+c\n' "$NEWFILE" "$NEWFILE" "$NEWFILE" >"$work/d10"
select_diff "$work/d10" "$NEWFILE"
[ "$(jq -c '{web, mobile}' <<<"$SEL")" = "$zone" ] && ok "a new file: its zone" || bad "new file: $(names web)"
select_diff "$work/d1" admin/src/modules/cookbook/RecipeEdit.test.tsx
[ "$(count web)" -eq 0 ] && [ "$(count mobile)" -eq 0 ] && ok "an ignored file still plays nothing" || bad "ignored: $(names web)"

printf '\n\033[1mNo map, or a stale one: exactly today'"'"'s selection\033[0m\n'
FILES_MIX=("$API_SERVICE" "$RECIPE_EDIT" "$MIGRATION")
cat "$work/d1" "$work/d8" "$work/d9" >"$work/dmix"
today="$(printf '%s\n' "${FILES_MIX[@]}" | "$IMPACTED" select 2>/dev/null | jq -c 'del(.coverage)')"
SEL="$(printf '%s\n' "${FILES_MIX[@]}" | "$IMPACTED" select --coverage-map "$work/missing.json" --diff "$work/dmix" 2>"$work/stderr")"
[ "$(jq -c 'del(.coverage)' <<<"$SEL")" = "$today" ] && grep -q 'selected by zones' "$work/stderr" \
  && ok "a map that is not there: today's selection, a notice" || bad "missing map differs"
SEL="$(printf '%s\n' "${FILES_MIX[@]}" | E2E_COVERAGE_NOW="$(jq -n '"2026-10-12T04:00:00Z" | fromdateiso8601')" "$IMPACTED" select --coverage-map "$work/map.json" --diff "$work/dmix" 2>/dev/null)"
[ "$(jq -c 'del(.coverage)' <<<"$SEL")" = "$today" ] && jq -e '.coverage.used == false and (.coverage.reason | test("older than 3 days"))' >/dev/null <<<"$SEL" \
  && ok "a map of more than three days: ignored" || bad "stale map: $(jq -c .coverage <<<"$SEL")"
SEL="$(printf '%s\n' "${FILES_MIX[@]}" | E2E_COVERAGE_NOW="$NOW" "$IMPACTED" select --coverage-map "$work/map.json" 2>/dev/null)"
[ "$(jq -c 'del(.coverage)' <<<"$SEL")" = "$today" ] && ok "a map without a diff: ignored" || bad "no diff: differs"
echo '{"version": 2}' >"$work/v2.json"
SEL="$(printf '%s\n' "${FILES_MIX[@]}" | "$IMPACTED" select --coverage-map "$work/v2.json" --diff "$work/dmix" 2>/dev/null)"
[ "$(jq -c 'del(.coverage)' <<<"$SEL")" = "$today" ] && ok "a map of another version: ignored" || bad "v2 map: differs"
SEL="$(E2E_COVERAGE_NOW="$NOW" "$IMPACTED" select --all --coverage-map "$work/map.json" </dev/null 2>/dev/null)"
[ "$(count web)" -eq "$on_disk_web" ] && [ "$(count mobile)" -eq "$on_disk_mobile" ] && ok "--all ignores the map" || bad "--all with a map: $(count web) web"

printf '\n\033[1mThe job summary says how the journeys were chosen\033[0m\n'
: >"$work/output"; : >"$work/summary"
printf '%s\n' "$API_SERVICE" | E2E_COVERAGE_NOW="$NOW" GITHUB_OUTPUT="$work/output" GITHUB_STEP_SUMMARY="$work/summary" \
  "$IMPACTED" select --coverage-map "$work/map.json" --diff "$work/d1" --github-output >/dev/null 2>&1
grep -q 'Sélection par couverture (carte du 2026-10-09, abc1234)' "$work/summary" && grep -q 'lines 105 → 02-voice-overlay.yaml, 13-chat-opens-on-latest.yaml' "$work/summary" \
  && ok "coverage: the map's date and commit, and the lines" || bad "summary: $(cat "$work/summary")"
grep -qx 'mobile=true' "$work/output" && grep -qx 'e2e=false' "$work/output" && ok "outputs: mobile only" || bad "outputs: $(cat "$work/output")"
: >"$work/summary"
printf '%s\n' "$API_SERVICE" | GITHUB_OUTPUT="$work/output" GITHUB_STEP_SUMMARY="$work/summary" "$IMPACTED" select --github-output >/dev/null
grep -q 'Carte par zones (no coverage map)' "$work/summary" && ok "zones: says so" || bad "summary: $(cat "$work/summary")"

printf '\n'
if [ "$failures" -gt 0 ]; then
  printf '\033[31m%d failure(s)\033[0m\n' "$failures"
  exit 1
fi
printf '\033[32mAll e2e selection tests passed.\033[0m\n'
