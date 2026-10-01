#!/usr/bin/env bash
# shellcheck disable=SC2015 # `cond && ok || bad`: ok and bad cannot fail
#
# Tests of infra/scripts/report-failed-deploy.sh against a fake curl (MAG-146,
# MAG-184).
#
# It runs at the worst moment, right after a failed deploy, and a silent failure
# means a broken release nobody hears about. What matters: the shipped tickets
# move to « Emergency » with `Top` and a comment; when they cannot carry the
# freeze, an Urgent Bug labelled `incident` and `Top` is opened; any failure
# that leaves nothing behind is loud.
#
# Usage: infra/scripts/tests/report-failed-deploy.test.sh

set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SCRIPT="$HERE/../report-failed-deploy.sh"
failures=0

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
mkdir -p "$work/bin"
ln -s "$HERE/fake-curl.sh" "$work/bin/curl"

ok()  { printf '  \033[32m✓\033[0m %s\n' "$1"; }
bad() { printf '  \033[31m✗\033[0m %s\n' "$1"; failures=$((failures + 1)); }

# fresh_world <lookup-json> [<create-json>]
fresh_world() {
  rm -rf "$work/api"
  mkdir -p "$work/api"
  : > "$work/api/requests"
  printf '%s' "$1" > "$work/api/lookup-response"
  printf '%s' "${2-$CREATED}" > "$work/api/create-response"
}

# known_ticket <key> <id> — the ticket exists in Linear
known_ticket() { printf '{"data":{"issue":{"id":"%s"}}}' "$2" > "$work/api/issue-$1"; }

# lookup [<labels-json>] [<states-json>]
lookup() {
  printf '{"data":{"teams":{"nodes":[{"id":"team-1"}]},"issueLabels":{"nodes":%s},"workflowStates":{"nodes":%s}}}' \
    "${1:-$ALL_LABELS}" "${2:-$EMERGENCY}"
}
ALL_LABELS='[{"id":"label-bug","name":"Bug"},{"id":"label-incident","name":"incident"},{"id":"label-top","name":"Top"}]'
EMERGENCY='[{"id":"state-emergency"}]'
CREATED='{"data":{"issueCreate":{"success":true,"issue":{"id":"incident-1","identifier":"MAG-999","url":"https://linear.app/meven/issue/MAG-999"}}}}'
TITLE='Production: the post-deploy smoke suite failed for 7cd6016'
BODY=$'Production was rolled back.\n\n- Commit: 7cd6016\n- Revisions restored:\n- php: revision 4 → back to 3'
CAUSE_LINE='la suite de smoke a échoué (commit 7cd6016)'
STATE_LINE='La prod a été remise sur la version précédente.'
RUN_LINK='https://github.com/x/y/actions/runs/1'

run_report() {
  OUTPUT="$(PATH="$work/bin:$PATH" FAKE_CURL_DIR="$work/api" LINEAR_API_KEY="${KEY-lin_api_secret}" \
    INCIDENT_TITLE="${TITLE_IN-$TITLE}" INCIDENT_BODY="$BODY" FAILURE_CAUSE="$CAUSE_LINE" PROD_STATE="$STATE_LINE" RUN_URL="$RUN_LINK" \
    DEPLOY_TICKETS="${TICKETS-}" ROLLBACK_FAILED="${ROLLBACK_FAILED_IN-false}" "$SCRIPT" 2>&1)"
  STATUS=$?
}

ops()          { jq -c --arg op "$1" 'select(.query | contains($op))' "$work/api/requests"; }
create_input() { ops issueCreate | jq -c .variables.input; }
updates()      { ops issueUpdate | jq -c .variables; }
comments()     { ops commentCreate | jq -c .variables.input; }
relations()    { ops issueRelationCreate | jq -c .variables.input; }

printf '\n\033[1mOne shipped ticket: « Emergency », Top and a comment, no incident\033[0m\n'
fresh_world "$(lookup)"
known_ticket MAG-12 issue-12
TICKETS='MAG-12' run_report
[ "$STATUS" -eq 0 ] && ok "exits 0" || bad "exit $STATUS — $OUTPUT"
[ "$(updates)" = '{"id":"issue-12","input":{"stateId":"state-emergency","addedLabelIds":["label-top"]}}' ] \
  && ok "moves it to « Emergency » with Top" || bad "wrong update: $(updates)"
ops workflowStates | jq -e '.variables.state == "Emergency"' >/dev/null && ok "looks the state up by its name" || bad "state lookup: $(ops workflowStates)"
[ "$(comments | jq -r .issueId)" = "issue-12" ] && ok "comments it" || bad "no comment: $(comments)"
comment="$(comments | jq -r .body)"
for part in "$CAUSE_LINE" "$STATE_LINE" "$RUN_LINK" '« Emergency »'; do
  [[ "$comment" == *"$part"* ]] && ok "comment carries: $part" || bad "comment lacks '$part': $comment"
done
[ -z "$(create_input)" ] && ok "opens no incident" || bad "opened an incident: $(create_input)"
grep -qx 'Authorization: lin_api_secret' "$work/api/auth" && ok "authenticates with the API key" || bad "Authorization header missing"

