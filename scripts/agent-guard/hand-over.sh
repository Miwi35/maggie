#!/usr/bin/env bash
#
# Hand a pull request over to a human (MAG-128): label it `needs-human`, switch
# its auto-merge off, take it out of the merge queue, and say why in a comment.
# The comment is the body on stdin; it is edited in place on the next call with
# the same marker, never repeated.
#
#   echo "why" | scripts/agent-guard/hand-over.sh <pr> <marker>
#
# Needs GH_TOKEN and GH_REPO.

set -euo pipefail

PR="${1:?usage: hand-over.sh <pr> <marker>}"
MARKER="<!-- agent-guard:${2:?usage: hand-over.sh <pr> <marker>} -->"
BODY="$MARKER"$'\n'"$(cat)"

gh label create needs-human --color D93F0B --description "A human must look at this before it merges" 2>/dev/null || true
gh api -X POST "repos/$GH_REPO/issues/$PR/labels" -f "labels[]=needs-human" > /dev/null
gh pr merge "$PR" --disable-auto 2>/dev/null || true
# Already in the merge queue (`gh pr merge --auto` enqueues at once a PR whose
# checks are green): take it out, or it merges with the queue.
queue="$(gh api graphql -F n="$PR" -f o="${GH_REPO%/*}" -f r="${GH_REPO#*/}" \
  -f query='query($o: String!, $r: String!, $n: Int!) { repository(owner: $o, name: $r) { pullRequest(number: $n) { id isInMergeQueue } } }' \
  --jq '.data.repository.pullRequest | "\(.id) \(.isInMergeQueue)"' 2>/dev/null || true)"
if [ "${queue#* }" = true ]; then
  gh api graphql -f id="${queue%% *}" \
    -f query='mutation($id: ID!) { dequeuePullRequest(input: {id: $id}) { clientMutationId } }' > /dev/null \
    || echo "::warning::could not take #$PR out of the merge queue: remove it by hand"
fi

ids="$(gh api "repos/$GH_REPO/issues/$PR/comments" --paginate \
  --jq ".[] | select(.body | startswith(\"$MARKER\")) | .id")"
id="${ids%%$'\n'*}"

if [ -n "$id" ]; then
  gh api -X PATCH "repos/$GH_REPO/issues/comments/$id" -f body="$BODY" > /dev/null
else
  gh pr comment "$PR" --body "$BODY"
fi
