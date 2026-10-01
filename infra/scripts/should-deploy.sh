#!/usr/bin/env bash
set -euo pipefail

# =============================================================================
# Does this CD run deploy? (MAG-189)
# Usage: CI_SHA=<sha of the CI run> GITHUB_REPOSITORY=<owner/repo> should-deploy.sh
#
# A CD run starts for every CI that finishes on main, including the late CI of
# an older commit. Such a run used to build and deploy the head of main under
# the same tag: new digests, nothing to roll out, a failed digest assertion and
# a rollback for nothing. A run deploys only when the commit of its CI is
#   - the head of main right now: a newer commit has its own CI and its own run;
#   - not already in production: a CD run on this SHA succeeded earlier.
# Otherwise it stops cleanly: no build, no deploy, no incident.
#
# Writes proceed=true|false to $GITHUB_OUTPUT. Exits 1, with proceed unset, when
# it cannot tell: deploying blind is how the false incident happened.
# =============================================================================

: "${CI_SHA:?CI_SHA is not set}"
: "${GITHUB_REPOSITORY:?GITHUB_REPOSITORY is not set}"
GH="${GH:-gh}"
BRANCH="${DEPLOY_BRANCH:-main}"

decide() {
  echo "proceed=$1" >> "${GITHUB_OUTPUT:-/dev/null}"
  echo "$2"
  exit 0
}

head_sha=$($GH api "repos/$GITHUB_REPOSITORY/commits/$BRANCH" | jq -er .sha) \
  || { echo "FATAL: could not read the head of $BRANCH" >&2; exit 1; }

if [ "$CI_SHA" != "$head_sha" ]; then
  decide false "Not deploying: ${CI_SHA:0:7} is no longer the head of $BRANCH (${head_sha:0:7}); the CI of the head deploys it."
fi

deployed=$($GH api "repos/$GITHUB_REPOSITORY/actions/workflows/cd.yml/runs?status=success&head_sha=$CI_SHA&per_page=1" | jq -er .total_count) \
  || { echo "FATAL: could not list the CD runs of ${CI_SHA:0:7}" >&2; exit 1; }

if [ "$deployed" -gt 0 ]; then
  decide false "Not deploying: ${CI_SHA:0:7} was already deployed by an earlier CD run."
fi

decide true "Deploying ${CI_SHA:0:7}, the head of $BRANCH."
