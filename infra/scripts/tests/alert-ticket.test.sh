#!/usr/bin/env bash
# shellcheck disable=SC2015 # `cond && ok || bad`: ok and bad cannot fail
#
# Tests of infra/scripts/alert-ticket.sh against a fake curl (MAG-151).
#
# The nightly alert is the only way the owner hears about a red night, and GitHub
# issues are disabled: a first red night creates a Bug in Linear, the next ones
# comment that same ticket, and nothing fails silently.
#
# Usage: infra/scripts/tests/alert-ticket.test.sh

set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SCRIPT="$HERE/../alert-ticket.sh"
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

# lookup [<labels-json>] [<open-issues-json>]
lookup() {
  printf '{"data":{"teams":{"nodes":[{"id":"team-1"}]},"issueLabels":{"nodes":%s},"issues":{"nodes":%s}}}' \
    "${1:-$ALL_LABELS}" "${2:-[]}"
}
ALL_LABELS='[{"id":"label-bug","name":"Bug"},{"id":"label-nightly","name":"nightly-failure"}]'
OPEN_TICKET='[{"id":"issue-7","identifier":"MAG-777","url":"https://linear.app/meven/issue/MAG-777"}]'
CREATED='{"data":{"issueCreate":{"success":true,"issue":{"id":"alert-1","identifier":"MAG-999","url":"https://linear.app/meven/issue/MAG-999"}}}}'
TITLE='Nightly : la suite de nuit a échoué'
BODY=$'La suite de nuit a échoué.\n\n- Run : https://github.com/x/y/actions/runs/1\n- CI : failure, mobile : success'

run_alert() {
  OUTPUT="$(PATH="$work/bin:$PATH" FAKE_CURL_DIR="$work/api" LINEAR_API_KEY="${KEY-lin_api_secret}" \
    ALERT_LABEL="${LABEL_IN-nightly-failure}" ALERT_TITLE="${TITLE_IN-$TITLE}" ALERT_BODY="$BODY" "$SCRIPT" 2>&1)"
  STATUS=$?
}

ops()          { jq -c --arg op "$1" 'select(.query | contains($op))' "$work/api/requests"; }
create_input() { ops 'issueCreate' | jq -c .variables.input; }
label_input()  { ops 'issueLabelCreate' | jq -c .variables.input; }
comments()     { ops commentCreate | jq -c .variables.input; }

printf '\n\033[1mFirst red night: a Urgent Bug labelled nightly-failure, no comment\033[0m\n'
fresh_world "$(lookup)"
run_alert
[ "$STATUS" -eq 0 ] && ok "exits 0" || bad "exit $STATUS — $OUTPUT"
[ "$(create_input | jq -r .teamId)" = "team-1" ] && ok "in the Maggie team" || bad "wrong team: $(create_input)"
[ "$(create_input | jq -r .priority)" = "1" ] && ok "Urgent" || bad "wrong priority: $(create_input)"
[ "$(create_input | jq -c '.labelIds | sort')" = '["label-bug","label-nightly"]' ] && ok "labelled Bug and nightly-failure" || bad "wrong labels: $(create_input)"
[ "$(create_input | jq -r .title)" = "$TITLE" ] && ok "carries the title" || bad "wrong title: $(create_input)"
[ "$(create_input | jq -r .description)" = "$BODY" ] && ok "carries the body untouched" || bad "wrong body: $(create_input)"
[ -z "$(comments)" ] && ok "comments nothing" || bad "commented: $(comments)"
[ -z "$(label_input)" ] && ok "the label exists: creates none" || bad "created a label: $(label_input)"
echo "$OUTPUT" | grep -q 'Alert ticket: MAG-999 https://linear.app/meven/issue/MAG-999' && ok "prints the ticket" || bad "ticket not printed: $OUTPUT"
grep -qx 'Authorization: lin_api_secret' "$work/api/auth" && ok "authenticates with the API key" || bad "Authorization header missing"
ops issues | jq -e '.variables.labelName == "nightly-failure"' >/dev/null && ok "looks open tickets up by the label" || bad "lookup: $(ops issues)"
ops issues | jq -e '.query | contains("completed") and contains("canceled")' >/dev/null && ok "ignores closed tickets" || bad "closed tickets counted"

printf '\n\033[1mSecond red night: the open ticket is commented, none is created\033[0m\n'
fresh_world "$(lookup "$ALL_LABELS" "$OPEN_TICKET")"
run_alert
[ "$STATUS" -eq 0 ] && ok "exits 0" || bad "exit $STATUS — $OUTPUT"
[ "$(comments | jq -r .issueId)" = "issue-7" ] && ok "comments the open ticket" || bad "no comment: $(comments)"
[ "$(comments | jq -r .body)" = "$BODY" ] && ok "the comment carries the body" || bad "wrong comment: $(comments)"
[ -z "$(create_input)" ] && ok "creates no ticket" || bad "created a ticket: $(create_input)"
echo "$OUTPUT" | grep -q 'Commented MAG-777' && ok "prints the ticket commented" || bad "not printed: $OUTPUT"

