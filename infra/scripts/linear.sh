# shellcheck shell=bash
# =============================================================================
# Linear API helpers, sourced by the scripts that talk to Linear (MAG-184).
# Needs LINEAR_API_KEY; LINEAR_API_URL and LINEAR_TEAM_KEY have defaults.
# =============================================================================

# shellcheck disable=SC2034 # TEAM_KEY and EMERGENCY_STATE are read by the scripts that source this file
API_URL="${LINEAR_API_URL:-https://api.linear.app/graphql}"
TEAM_KEY="${LINEAR_TEAM_KEY:-MAG}"
# The state a ticket enters when its deploy failed: it freezes production.
# shellcheck disable=SC2034
EMERGENCY_STATE="Emergency"

warn() { echo "WARNING: $*" >&2; }
fail() { echo "FATAL: $*" >&2; exit 1; }

# graphql <query> <variables-json> — prints the response. On any error, says
# why on stderr and returns 1: the caller decides whether that is fatal.
graphql() {
  local response
  response=$(jq -n --arg query "$1" --argjson variables "$2" '{query: $query, variables: $variables}' \
    | curl -sS --max-time 30 --retry 2 --retry-delay 2 -X POST "$API_URL" \
        -H "Authorization: $LINEAR_API_KEY" \
        -H "Content-Type: application/json" \
        --data @-) || { echo "Linear API unreachable" >&2; return 1; }

  if ! jq -e . >/dev/null 2>&1 <<<"$response"; then
    echo "Linear API answered something that is not JSON" >&2
    return 1
  fi
  if jq -e '.errors' >/dev/null 2>&1 <<<"$response"; then
    echo "Linear API error: $(jq -c '.errors' <<<"$response")" >&2
    return 1
  fi
  printf '%s' "$response"
}

# update_issue <issue-id> <input-json> — succeeds only when Linear says the update did.
update_issue() {
  local response
  response=$(graphql 'mutation($id: String!, $input: IssueUpdateInput!) { issueUpdate(id: $id, input: $input) { success } }' \
    "$(jq -n --arg id "$1" --argjson input "$2" '{id: $id, input: $input}')") || return 1
  jq -e '.data.issueUpdate.success == true' >/dev/null <<<"$response"
}

# ticket_keys < words — prints the words of stdin that are ticket keys, one per
# line, and warns about the rest. Never globbed: the words are data.
ticket_keys() {
  local word
  tr -s '[:space:]' '\n' | while read -r word; do
    [ -n "$word" ] || continue
    if [[ "$word" =~ ^[A-Z]+-[0-9]+$ ]]; then
      echo "$word"
    else
      warn "'$word' is not a ticket key: ignored"
    fi
  done
}

# frozen_tickets — prints the keys that freeze production, one per line: the
# tickets in « Emergency » and the open `incident` tickets. Returns 1 when
# Linear cannot say. Two aliased queries, not one `or` filter: Linear ignored
# the label in an `or` branch and returned every open ticket.
frozen_tickets() {
  local response
  response=$(graphql 'query($key: String!, $state: String!) {
     emergency: issues(first: 100, filter: {team: {key: {eq: $key}}, state: {name: {eq: $state}}}) { nodes { identifier } }
     incidents: issues(first: 100, filter: {team: {key: {eq: $key}}, labels: {some: {name: {eq: "incident"}}}, state: {type: {nin: ["completed", "canceled"]}}}) { nodes { identifier } }
   }' "$(jq -n --arg key "$TEAM_KEY" --arg state "$EMERGENCY_STATE" '{key: $key, state: $state}')") || return 1
  if ! jq -e '(.data.emergency.nodes | type) == "array" and (.data.incidents.nodes | type) == "array"' >/dev/null <<<"$response"; then
    echo "Linear answered without the frozen tickets: $response" >&2
    return 1
  fi
  jq -r '[.data.emergency.nodes[], .data.incidents.nodes[]] | map(.identifier) | unique | .[]' <<<"$response"
}
