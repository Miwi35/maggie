#!/usr/bin/env bash
set -euo pipefail

# =============================================================================
# Prints the Linear keys of the tickets a deploy shipped, one per line (MAG-184)
# Usage: deploy-tickets.sh <deployed-sha> [<last-deployed-sha>]
#
# Every commit after the last successful deploy, up to the deployed one, counts:
# a deploy can carry several merges. A commit gives the key in its subject (the
# squash merge keeps the PR title, `… (MAG-176) (#54)`), or else the one in the
# branch name of its PR (`meven35/mag-184-…`), asked from GitHub with `gh`
# (GH_TOKEN and GH_REPO set). No last deploy, or one that is not an ancestor:
# only the deployed commit counts.
#
# Never fails on a lookup: an incident with fewer links beats none.
# =============================================================================

head="${1:?usage: deploy-tickets.sh <deployed-sha> [<last-deployed-sha>]}"
base="${2:-}"
KEY="${LINEAR_TEAM_KEY:-MAG}"
MAX_COMMITS=50

commits=""
if [ -n "$base" ] && git merge-base --is-ancestor "$base" "$head" 2>/dev/null; then
  commits=$(git rev-list --max-count="$MAX_COMMITS" "$base..$head")
fi
[ -n "$commits" ] || commits=$(git rev-parse --verify "$head^{commit}")

for sha in $commits; do
  keys=$(git log -1 --format=%s "$sha" | grep -oE "\\b$KEY-[0-9]+\\b" || true)
  if [ -z "$keys" ]; then
    branch=$(gh api "repos/$GH_REPO/commits/$sha/pulls" --jq '.[0].head.ref // empty' 2>/dev/null || true)
    keys=$(printf '%s' "$branch" | grep -oiE "\\b$KEY-[0-9]+" | tr '[:lower:]' '[:upper:]' || true)
  fi
  [ -z "$keys" ] || printf '%s\n' "$keys"
done | awk '!seen[$0]++'
