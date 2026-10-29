#!/usr/bin/env bash
set -euo pipefail

# =============================================================================
# Which light jobs already passed on this pull request? (MAG-702)
# Usage: PR=<number> BRANCH=<head branch> HEAD_SHA=<commit the job tests> \
#        GITHUB_REPOSITORY=<owner/repo> green-jobs.sh   (from a checkout)
#
# A ready pull request replays the lints and unit tests the CI of its draft
# already passed. The train rebases the draft right before marking it ready, so
# the commit id says nothing; what matters to a job is the content of the
# folders it reads. Same principle as tested-tree.sh (main), job by job: a job
# is reused when it succeeded in an earlier finished `ci.yml` run of this very
# pull request AND every input folder has the same tree hash at that run's head
# as in HEAD_SHA (the merge commit this run tests). Newest run first.
#
# The input folders are those of the `dorny/paths-filter` filters of the
# `changes` job in ci.yml: KEEP THE TABLE BELOW IN STEP WITH THEM.
#
# Writes to $GITHUB_OUTPUT:
#   reuse=<json list of job ids reused>        e.g. ["admin-lint","admin-test"]
#   reuse_runs=<json object job id -> run id>  where the coverage artifact is
# and one line per job to the job summary: reused from which run, or replayed
# and why. Doubt always replays: any failure, an API that does not answer, a
# commit that cannot be fetched → nothing is reused.
# =============================================================================

: "${PR:?PR is not set}"
: "${BRANCH:?BRANCH is not set}"
: "${HEAD_SHA:?HEAD_SHA is not set}"
: "${GITHUB_REPOSITORY:?GITHUB_REPOSITORY is not set}"
GH="${GH:-gh}"
SUMMARY="${GITHUB_STEP_SUMMARY:-/dev/null}"
MAX_RUNS=10

# job id|display name (the check's name in the jobs API)|folders it reads
COVERAGE='.github/actions/coverage'
CI_YML='.github/workflows/ci.yml'
JOBS=(
  "api-lint|API Lint (PHPStan)|api $COVERAGE $CI_YML"
  "agent-lint|Agent Lint (Ruff)|agent $COVERAGE $CI_YML"
  "ciqual-lint|Ciqual Lint (Ruff)|ciqual $COVERAGE $CI_YML"
  "admin-lint|Admin Lint (ESLint + TypeScript)|admin $COVERAGE api/contract agent/contract design $CI_YML"
  "api-test|API Tests (PHPUnit)|api $COVERAGE $CI_YML"
  "agent-test|Agent Tests (pytest)|agent $COVERAGE $CI_YML"
  "ciqual-test|Ciqual Tests (pytest)|ciqual $COVERAGE $CI_YML"
  "admin-test|Admin Tests (Vitest)|admin $COVERAGE api/contract agent/contract design $CI_YML"
  "mobile-unit|Mobile Unit Tests (release)|mobile api/contract agent/contract design $COVERAGE $CI_YML"
)

reuse='[]'
reuse_runs='{}'
declare -A reason=()

emit() {
  {
    echo "reuse=$reuse"
    echo "reuse_runs=$reuse_runs"
  } >> "${GITHUB_OUTPUT:-/dev/null}"
}

# Any error from here on means « replay everything ».
doubt() {
  echo "::notice::Nothing reused from earlier runs: $1"
  echo "Nothing reused from earlier runs: $1. Every job runs." >> "$SUMMARY"
  reuse='[]'
  reuse_runs='{}'
  emit
  exit 0
}

api() { $GH api "repos/$GITHUB_REPOSITORY/$1" 2>/dev/null; }

# sig <commit> <folder...> — the tree hash of each folder, « - » when absent.
sig() {
  local sha=$1 p
  shift
  for p in "$@"; do
    printf '%s=%s\n' "$p" "$(git rev-parse --verify -q "$sha:$p" || echo -)"
  done
}

now_sig_ok() { git rev-parse --verify -q "$HEAD_SHA^{commit}" > /dev/null; }
now_sig_ok || doubt "$HEAD_SHA is not in the checkout"

runs=$(api "actions/workflows/ci.yml/runs?branch=$BRANCH&event=pull_request&per_page=30" \
  | jq -c --argjson pr "$PR" '[.workflow_runs[]
      | select(.conclusion != null and any(.pull_requests[]?; .number == $pr))]
      | sort_by(.created_at) | reverse | .[:'"$MAX_RUNS"']') \
  || doubt "could not list the CI runs of $BRANCH"
[ "$(jq length <<<"$runs")" -gt 0 ] || doubt "no finished CI run of #$PR yet"

declare -A done_job=()
for run in $(jq -r '.[] | "\(.id):\(.head_sha)"' <<<"$runs"); do
  run_id=${run%%:*}
  run_sha=${run#*:}
  jobs=$(api "actions/runs/$run_id/jobs?per_page=100") || continue
  fetched=unknown
  for entry in "${JOBS[@]}"; do
    IFS='|' read -r id name folders <<<"$entry"
    [ -z "${done_job[$id]:-}" ] || continue
    [ "$(jq -r --arg n "$name" '[.jobs[] | select(.name == $n) | .conclusion] | first // "absent"' <<<"$jobs")" = success ] || continue

    if [ "$fetched" = unknown ]; then
      if git rev-parse --verify -q "$run_sha^{commit}" > /dev/null \
        || git fetch -q --no-tags --depth=1 origin "$run_sha" 2> /dev/null; then
        fetched=yes
      else
        fetched=no
      fi
    fi
    if [ "$fetched" != yes ]; then
      reason[$id]="${reason[$id]:-run $run_id: its commit ${run_sha:0:7} cannot be fetched}"
      continue
    fi

    # shellcheck disable=SC2086
    if [ "$(sig "$run_sha" $folders)" = "$(sig "$HEAD_SHA" $folders)" ]; then
      done_job[$id]=1
      reuse=$(jq -c --arg id "$id" '. + [$id]' <<<"$reuse")
      reuse_runs=$(jq -c --arg id "$id" --argjson run "$run_id" '. + {($id): $run}' <<<"$reuse_runs")
      reason[$id]="reused from run $run_id (${run_sha:0:7}): green there, same content in $folders"
    else
      reason[$id]="${reason[$id]:-replayed: the content of $folders changed since the green run $run_id (${run_sha:0:7})}"
    fi
  done
done

{
  echo "### Jobs reused from earlier runs of #$PR (MAG-702)"
  for entry in "${JOBS[@]}"; do
    id=${entry%%|*}
    echo "- \`$id\`: ${reason[$id]:-replayed: no earlier run of #$PR passed it}"
  done
} | tee -a "$SUMMARY"

emit