printf '\n\033[1mSeveral shipped tickets: each one moved and commented\033[0m\n'
fresh_world "$(lookup)"
known_ticket MAG-12 issue-12
known_ticket MAG-15 issue-15
TICKETS=$'MAG-12\nMAG-15' run_report
[ "$STATUS" -eq 0 ] && ok "exits 0" || bad "exit $STATUS — $OUTPUT"
[ "$(updates | jq -r .id | sort | paste -sd,)" = "issue-12,issue-15" ] && ok "moves both" || bad "updates: $(updates)"
[ "$(comments | jq -r .issueId | sort | paste -sd,)" = "issue-12,issue-15" ] && ok "comments both" || bad "comments: $(comments)"
[ -z "$(create_input)" ] && ok "opens no incident" || bad "opened an incident: $(create_input)"

printf '\n\033[1mNo shipped ticket: an Urgent Bug labelled incident and Top that says so\033[0m\n'
fresh_world "$(lookup)"
run_report
[ "$STATUS" -eq 0 ] && ok "exits 0" || bad "exit $STATUS — $OUTPUT"
[ "$(create_input | jq -r .teamId)" = "team-1" ] && ok "in the Maggie team" || bad "wrong team: $(create_input)"
[ "$(create_input | jq -r .priority)" = "1" ] && ok "Urgent" || bad "wrong priority: $(create_input)"
[ "$(create_input | jq -c '.labelIds | sort')" = '["label-bug","label-incident","label-top"]' ] \
  && ok "labelled Bug, incident and Top" || bad "wrong labels: $(create_input)"
[ "$(create_input | jq -r .title)" = "$TITLE" ] && ok "carries the title" || bad "wrong title: $(create_input)"
[[ "$(create_input | jq -r .description)" == "$BODY"$'\n\n'* ]] && ok "carries the body untouched" || bad "wrong body: $(create_input)"
create_input | jq -r .description | grep -q 'No ticket found for this deploy' && ok "says no ticket was found" || bad "silent: $(create_input)"
[ -z "$(updates)$(comments)" ] && ok "moves and comments nothing" || bad "touched a ticket: $(updates) $(comments)"
echo "$OUTPUT" | grep -q 'Incident ticket: MAG-999' && ok "prints the incident" || bad "incident not printed: $OUTPUT"

printf '\n\033[1mRollback failed: incident, and the tickets are still moved, commented and linked\033[0m\n'
fresh_world "$(lookup)"
known_ticket MAG-12 issue-12
TICKETS='MAG-12' ROLLBACK_FAILED_IN=true run_report
[ "$STATUS" -eq 0 ] && ok "exits 0" || bad "exit $STATUS — $OUTPUT"
create_input | jq -r .description | grep -q 'the rollback itself failed' && ok "opens an incident that says why" || bad "no incident: $(create_input)"
create_input | jq -r .description | grep -qx 'Tickets in this deploy: MAG-12' && ok "the incident names the ticket" || bad "not named: $(create_input)"
[ "$(updates | jq -r .input.stateId)" = "state-emergency" ] && ok "moves the ticket" || bad "not moved: $(updates)"
[[ "$(comments | jq -r .body)" == *'https://linear.app/meven/issue/MAG-999'* ]] && ok "the comment links the incident" || bad "no link: $(comments)"
[ "$(relations)" = '{"issueId":"incident-1","relatedIssueId":"issue-12","type":"related"}' ] \
  && ok "relates the ticket to the incident" || bad "wrong relation: $(relations)"

printf '\n\033[1m« Emergency » missing: nothing moved, Top and a comment, and an incident\033[0m\n'
fresh_world "$(lookup "$ALL_LABELS" '[]')"
known_ticket MAG-12 issue-12
TICKETS='MAG-12' run_report
[ "$STATUS" -eq 0 ] && ok "exits 0" || bad "exit $STATUS — $OUTPUT"
echo "$OUTPUT" | grep -q 'WARNING: Linear state « Emergency » not found' && ok "warns" || bad "no warning: $OUTPUT"
[ "$(updates)" = '{"id":"issue-12","input":{"addedLabelIds":["label-top"]}}' ] && ok "moves nothing, adds Top" || bad "wrong update: $(updates)"
[ "$(comments | jq -r .issueId)" = "issue-12" ] && ok "still comments" || bad "no comment: $(comments)"
create_input | jq -r .description | grep -q 'state is missing' && ok "opens an incident that says why" || bad "no incident: $(create_input)"

