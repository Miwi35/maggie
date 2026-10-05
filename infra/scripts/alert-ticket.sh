#!/usr/bin/env bash
set -euo pipefail

# =============================================================================
# Raises an alert as a Linear ticket, once (MAG-151)
# Usage: LINEAR_API_KEY=… ALERT_LABEL=nightly-failure ALERT_TITLE=… ALERT_BODY=… alert-ticket.sh
#
# GitHub issues are disabled on this repository, so an alert lands in Linear:
#   - an open ticket (not completed or canceled) already carries ALERT_LABEL:
#     ALERT_BODY is added to it as a comment, so a red that lasts a week is one
#     ticket, not seven;
#   - otherwise a `Bug` ticket labelled ALERT_LABEL is created, Urgent, with
#     ALERT_TITLE and ALERT_BODY. It is not an `incident`: it freezes nothing.
#
# The ALERT_LABEL label is created when it does not exist yet. A missing `Bug`
# label only warns. A missing team, a failed lookup, a refused ticket or a
# refused comment fails, loudly: an alert nobody sees is worse than a red job.
# =============================================================================

: "${LINEAR_API_KEY:?LINEAR_API_KEY is not set: add it as a GitHub Actions secret}"
: "${ALERT_LABEL:?ALERT_LABEL is not set}"
: "${ALERT_TITLE:?ALERT_TITLE is not set}"
: "${ALERT_BODY:?ALERT_BODY is not set}"

# shellcheck source=infra/scripts/linear.sh
. "$(dirname "${BASH_SOURCE[0]}")/linear.sh"
URGENT=1
LABEL_COLOR="#B60205"

lookup=$(graphql \
  'query($key: String!, $labelName: String!) {
     teams(filter: {key: {eq: $key}}) { nodes { id } }
     issueLabels(filter: {name: {in: ["Bug", $labelName]}}) { nodes { id name team { key } } }
     issues(first: 1, filter: {team: {key: {eq: $key}}, labels: {some: {name: {eq: $labelName}}}, state: {type: {nin: ["completed", "canceled"]}}}) { nodes { id identifier url } }
   }' \
  "$(jq -n --arg key "$TEAM_KEY" --arg labelName "$ALERT_LABEL" '{key: $key, labelName: $labelName}')") \
  || fail "could not look up team $TEAM_KEY in Linear"

team_id=$(jq -r '.data.teams.nodes[0].id // empty' <<<"$lookup")
[ -n "$team_id" ] || fail "Linear team $TEAM_KEY not found (is the API key from this workspace?)"

# label_id <name> — the team's label of that name, else the workspace's; never another team's.
label_id() {
  jq -r --arg name "$1" --arg team "$TEAM_KEY" \
    '[.data.issueLabels.nodes[] | select(.name == $name and (.team == null or .team.key == $team))]
     | sort_by(.team == null) | .[0].id // empty' <<<"$lookup"
}

# --- An alert is already open: comment it ----------------------------------------

open_id=$(jq -r '.data.issues.nodes[0].id // empty' <<<"$lookup")
if [ -n "$open_id" ]; then
  open_key=$(jq -r '.data.issues.nodes[0].identifier' <<<"$lookup")
  graphql 'mutation($input: CommentCreateInput!) { commentCreate(input: $input) { success } }' \
      "$(jq -n --arg issueId "$open_id" --arg body "$ALERT_BODY" '{input: {issueId: $issueId, body: $body}}')" >/dev/null \
    || fail "could not comment $open_key"
  echo "Commented $open_key"
  exit 0
fi

# --- None: create it -------------------------------------------------------------

alert_label_id=$(label_id "$ALERT_LABEL")
if [ -z "$alert_label_id" ]; then
  if created_label=$(graphql 'mutation($input: IssueLabelCreateInput!) { issueLabelCreate(input: $input) { success issueLabel { id } } }' \
      "$(jq -n --arg teamId "$team_id" --arg name "$ALERT_LABEL" --arg color "$LABEL_COLOR" '{input: {teamId: $teamId, name: $name, color: $color}}')") \
     && jq -e '.data.issueLabelCreate.success == true' >/dev/null <<<"$created_label"; then
    alert_label_id=$(jq -r '.data.issueLabelCreate.issueLabel.id' <<<"$created_label")
  else
    warn "could not create the Linear label '$ALERT_LABEL': the ticket is created without it, so the next alert opens another one"
  fi
fi

label_ids=()
[ -z "$alert_label_id" ] || label_ids+=("$alert_label_id")
bug_id=$(label_id Bug)
if [ -n "$bug_id" ]; then label_ids+=("$bug_id"); else warn "Linear label 'Bug' not found: the ticket is created without it"; fi

input=$(jq -n \
  --arg teamId "$team_id" \
  --arg title "$ALERT_TITLE" \
  --arg description "$ALERT_BODY" \
  --argjson priority "$URGENT" \
  --args '{teamId: $teamId, title: $title, description: $description, priority: $priority, labelIds: $ARGS.positional}' \
  ${label_ids[@]+"${label_ids[@]}"})

created=$(graphql \
  'mutation($input: IssueCreateInput!) { issueCreate(input: $input) { success issue { id identifier url } } }' \
  "$(jq -n --argjson input "$input" '{input: $input}')") || fail "could not create the ticket"
jq -e '.data.issueCreate.success == true' >/dev/null <<<"$created" || fail "Linear did not create the ticket: $created"

echo "Alert ticket: $(jq -r '.data.issueCreate.issue.identifier + " " + .data.issueCreate.issue.url' <<<"$created")"
