#!/usr/bin/env bash
set -euo pipefail

# =============================================================================
# Opens a production incident ticket in Linear (MAG-146)
# Usage: INCIDENT_TITLE=… INCIDENT_BODY=… LINEAR_API_KEY=… open-incident.sh
#
# Run on the CI runner by the CD workflow's rollback job. GitHub issues are
# disabled on this repository, so Linear is where an incident is tracked: a
# `Bug` ticket, Urgent, labelled `incident`, in team Maggie.
#
# A missing label does not stop the ticket: an unlabelled incident is better
# than none. A missing team or a rejected request does, loudly.
# =============================================================================

: "${LINEAR_API_KEY:?LINEAR_API_KEY is not set: add it as a GitHub Actions secret}"
: "${INCIDENT_TITLE:?INCIDENT_TITLE is not set}"
: "${INCIDENT_BODY:?INCIDENT_BODY is not set}"

API_URL="${LINEAR_API_URL:-https://api.linear.app/graphql}"
TEAM_KEY="${LINEAR_TEAM_KEY:-MAG}"
URGENT=1

warn() { echo "WARNING: $*" >&2; }
fail() { echo "FATAL: $*" >&2; exit 1; }

# graphql <query> <variables-json> — prints the response, fails on any error.
graphql() {
  local response
  response=$(jq -n --arg query "$1" --argjson variables "$2" '{query: $query, variables: $variables}' \
    | curl -sS --max-time 30 --retry 2 --retry-delay 2 -X POST "$API_URL" \
        -H "Authorization: $LINEAR_API_KEY" \
        -H "Content-Type: application/json" \
        --data @-) || fail "Linear API unreachable"

  if ! jq -e . >/dev/null 2>&1 <<<"$response"; then
    fail "Linear API answered something that is not JSON"
  fi
  if jq -e '.errors' >/dev/null 2>&1 <<<"$response"; then
    fail "Linear API error: $(jq -c '.errors' <<<"$response")"
  fi
  printf '%s' "$response"
}

lookup=$(graphql \
  'query($key: String!) {
     teams(filter: {key: {eq: $key}}) { nodes { id } }
     issueLabels(filter: {name: {in: ["Bug", "incident"]}}) { nodes { id name } }
   }' \
  "$(jq -n --arg key "$TEAM_KEY" '{key: $key}')")

team_id=$(jq -r '.data.teams.nodes[0].id // empty' <<<"$lookup")
[ -n "$team_id" ] || fail "Linear team $TEAM_KEY not found (is the API key from this workspace?)"

label_ids=()
for name in Bug incident; do
  id=$(jq -r --arg name "$name" '[.data.issueLabels.nodes[] | select(.name == $name)][0].id // empty' <<<"$lookup")
  if [ -n "$id" ]; then
    label_ids+=("$id")
  else
    warn "Linear label '$name' not found: the incident is created without it"
  fi
done

input=$(jq -n \
  --arg teamId "$team_id" \
  --arg title "$INCIDENT_TITLE" \
  --arg description "$INCIDENT_BODY" \
  --argjson priority "$URGENT" \
  --args '{teamId: $teamId, title: $title, description: $description, priority: $priority, labelIds: $ARGS.positional}' \
  ${label_ids[@]+"${label_ids[@]}"})

created=$(graphql \
  'mutation($input: IssueCreateInput!) { issueCreate(input: $input) { success issue { identifier url } } }' \
  "$(jq -n --argjson input "$input" '{input: $input}')")

jq -e '.data.issueCreate.success == true' >/dev/null <<<"$created" || fail "Linear did not create the issue: $created"

echo "Incident ticket: $(jq -r '.data.issueCreate.issue.identifier + " " + .data.issueCreate.issue.url' <<<"$created")"
