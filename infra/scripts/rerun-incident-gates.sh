#!/usr/bin/env bash
set -euo pipefail

# =============================================================================
# Re-runs the red Incident gates of the open PRs once the freeze lifts (MAG-184)
# Usage: LINEAR_API_KEY=… GH_TOKEN=… GH_REPO=owner/repo rerun-incident-gates.sh
#
# Run every 10 minutes and after each CD run (incident-gate-release.yml). While
# production is frozen it does nothing. Once it is not, every open PR whose
# `Incident gate` check failed gets that job re-run: it turns green, and the
# PR's auto-merge, still armed, fires on its own.
#
# Never fails on Linear or on one PR: it warns, the next run tries again.
# =============================================================================

: "${GH_REPO:?GH_REPO is not set}"
GATE="Incident gate"

# shellcheck source=infra/scripts/linear.sh
. "$(dirname "${BASH_SOURCE[0]}")/linear.sh"

if [ -z "${LINEAR_API_KEY:-}" ] || ! frozen=$(frozen_tickets); then
  echo "::warning::Linear cannot say whether production is frozen: no gate re-run this time."
  exit 0
fi
if [ -n "$frozen" ]; then
  echo "Production is still frozen by $(paste -sd' ' <<<"$frozen"): nothing re-run."
  exit 0
fi

prs=$(gh pr list --repo "$GH_REPO" --state open --limit 100 --json number,statusCheckRollup) \
  || { echo "::warning::could not list the open PRs"; exit 0; }

red=$(jq -r --arg gate "$GATE" \
  '.[] | .number as $pr | .statusCheckRollup[]? | select(.name == $gate and .conclusion == "FAILURE") | "\($pr) \(.detailsUrl)"' \
  <<<"$prs")
if [ -z "$red" ]; then
  echo "Production is not frozen and no gate is red: nothing to re-run."
  exit 0
fi

while read -r pr url; do
  job=$(sed -nE 's|.*/job/([0-9]+).*|\1|p' <<<"$url")
  if [ -z "$job" ]; then
    warn "#$pr: no job id in $url"
  elif gh api -X POST "repos/$GH_REPO/actions/jobs/$job/rerun" >/dev/null; then
    echo "Re-ran the $GATE of #$pr"
  else
    warn "#$pr: could not re-run job $job"
  fi
done <<<"$red"
