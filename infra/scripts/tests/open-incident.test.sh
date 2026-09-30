#!/usr/bin/env bash
# shellcheck disable=SC2015 # `cond && ok || bad`: ok and bad cannot fail
#
# Tests of infra/scripts/open-incident.sh against a fake curl (MAG-146).
#
# It runs at the worst moment, right after a production rollback, and a silent
# failure means an incident nobody hears about. What matters: the ticket is
# Urgent, a Bug, labelled `incident`, in the right team, and any failure is loud.
#
# Usage: infra/scripts/tests/open-incident.test.sh

set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SCRIPT="$HERE/../open-incident.sh"
failures=0

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
mkdir -p "$work/bin"
ln -s "$HERE/fake-curl.sh" "$work/bin/curl"

ok()  { printf '  \033[32m✓\033[0m %s\n' "$1"; }
bad() { printf '  \033[31m✗\033[0m %s\n' "$1"; failures=$((failures + 1)); }

# fresh_world <lookup-json> <create-json>
fresh_world() {
  rm -rf "$work/api"
  mkdir -p "$work/api"
  : > "$work/api/requests"
  printf '%s' "$1" > "$work/api/lookup-response"
  printf '%s' "$2" > "$work/api/create-response"
}

LOOKUP='{"data":{"teams":{"nodes":[{"id":"team-1"}]},"issueLabels":{"nodes":[{"id":"label-bug","name":"Bug"},{"id":"label-incident","name":"incident"}]}}}'
CREATED='{"data":{"issueCreate":{"success":true,"issue":{"identifier":"MAG-999","url":"https://linear.app/meven/issue/MAG-999"}}}}'
TITLE='Production: the post-deploy smoke suite failed for 7cd6016'
BODY=$'Production was rolled back.\n\n- Commit: 7cd6016\n- Revisions restored:\n- php: revision 4 → back to 3'

run_incident() {
  OUTPUT="$(PATH="$work/bin:$PATH" FAKE_CURL_DIR="$work/api" LINEAR_API_KEY="${KEY-lin_api_secret}" \
    INCIDENT_TITLE="${TITLE_IN-$TITLE}" INCIDENT_BODY="${BODY_IN-$BODY}" "$SCRIPT" 2>&1)"
  STATUS=$?
}

create_input() { jq -c 'select(.query | contains("issueCreate")) | .variables.input' "$work/api/requests"; }

printf '\n\033[1mHappy path: an Urgent Bug labelled incident\033[0m\n'
fresh_world "$LOOKUP" "$CREATED"
run_incident
[ "$STATUS" -eq 0 ] && ok "exits 0" || bad "exit $STATUS — $OUTPUT"
[ "$(create_input | jq -r .teamId)" = "team-1" ] && ok "in the Maggie team" || bad "wrong team: $(create_input)"
[ "$(create_input | jq -r .priority)" = "1" ] && ok "Urgent" || bad "wrong priority: $(create_input)"
[ "$(create_input | jq -c '.labelIds | sort')" = '["label-bug","label-incident"]' ] \
  && ok "labelled Bug and incident" || bad "wrong labels: $(create_input)"
[ "$(create_input | jq -r .title)" = "$TITLE" ] && ok "carries the title" || bad "wrong title: $(create_input)"
[ "$(create_input | jq -r .description)" = "$BODY" ] && ok "carries the body untouched" || bad "wrong body: $(create_input)"
grep -qx 'Authorization: lin_api_secret' "$work/api/auth" && ok "authenticates with the API key" || bad "Authorization header missing"
echo "$OUTPUT" | grep -q 'MAG-999' && ok "prints the ticket" || bad "ticket not printed: $OUTPUT"

printf '\n\033[1mThe body is data, never parsed\033[0m\n'
fresh_world "$LOOKUP" "$CREATED"
TITLE_IN='Title with "quotes" and $(whoami)' BODY_IN='- a `backtick` and "quotes"'$'\n''second line' run_incident
[ "$STATUS" -eq 0 ] && ok "exits 0" || bad "exit $STATUS — $OUTPUT"
[ "$(create_input | jq -r .title)" = 'Title with "quotes" and $(whoami)' ] && ok "title kept verbatim" || bad "title altered: $(create_input)"

printf '\n\033[1mMissing label: a ticket without it beats none\033[0m\n'
fresh_world '{"data":{"teams":{"nodes":[{"id":"team-1"}]},"issueLabels":{"nodes":[{"id":"label-bug","name":"Bug"}]}}}' "$CREATED"
run_incident
[ "$STATUS" -eq 0 ] && ok "still exits 0" || bad "exit $STATUS — $OUTPUT"
[ "$(create_input | jq -c .labelIds)" = '["label-bug"]' ] && ok "created with the labels that exist" || bad "wrong labels: $(create_input)"
echo "$OUTPUT" | grep -q "label 'incident' not found" && ok "warns about the missing label" || bad "no warning: $OUTPUT"

printf '\n\033[1mFailures are loud\033[0m\n'
fresh_world "$LOOKUP" "$CREATED"
KEY='' run_incident
[ "$STATUS" -ne 0 ] && ok "no API key: fails" || bad "passed without an API key"
echo "$OUTPUT" | grep -q 'LINEAR_API_KEY' && ok "says which secret is missing" || bad "unclear message: $OUTPUT"
[ ! -s "$work/api/requests" ] && ok "calls nothing" || bad "called the API without a key"

fresh_world '{"data":{"teams":{"nodes":[]},"issueLabels":{"nodes":[]}}}' "$CREATED"
run_incident
[ "$STATUS" -ne 0 ] && ok "unknown team: fails" || bad "passed with no team"
[ -z "$(create_input)" ] && ok "creates nothing" || bad "created a ticket without a team"

fresh_world '{"errors":[{"message":"Authentication required"}]}' "$CREATED"
run_incident
[ "$STATUS" -ne 0 ] && ok "rejected key: fails" || bad "passed on a GraphQL error"
echo "$OUTPUT" | grep -q 'Authentication required' && ok "shows Linear's error" || bad "error hidden: $OUTPUT"

fresh_world "$LOOKUP" '{"data":{"issueCreate":{"success":false,"issue":null}}}'
run_incident
[ "$STATUS" -ne 0 ] && ok "creation refused: fails" || bad "passed when Linear refused the ticket"

fresh_world "$LOOKUP" 'not json'
run_incident
[ "$STATUS" -ne 0 ] && ok "garbage answer: fails" || bad "passed on a non-JSON answer"

printf '\n'
[ "$failures" -eq 0 ] && echo "All open-incident tests passed." || echo "$failures failure(s)."
exit "$failures"
