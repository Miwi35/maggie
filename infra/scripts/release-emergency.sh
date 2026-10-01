#!/usr/bin/env bash
set -euo pipefail

# =============================================================================
# Takes the tickets a green deploy shipped out of « Emergency » (MAG-184)
# Usage: LINEAR_API_KEY=… DEPLOY_TICKETS="MAG-12 MAG-15" release-emergency.sh
#
# Run by the CD workflow once the smoke suite passed. A ticket the deploy shipped
# that is still in « Emergency » has had its fix deployed green: it goes where a
# merge sends it, Done for a Task (nothing to accept in the UI), Recette for the
# rest. The GitHub integration usually moved it already on merge; this is the
# net for when it did not, since a ticket left in « Emergency » freezes every PR.
#
# Never fails on one ticket: it warns and goes on to the next.
# =============================================================================

: "${LINEAR_API_KEY:?LINEAR_API_KEY is not set: add it as a GitHub Actions secret}"

# shellcheck source=infra/scripts/linear.sh
. "$(dirname "${BASH_SOURCE[0]}")/linear.sh"

keys=()
while read -r key; do keys+=("$key"); done < <(ticket_keys <<<"${DEPLOY_TICKETS:-}")
if [ "${#keys[@]}" -eq 0 ]; then
  echo "No ticket in this deploy: nothing to release."
  exit 0
fi

states=$(graphql \
  'query($key: String!) { workflowStates(filter: {team: {key: {eq: $key}}, name: {in: ["Recette", "Done"]}}) { nodes { id name } } }' \
  "$(jq -n --arg key "$TEAM_KEY" '{key: $key}')") || fail "could not look up the states of team $TEAM_KEY"
state_id() { jq -r --arg name "$1" '[.data.workflowStates.nodes[] | select(.name == $name)][0].id // empty' <<<"$states"; }
recette_id=$(state_id Recette)
done_id=$(state_id Done)

for key in "${keys[@]}"; do
  if ! found=$(graphql 'query($id: String!) { issue(id: $id) { id state { name } labels { nodes { name } } } }' \
      "$(jq -n --arg id "$key" '{id: $id}')"); then
    warn "$key not found in Linear: left alone"
    continue
  fi
  [ "$(jq -r '.data.issue.state.name // empty' <<<"$found")" = "$EMERGENCY_STATE" ] || continue

  if jq -e '[.data.issue.labels.nodes[].name] | index("Task")' >/dev/null <<<"$found"; then
    target=Done; target_id=$done_id
  else
    target=Recette; target_id=$recette_id
  fi
  if [ -z "$target_id" ]; then
    warn "state $target not found: $key stays in « $EMERGENCY_STATE »"
    continue
  fi

  if graphql 'mutation($id: String!, $input: IssueUpdateInput!) { issueUpdate(id: $id, input: $input) { success } }' \
      "$(jq -n --arg id "$(jq -r .data.issue.id <<<"$found")" --arg state "$target_id" '{id: $id, input: {stateId: $state}}')" >/dev/null; then
    echo "Released $key: « $EMERGENCY_STATE » → $target"
  else
    warn "could not move $key out of « $EMERGENCY_STATE »"
  fi
done
