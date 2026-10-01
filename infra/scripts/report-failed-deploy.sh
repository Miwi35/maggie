#!/usr/bin/env bash
set -euo pipefail

# =============================================================================
# Reports a failed production deploy in Linear (MAG-146, MAG-184)
# Usage: LINEAR_API_KEY=… INCIDENT_TITLE=… INCIDENT_BODY=… FAILURE_CAUSE=… PROD_STATE=… \
#        DEPLOY_TICKETS="MAG-12 MAG-15" ROLLBACK_FAILED=false RUN_URL=… report-failed-deploy.sh
#
# INCIDENT_TITLE and INCIDENT_BODY make the incident ticket, in English like the
# run summary; FAILURE_CAUSE and PROD_STATE make the ticket comments, in French
# like the rest of the team's Linear.
#
# Run on the CI runner by the CD workflow's rollback job, after a build, the
# deploy or the smoke suite failed. Each ticket the deploy shipped:
#   - moves to the « Emergency » state, which freezes production: the Incident
#     gate holds every other PR and the dispatcher delegates nothing else;
#   - gets the `Top` label and a short comment (cause, production state, run).
#
# A separate incident ticket (`Bug`, Urgent, labels `incident` and `Top`) is
# opened only when the tickets cannot carry the freeze: none was found, the
# « Emergency » state is missing, none could be moved, or the rollback itself
# failed (production may be broken: that needs more than a ticket to fix).
#
# A missing label or a ticket that cannot be found, moved or commented only
# warns. A missing team, a failed lookup or a refused incident fails, loudly.
# =============================================================================

: "${LINEAR_API_KEY:?LINEAR_API_KEY is not set: add it as a GitHub Actions secret}"
: "${INCIDENT_TITLE:?INCIDENT_TITLE is not set}"
: "${INCIDENT_BODY:?INCIDENT_BODY is not set}"
: "${FAILURE_CAUSE:?FAILURE_CAUSE is not set}"
: "${PROD_STATE:?PROD_STATE is not set}"

# shellcheck source=infra/scripts/linear.sh
. "$(dirname "${BASH_SOURCE[0]}")/linear.sh"
URGENT=1

# --- What is there --------------------------------------------------------------

keys=()
while read -r key; do keys+=("$key"); done < <(ticket_keys <<<"${DEPLOY_TICKETS:-}")

lookup=$(graphql \
  'query($key: String!, $state: String!) {
     teams(filter: {key: {eq: $key}}) { nodes { id } }
     issueLabels(filter: {name: {in: ["Bug", "incident", "Top"]}}) { nodes { id name team { key } } }
     workflowStates(filter: {team: {key: {eq: $key}}, name: {eq: $state}}) { nodes { id } }
   }' \
  "$(jq -n --arg key "$TEAM_KEY" --arg state "$EMERGENCY_STATE" '{key: $key, state: $state}')") \
  || fail "could not look up team $TEAM_KEY in Linear"

team_id=$(jq -r '.data.teams.nodes[0].id // empty' <<<"$lookup")
[ -n "$team_id" ] || fail "Linear team $TEAM_KEY not found (is the API key from this workspace?)"

# label_id <name> — the team's label of that name, else the workspace's; never another team's.
label_id() {
  jq -r --arg name "$1" --arg team "$TEAM_KEY" \
    '[.data.issueLabels.nodes[] | select(.name == $name and (.team == null or .team.key == $team))]
     | sort_by(.team == null) | .[0].id // empty' <<<"$lookup"
}
top_id=$(label_id Top)
[ -n "$top_id" ] || warn "Linear label 'Top' not found: nothing gets it"

emergency_id=$(jq -r '.data.workflowStates.nodes[0].id // empty' <<<"$lookup")
[ -n "$emergency_id" ] || warn "Linear state « $EMERGENCY_STATE » not found in team $TEAM_KEY: no ticket is moved, an incident is opened instead"

tickets=()     # "KEY ID"
for key in ${keys[@]+"${keys[@]}"}; do
  id=""
  if found=$(graphql 'query($id: String!) { issue(id: $id) { id } }' "$(jq -n --arg id "$key" '{id: $id}')"); then
    id=$(jq -r '.data.issue.id // empty' <<<"$found")
  fi
  if [ -n "$id" ]; then
    tickets+=("$key $id")
  else
    warn "$key not found in Linear: left alone"
  fi
done

# --- The incident ticket, when the tickets cannot carry the freeze --------------

incident_id=""
incident_key=""
incident_url=""

