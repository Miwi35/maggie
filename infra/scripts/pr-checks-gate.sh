#!/usr/bin/env bash
set -euo pipefail

# =============================================================================
# PR checks passed — the aggregator of the pull-request pipeline (MAG-267)
# Usage: DRAFT=<true|false> NEEDS='<toJSON(needs)>' pr-checks-gate.sh
#
# GitHub counts a skipped required check as a success. A draft pull request, a
# run cancelled by a newer one, a job whose `needs` failed: every one of them
# leaves a skipped check, and an armed auto-merge merged five pull requests that
# no test had run on. This job is the one required check that cannot be skipped
# (`if: always()` in ci.yml) and that reads skipped for what it is.
#
# Green only when everything that had to run ran and succeeded:
#   - the pull request is not a draft;
#   - `Detect changes` succeeded;
#   - every job below succeeded — or was skipped *by the decision of `Detect
#     changes`* (its path filter said false). A job without a filter is expected
#     on every ready pull request: skipped is a failure for it.
#
# Anything else is red: failure, cancelled, skipped for another reason, absent
# from `needs` (a job renamed in ci.yml but not here).
# =============================================================================

: "${DRAFT:?DRAFT is not set}"
: "${NEEDS:?NEEDS is not set}"

# job:filter — `filter` is the output of `Detect changes` that switches the job on,
# `-` when nothing may switch it off. The jobs the e2e and mobile aggregators
# (`E2E Stack (smoke journey)`, `E2E Mobile (phone)`) stand for are listed
# through them, not one by one: they carry their own skip rules.
REQUIRED=(
  api-lint:api
  agent-lint:agent
  ciqual-lint:ciqual
  admin-lint:admin
  api-test:api
  agent-test:agent
  ciqual-test:ciqual
  admin-test:admin
  mobile-unit:mobile_unit
  cron-image:cron
  e2e-stack:-
  e2e-mobile:-
  infra-scripts:-
  incident-gate:-
)

problems=0
fail() {
  echo "::error::$1"
  problems=$((problems + 1))
}

if [ "$DRAFT" = true ]; then
  fail "the pull request is a draft: nothing ran, and a draft must not merge"
fi

result_of() { jq -r --arg job "$1" '.[$job].result // "absent"' <<<"$NEEDS"; }
filter_of() { jq -r --arg name "$1" '.changes.outputs[$name] // "absent"' <<<"$NEEDS"; }

changes="$(result_of changes)"
printf '%-18s %s\n' changes "$changes"
[ "$changes" = success ] || fail "Detect changes did not succeed ($changes)"

for entry in "${REQUIRED[@]}"; do
  job="${entry%%:*}"
  filter="${entry#*:}"
  result="$(result_of "$job")"
  wanted=required
  if [ "$filter" != - ]; then
    wanted="$(filter_of "$filter")"
  fi
  printf '%-18s %-10s (%s)\n' "$job" "$result" "$wanted"

  case "$result" in
    success) ;;
    skipped)
      if [ "$filter" = - ]; then
        fail "$job was skipped, and nothing but a draft or a cancelled run skips it"
      elif [ "$changes" != success ] || [ "$wanted" != false ]; then
        fail "$job was skipped, not by Detect changes ($filter=$wanted)"
      fi
      ;;
    *) fail "$job did not succeed ($result)" ;;
  esac
done

if [ "$problems" -gt 0 ]; then
  echo "PR checks failed: $problems problem(s)"
  exit 1
fi
echo "PR checks passed: everything that had to run ran, and is green"
