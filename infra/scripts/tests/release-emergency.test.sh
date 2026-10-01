#!/usr/bin/env bash
# shellcheck disable=SC2015 # `cond && ok || bad`: ok and bad cannot fail
#
# Tests of infra/scripts/release-emergency.sh against a fake curl (MAG-184).
#
# A ticket left in « Emergency » freezes every PR, and one taken out too early
# lifts the freeze before its fix is live. What matters: only tickets in
# « Emergency » move, a Task to Done, anything else to Recette.
#
# Usage: infra/scripts/tests/release-emergency.test.sh

set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SCRIPT="$HERE/../release-emergency.sh"
failures=0

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
mkdir -p "$work/bin"
ln -s "$HERE/fake-curl.sh" "$work/bin/curl"

ok()  { printf '  \033[32m✓\033[0m %s\n' "$1"; }
bad() { printf '  \033[31m✗\033[0m %s\n' "$1"; failures=$((failures + 1)); }

STATES='{"data":{"workflowStates":{"nodes":[{"id":"state-recette","name":"Recette"},{"id":"state-done","name":"Done"}]}}}'

fresh_world() {
  rm -rf "$work/api"
  mkdir -p "$work/api"
  : > "$work/api/requests"
  printf '%s' "${1:-$STATES}" > "$work/api/lookup-response"
}

# ticket <key> <id> <state> <label…>
ticket() {
  local key=$1 id=$2 state=$3
  shift 3
  jq -nc --arg id "$id" --arg state "$state" --args \
    '{data: {issue: {id: $id, state: {name: $state}, labels: {nodes: [$ARGS.positional[] | {name: .}]}}}}' "$@" \
    > "$work/api/issue-$key"
}

run_release() {
  OUTPUT="$(PATH="$work/bin:$PATH" FAKE_CURL_DIR="$work/api" LINEAR_API_KEY="${KEY-lin_api_secret}" \
    DEPLOY_TICKETS="${TICKETS-}" "$SCRIPT" 2>&1)"
  STATUS=$?
}

updates() { jq -c 'select(.query | contains("issueUpdate")) | .variables' "$work/api/requests"; }

printf '\n\033[1mA Bug or a Feature in « Emergency » goes to Recette, a Task to Done\033[0m\n'
fresh_world
ticket MAG-12 issue-12 Emergency Bug
ticket MAG-15 issue-15 Emergency Task area:infra-ci
ticket MAG-17 issue-17 Emergency Feature
TICKETS='MAG-12 MAG-15 MAG-17' run_release
[ "$STATUS" -eq 0 ] && ok "exits 0" || bad "exit $STATUS — $OUTPUT"
updates | grep -qx '{"id":"issue-12","input":{"stateId":"state-recette"}}' && ok "Bug → Recette" || bad "updates: $(updates)"
updates | grep -qx '{"id":"issue-15","input":{"stateId":"state-done"}}' && ok "Task → Done" || bad "updates: $(updates)"
updates | grep -qx '{"id":"issue-17","input":{"stateId":"state-recette"}}' && ok "Feature → Recette" || bad "updates: $(updates)"

printf '\n\033[1mA ticket not in « Emergency » is left alone\033[0m\n'
fresh_world
ticket MAG-12 issue-12 Recette Bug
ticket MAG-15 issue-15 "In Review" Task
TICKETS='MAG-12 MAG-15' run_release
[ "$STATUS" -eq 0 ] && ok "exits 0" || bad "exit $STATUS — $OUTPUT"
[ -z "$(updates)" ] && ok "moves nothing" || bad "moved: $(updates)"

printf '\n\033[1mNo ticket, an unknown one, or a refused move only warn\033[0m\n'
fresh_world
run_release
[ "$STATUS" -eq 0 ] && ok "no ticket: exits 0" || bad "exit $STATUS — $OUTPUT"
[ ! -s "$work/api/requests" ] && ok "calls nothing" || bad "called the API"

fresh_world
ticket MAG-12 issue-12 Emergency Bug
TICKETS='MAG-404 MAG-12' run_release
[ "$STATUS" -eq 0 ] && ok "unknown ticket: exits 0" || bad "exit $STATUS — $OUTPUT"
echo "$OUTPUT" | grep -q 'WARNING: MAG-404 not found' && ok "warns about it" || bad "no warning: $OUTPUT"
[ "$(updates | jq -r .id)" = "issue-12" ] && ok "still releases the others" || bad "updates: $(updates)"

fresh_world
ticket MAG-12 issue-12 Emergency Bug
printf '%s' '{"errors":[{"message":"refused"}]}' > "$work/api/update-response"
TICKETS='MAG-12' run_release
[ "$STATUS" -eq 0 ] && ok "refused move: exits 0" || bad "exit $STATUS — $OUTPUT"
echo "$OUTPUT" | grep -q 'WARNING: could not move MAG-12' && ok "warns about it" || bad "no warning: $OUTPUT"

fresh_world '{"data":{"workflowStates":{"nodes":[{"id":"state-recette","name":"Recette"}]}}}'
ticket MAG-15 issue-15 Emergency Task
TICKETS='MAG-15' run_release
[ "$STATUS" -eq 0 ] && ok "missing Done state: exits 0" || bad "exit $STATUS — $OUTPUT"
echo "$OUTPUT" | grep -q 'WARNING: state Done not found' && ok "warns about it" || bad "no warning: $OUTPUT"
[ -z "$(updates)" ] && ok "moves nothing" || bad "moved: $(updates)"

printf '\n\033[1mFailures are loud\033[0m\n'
fresh_world
KEY='' TICKETS='MAG-12' run_release
[ "$STATUS" -ne 0 ] && ok "no API key: fails" || bad "passed without an API key"
fresh_world '{"errors":[{"message":"Authentication required"}]}'
TICKETS='MAG-12' run_release
[ "$STATUS" -ne 0 ] && ok "rejected key: fails" || bad "passed on a GraphQL error"

printf '\n'
[ "$failures" -eq 0 ] && echo "All release-emergency tests passed." || echo "$failures failure(s)."
exit "$failures"