# open_incident <why>
open_incident() {
  local why=$1 label_ids=() name id shipped input created
  for name in Bug incident Top; do
    id=$(label_id "$name")
    if [ -n "$id" ]; then label_ids+=("$id"); else warn "Linear label '$name' not found: the incident is created without it"; fi
  done

  if [ "${#keys[@]}" -gt 0 ]; then
    shipped="Tickets in this deploy: ${keys[*]}"
  else
    shipped="No ticket found for this deploy: no $TEAM_KEY key in the commit subjects or the PR branches."
  fi

  input=$(jq -n \
    --arg teamId "$team_id" \
    --arg title "$INCIDENT_TITLE" \
    --arg description "$(printf '%s\n\n%s\n\nOpened because %s.\n' "$INCIDENT_BODY" "$shipped" "$why")" \
    --argjson priority "$URGENT" \
    --args '{teamId: $teamId, title: $title, description: $description, priority: $priority, labelIds: $ARGS.positional}' \
    ${label_ids[@]+"${label_ids[@]}"})

  created=$(graphql \
    'mutation($input: IssueCreateInput!) { issueCreate(input: $input) { success issue { id identifier url } } }' \
    "$(jq -n --argjson input "$input" '{input: $input}')") || fail "could not create the incident"
  jq -e '.data.issueCreate.success == true' >/dev/null <<<"$created" || fail "Linear did not create the incident: $created"

  incident_id=$(jq -r '.data.issueCreate.issue.id' <<<"$created")
  incident_key=$(jq -r '.data.issueCreate.issue.identifier' <<<"$created")
  incident_url=$(jq -r '.data.issueCreate.issue.url' <<<"$created")
  echo "Incident ticket: $incident_key $incident_url"
}

if [ "${ROLLBACK_FAILED:-false}" = "true" ]; then
  open_incident "the rollback itself failed"
elif [ "${#tickets[@]}" -eq 0 ]; then
  open_incident "no ticket of this deploy could be found in Linear"
elif [ -z "$emergency_id" ]; then
  open_incident "the « $EMERGENCY_STATE » state is missing in Linear"
fi

# --- The shipped tickets ----------------------------------------------------------

moved=0
for ticket in ${tickets[@]+"${tickets[@]}"}; do
  key=${ticket%% *}
  id=${ticket#* }

  # Two updates: a label Linear refuses must not keep the ticket out of « Emergency ».
  if [ -n "$emergency_id" ]; then
    if update_issue "$id" "$(jq -n --arg state "$emergency_id" '{stateId: $state}')"; then
      moved=$((moved + 1))
      echo "Moved $key to « $EMERGENCY_STATE »"
    else
      warn "could not move $key to « $EMERGENCY_STATE »"
    fi
  fi
  if [ -n "$top_id" ]; then
    update_issue "$id" "$(jq -n --arg top "$top_id" '{addedLabelIds: [$top]}')" || warn "could not add Top to $key"
  fi

  if [ -n "$emergency_id" ]; then
    freeze="Ce ticket est en « $EMERGENCY_STATE » : la prod est gelée jusqu'au déploiement vert de son correctif."
  else
    freeze="Il n'a pas pu passer en « $EMERGENCY_STATE » : voir l'incident."
  fi
  comment=$(printf '**Le déploiement en production a échoué** : %s.\n\n%s\n\n%s\n\n%s%s\n' \
    "$FAILURE_CAUSE" "$PROD_STATE" "$freeze" \
    "${RUN_URL:+Run : $RUN_URL}" "${incident_key:+ · Incident : [$incident_key]($incident_url)}")
  if graphql 'mutation($input: CommentCreateInput!) { commentCreate(input: $input) { success } }' \
      "$(jq -n --arg issueId "$id" --arg body "$comment" '{input: {issueId: $issueId, body: $body}}')" >/dev/null; then
    echo "Commented $key"
  else
    warn "could not comment $key"
  fi

  if [ -n "$incident_id" ]; then
    graphql 'mutation($input: IssueRelationCreateInput!) { issueRelationCreate(input: $input) { success } }' \
      "$(jq -n --arg issueId "$incident_id" --arg related "$id" '{input: {issueId: $issueId, relatedIssueId: $related, type: "related"}}')" >/dev/null \
      && echo "Linked $key to $incident_key" || warn "could not link $key to $incident_key"
  fi
done

# Tickets were found but none carries the freeze: the incident has to.
if [ -z "$incident_id" ] && [ "$moved" -eq 0 ]; then
  open_incident "no ticket of this deploy could be moved to « $EMERGENCY_STATE »"
fi
