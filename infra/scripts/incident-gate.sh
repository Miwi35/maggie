#!/usr/bin/env bash
set -euo pipefail

# =============================================================================
# The Incident gate: no PR merges while production is frozen, but the fix (MAG-184)
# Usage: LINEAR_API_KEY=… PR_TITLE=… PR_BRANCH=… incident-gate.sh
#
# A required check. Production is frozen while a ticket sits in « Emergency »
# (its deploy failed) or an `incident` ticket is open. Then only a PR whose
# title or branch carries one of those keys passes: the ambulance goes through,
# everything else waits, and re-runs by itself once the freeze lifts
# (rerun-incident-gates.sh).
#
# Fails closed: no key, or no answer from Linear, and nothing merges.
# =============================================================================

: "${PR_TITLE?PR_TITLE is not set}"
: "${PR_BRANCH?PR_BRANCH is not set}"

if [ -z "${LINEAR_API_KEY:-}" ]; then
  echo "::error::LINEAR_API_KEY is not set: the Incident gate cannot tell whether production is frozen, so it holds the PR."
  exit 1
fi

# shellcheck source=infra/scripts/linear.sh
. "$(dirname "${BASH_SOURCE[0]}")/linear.sh"

if ! frozen=$(frozen_tickets); then
  echo "::error::Linear did not answer: the Incident gate cannot tell whether production is frozen, so it holds the PR."
  exit 1
fi

if [ -z "$frozen" ]; then
  echo "Production is not frozen: the gate is open."
  exit 0
fi

while read -r key; do
  # A whole key, any case: MAG-18 is not MAG-184, mag-184 in a branch is.
  if grep -qiE "(^|[^[:alnum:]])${key}([^[:alnum:]]|$)" <<<"$PR_TITLE"$'\n'"$PR_BRANCH"; then
    echo "This PR carries $key: it may merge during the freeze."
    exit 0
  fi
done <<<"$frozen"

echo "::error::Production is frozen by $(paste -sd' ' <<<"$frozen"): only a PR whose title or branch carries one of these keys may merge. This check re-runs by itself once the freeze lifts."
exit 1