printf '\n\033[1mMissing Top label: tickets still moved, incident still opened\033[0m\n'
NO_TOP='[{"id":"label-bug","name":"Bug"},{"id":"label-incident","name":"incident"}]'
fresh_world "$(lookup "$NO_TOP")"
known_ticket MAG-12 issue-12
TICKETS='MAG-12' run_report
[ "$STATUS" -eq 0 ] && ok "exits 0" || bad "exit $STATUS — $OUTPUT"
[ "$(updates)" = '{"id":"issue-12","input":{"stateId":"state-emergency"}}' ] && ok "moved without Top" || bad "wrong update: $(updates)"
echo "$OUTPUT" | grep -q "WARNING: Linear label 'Top' not found" && ok "warns about Top" || bad "no warning: $OUTPUT"
fresh_world "$(lookup "$NO_TOP")"
run_report
[ "$STATUS" -eq 0 ] && ok "incident without Top: exits 0" || bad "exit $STATUS — $OUTPUT"
[ "$(create_input | jq -c '.labelIds | sort')" = '["label-bug","label-incident"]' ] && ok "created with the labels that exist" || bad "wrong labels: $(create_input)"

printf '\n\033[1mA ticket that cannot be found, moved or commented only warns\033[0m\n'
fresh_world "$(lookup)"
known_ticket MAG-15 issue-15
TICKETS='MAG-404 MAG-15 not-a-key *' run_report
[ "$STATUS" -eq 0 ] && ok "unknown ticket: exits 0" || bad "exit $STATUS — $OUTPUT"
echo "$OUTPUT" | grep -q 'WARNING: MAG-404 not found' && ok "warns about the unknown ticket" || bad "no warning: $OUTPUT"
echo "$OUTPUT" | grep -q "WARNING: 'not-a-key' is not a ticket key" && ok "drops what is not a key" || bad "no warning: $OUTPUT"
echo "$OUTPUT" | grep -q "WARNING: '\*' is not a ticket key" && ok "never globs the keys" || bad "globbed: $OUTPUT"
[ "$(updates | jq -r .id)" = "issue-15" ] && ok "still moves the others" || bad "updates: $(updates)"
[ -z "$(create_input)" ] && ok "no incident: one ticket carries the freeze" || bad "opened an incident: $(create_input)"

fresh_world "$(lookup)"
TICKETS='MAG-404' run_report
[ "$STATUS" -eq 0 ] && ok "only unknown tickets: exits 0" || bad "exit $STATUS — $OUTPUT"
[ -n "$(create_input)" ] && ok "opens an incident instead" || bad "nothing opened"

fresh_world "$(lookup)"
known_ticket MAG-12 issue-12
printf '%s' '{"errors":[{"message":"update refused"}]}' > "$work/api/update-response"
printf '%s' 'not json' > "$work/api/comment-response"
TICKETS='MAG-12' run_report
[ "$STATUS" -eq 0 ] && ok "move and comment refused: exits 0" || bad "exit $STATUS — $OUTPUT"
echo "$OUTPUT" | grep -q 'WARNING: could not move MAG-12' && ok "warns about the move" || bad "no warning: $OUTPUT"
echo "$OUTPUT" | grep -q 'WARNING: could not comment MAG-12' && ok "warns about the comment" || bad "no warning: $OUTPUT"
create_input | jq -r .description | grep -q 'could be moved' && ok "nothing carries the freeze: opens an incident" || bad "no incident: $(create_input)"

printf '\n\033[1mThe title is data, never parsed\033[0m\n'
fresh_world "$(lookup)"
TITLE_IN='Title with "quotes" and $(whoami)' run_report
[ "$STATUS" -eq 0 ] && ok "exits 0" || bad "exit $STATUS — $OUTPUT"
[ "$(create_input | jq -r .title)" = 'Title with "quotes" and $(whoami)' ] && ok "title kept verbatim" || bad "title altered: $(create_input)"

printf '\n\033[1mFailures are loud\033[0m\n'
fresh_world "$(lookup)"
KEY='' run_report
[ "$STATUS" -ne 0 ] && ok "no API key: fails" || bad "passed without an API key"
echo "$OUTPUT" | grep -q 'LINEAR_API_KEY' && ok "says which secret is missing" || bad "unclear message: $OUTPUT"
[ ! -s "$work/api/requests" ] && ok "calls nothing" || bad "called the API without a key"

fresh_world '{"data":{"teams":{"nodes":[]},"issueLabels":{"nodes":[]},"workflowStates":{"nodes":[]}}}'
run_report
[ "$STATUS" -ne 0 ] && ok "unknown team: fails" || bad "passed with no team"
[ -z "$(create_input)" ] && ok "creates nothing" || bad "created a ticket without a team"

fresh_world '{"errors":[{"message":"Authentication required"}]}'
run_report
[ "$STATUS" -ne 0 ] && ok "rejected key: fails" || bad "passed on a GraphQL error"
echo "$OUTPUT" | grep -q 'Authentication required' && ok "shows Linear's error" || bad "error hidden: $OUTPUT"

fresh_world "$(lookup)" '{"data":{"issueCreate":{"success":false,"issue":null}}}'
run_report
[ "$STATUS" -ne 0 ] && ok "incident refused: fails" || bad "passed when Linear refused the incident"

fresh_world "$(lookup)" 'not json'
run_report
[ "$STATUS" -ne 0 ] && ok "garbage answer: fails" || bad "passed on a non-JSON answer"

printf '\n'
[ "$failures" -eq 0 ] && echo "All report-failed-deploy tests passed." || echo "$failures failure(s)."
exit "$failures"