printf '\n\033[1mLabel missing: it is created, and the ticket carries it\033[0m\n'
fresh_world "$(lookup '[{"id":"label-bug","name":"Bug"}]')"
run_alert
[ "$STATUS" -eq 0 ] && ok "exits 0" || bad "exit $STATUS — $OUTPUT"
[ "$(label_input | jq -r '.teamId + " " + .name')" = "team-1 nightly-failure" ] && ok "creates the label in the team" || bad "wrong label: $(label_input)"
[ "$(create_input | jq -c '.labelIds | sort')" = '["label-bug","label-new"]' ] && ok "the ticket carries the new label" || bad "wrong labels: $(create_input)"

printf '\n\033[1mLabel refused: warns, ticket still created\033[0m\n'
fresh_world "$(lookup '[{"id":"label-bug","name":"Bug"}]')"
printf '%s' '{"errors":[{"message":"label refused"}]}' > "$work/api/label-create-response"
run_alert
[ "$STATUS" -eq 0 ] && ok "exits 0" || bad "exit $STATUS — $OUTPUT"
echo "$OUTPUT" | grep -q "WARNING: could not create the Linear label 'nightly-failure'" && ok "warns" || bad "no warning: $OUTPUT"
[ "$(create_input | jq -c '.labelIds')" = '["label-bug"]' ] && ok "created with the labels that exist" || bad "wrong labels: $(create_input)"

printf '\n\033[1mBug label missing: warns, ticket still created\033[0m\n'
fresh_world "$(lookup '[{"id":"label-nightly","name":"nightly-failure"}]')"
run_alert
[ "$STATUS" -eq 0 ] && ok "exits 0" || bad "exit $STATUS — $OUTPUT"
echo "$OUTPUT" | grep -q "WARNING: Linear label 'Bug' not found" && ok "warns" || bad "no warning: $OUTPUT"
[ "$(create_input | jq -c '.labelIds')" = '["label-nightly"]' ] && ok "created with the labels that exist" || bad "wrong labels: $(create_input)"

printf '\n\033[1mLabels: the team label, else the workspace one, never another team label\033[0m\n'
fresh_world "$(lookup '[{"id":"hil-bug","name":"Bug","team":{"key":"HIL"}},{"id":"ws-bug","name":"Bug","team":null},{"id":"mag-bug","name":"Bug","team":{"key":"MAG"}},{"id":"hil-nightly","name":"nightly-failure","team":{"key":"HIL"}}]')"
run_alert
[ "$(label_input | jq -r .name)" = "nightly-failure" ] && ok "another team's label is not used: creates its own" || bad "label: $(label_input)"
[ "$(create_input | jq -c '.labelIds | sort')" = '["label-new","mag-bug"]' ] && ok "the team Bug, the new label" || bad "wrong labels: $(create_input)"

printf '\n\033[1mThe title and the body are data, never parsed\033[0m\n'
fresh_world "$(lookup)"
TITLE_IN='Title with "quotes" and $(whoami)' run_alert
[ "$STATUS" -eq 0 ] && ok "exits 0" || bad "exit $STATUS — $OUTPUT"
[ "$(create_input | jq -r .title)" = 'Title with "quotes" and $(whoami)' ] && ok "title kept verbatim" || bad "title altered: $(create_input)"

printf '\n\033[1mFailures are loud\033[0m\n'
fresh_world "$(lookup)"
KEY='' run_alert
[ "$STATUS" -ne 0 ] && ok "no API key: fails" || bad "passed without an API key"
echo "$OUTPUT" | grep -q 'LINEAR_API_KEY' && ok "says which secret is missing" || bad "unclear message: $OUTPUT"
[ ! -s "$work/api/requests" ] && ok "calls nothing" || bad "called the API without a key"

fresh_world "$(lookup)"
LABEL_IN='' run_alert
[ "$STATUS" -ne 0 ] && ok "no label: fails" || bad "passed without a label"

fresh_world '{"data":{"teams":{"nodes":[]},"issueLabels":{"nodes":[]},"issues":{"nodes":[]}}}'
run_alert
[ "$STATUS" -ne 0 ] && ok "unknown team: fails" || bad "passed with no team"
[ -z "$(create_input)" ] && ok "creates nothing" || bad "created a ticket without a team"

fresh_world '{"errors":[{"message":"Authentication required"}]}'
run_alert
[ "$STATUS" -ne 0 ] && ok "rejected key: fails" || bad "passed on a GraphQL error"
echo "$OUTPUT" | grep -q 'Authentication required' && ok "shows Linear's error" || bad "error hidden: $OUTPUT"

fresh_world "$(lookup)" '{"data":{"issueCreate":{"success":false,"issue":null}}}'
run_alert
[ "$STATUS" -ne 0 ] && ok "ticket refused: fails" || bad "passed when Linear refused the ticket"

fresh_world "$(lookup)" 'not json'
run_alert
[ "$STATUS" -ne 0 ] && ok "garbage answer: fails" || bad "passed on a non-JSON answer"

fresh_world "$(lookup "$ALL_LABELS" "$OPEN_TICKET")"
printf '%s' '{"errors":[{"message":"comment refused"}]}' > "$work/api/comment-response"
run_alert
[ "$STATUS" -ne 0 ] && ok "comment refused: fails" || bad "passed when Linear refused the comment"
[ -z "$(create_input)" ] && ok "and opens no duplicate" || bad "created a ticket: $(create_input)"

printf '\n'
[ "$failures" -eq 0 ] && echo "All alert-ticket tests passed." || echo "$failures failure(s)."
exit "$failures"
