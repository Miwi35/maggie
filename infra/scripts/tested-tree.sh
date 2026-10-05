#!/usr/bin/env bash
set -euo pipefail

# =============================================================================
# Was the tree of this main commit already tested? (MAG-262)
# Usage: CI_SHA=<sha of the run's commit> GITHUB_REPOSITORY=<owner/repo> tested-tree.sh
#
# The merge train rebases every pull request on the head of main before its CI,
# and merges it with a squash: the commit that lands on main has exactly the file
# tree the CI of the pull request ran on. Running the same suite again on main
# costs 20 to 25 minutes for nothing. The CI of main is skipped when, together,
#   - the commit is the squash (merge) commit of a merged pull request;
#   - its tree is the tree of that pull request's head commit: nothing else landed
#     on main between the rebase and the merge, nothing was added in the merge;
#   - the latest `Pull request` run (ci.yml) on that head commit is green, and
#     really ran (its `Detect changes` job is not skipped, as it is for a draft).
# Anything else (a commit pushed straight to main, a merge of a branch that was
# not up to date, a revert, a red or missing run, an API that does not answer)
# runs the full CI, as before. Doubt always runs the suite.
#
# Writes skip=true|false to $GITHUB_OUTPUT and prints why. Fails only when its
# own inputs are missing: a lookup that fails means "run the CI".
# =============================================================================

: "${CI_SHA:?CI_SHA is not set}"
: "${GITHUB_REPOSITORY:?GITHUB_REPOSITORY is not set}"
GH="${GH:-gh}"

decide() {
  echo "skip=$1" >> "${GITHUB_OUTPUT:-/dev/null}"
  echo "$2"
  exit 0
}
run_ci() { decide false "Running the full CI: $1."; }

api() { $GH api "repos/$GITHUB_REPOSITORY/$1" 2>/dev/null; }

short="${CI_SHA:0:7}"

prs=$(api "commits/$CI_SHA/pulls" \
  | jq -c --arg sha "$CI_SHA" '[.[] | select(.merged_at != null and .merge_commit_sha == $sha)]') \
  || run_ci "could not list the pull requests of $short"
[ "$(jq 'length' <<<"$prs")" -eq 1 ] \
  || run_ci "$short is not the merge commit of one merged pull request (direct push, revert or unknown)"

number=$(jq -r '.[0].number' <<<"$prs")
head_sha=$(jq -r '.[0].head.sha' <<<"$prs")
head_short="${head_sha:0:7}"

main_tree=$(api "git/commits/$CI_SHA" | jq -er .tree.sha) || run_ci "could not read the tree of $short"
head_tree=$(api "git/commits/$head_sha" | jq -er .tree.sha) || run_ci "could not read the tree of #$number ($head_short)"
[ "$main_tree" = "$head_tree" ] \
  || run_ci "the tree of $short (${main_tree:0:7}) is not the tree of #$number ($head_short, ${head_tree:0:7}): main moved after the rebase, or the branch was not up to date"

run=$(api "actions/workflows/ci.yml/runs?head_sha=$head_sha&event=pull_request&per_page=30" \
  | jq -c '[.workflow_runs[] | select(.conclusion != null)] | sort_by(.created_at) | last // empty') \
  || run_ci "could not list the CI runs of $head_short"
[ -n "$run" ] || run_ci "no finished CI run of #$number on $head_short"
[ "$(jq -r .conclusion <<<"$run")" = success ] \
  || run_ci "the latest CI run of #$number on $head_short is $(jq -r .conclusion <<<"$run"), not success"

run_id=$(jq -r .id <<<"$run")
detected=$(api "actions/runs/$run_id/jobs?per_page=100" \
  | jq -r '[.jobs[] | select(.name == "Detect changes") | .conclusion] | first // empty') \
  || run_ci "could not read the jobs of the CI run $run_id"
[ "$detected" = success ] \
  || run_ci "the CI run $run_id of #$number did not run its jobs (Detect changes: ${detected:-missing}), a draft's run says nothing"

decide true "Skipping the CI of main: $short has the tree ${main_tree:0:7} of #$number ($head_short), which the CI run $run_id already passed. Build, deployment and smoke tests still run."
