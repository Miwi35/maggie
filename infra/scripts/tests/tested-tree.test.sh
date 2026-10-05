#!/usr/bin/env bash
# shellcheck disable=SC2015 # `cond && ok || bad`: ok and bad cannot fail
#
# Tests of infra/scripts/tested-tree.sh against a fake gh (MAG-262).
#
# The merge train rebases a pull request on main and squash-merges it: the commit
# on main has the tree the pull request's CI ran on. The CI of main is skipped for
# exactly that case; every other one (not up to date, direct push, revert, red or
# missing CI, API down) runs the full suite.
#
# Usage: infra/scripts/tests/tested-tree.test.sh

set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SCRIPT="$HERE/../tested-tree.sh"
failures=0

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

ok()  { printf '  \033[32m✓\033[0m %s\n' "$1"; }
bad() { printf '  \033[31m✗\033[0m %s\n' "$1"; failures=$((failures + 1)); }

h() { printf '%40s' '' | tr ' ' "$1"; }
MAIN_SHA="$(h a)"      # the squash commit on main
HEAD_SHA="$(h b)"      # the head of the merged pull request
TREE="$(h 1)"
OTHER_TREE="$(h 2)"
RUN_ID=777

# fresh_world — a pull request #42, merged as MAIN_SHA, same tree as its head, one
# green CI run that ran its jobs.
fresh_world() {
  rm -rf "$work/gh"
  mkdir -p "$work/gh"
  : > "$work/gh/calls"
  printf '[{"number":42,"merged_at":"2026-10-06T01:00:00Z","merge_commit_sha":"%s","head":{"sha":"%s"}}]' \
    "$MAIN_SHA" "$HEAD_SHA" > "$work/gh/pulls-$MAIN_SHA.json"
  printf '{"tree":{"sha":"%s"}}' "$TREE" > "$work/gh/commit-$MAIN_SHA.json"
  printf '{"tree":{"sha":"%s"}}' "$TREE" > "$work/gh/commit-$HEAD_SHA.json"
  set_runs '{"id":'"$RUN_ID"',"conclusion":"success","created_at":"2026-10-06T00:10:00Z"}'
  printf '{"jobs":[{"name":"Detect changes","conclusion":"success"},{"name":"API tests","conclusion":"success"}]}' \
    > "$work/gh/jobs-$RUN_ID.json"
}

# set_runs <run-json>... — the CI runs of the pull request head, as the API lists them.
set_runs() {
  local IFS=,
  printf '{"workflow_runs":[%s]}' "$*" > "$work/gh/runs-$HEAD_SHA.json"
}

# decide — the verdict for MAIN_SHA.
decide() {
  : > "$work/output"
  OUTPUT="$(FAKE_GH_DIR="$work/gh" GH="$HERE/fake-gh-tree.sh" GITHUB_REPOSITORY=Miwi35/maggie \
    GITHUB_OUTPUT="$work/output" CI_SHA="${1-$MAIN_SHA}" "$SCRIPT" 2>&1)"
  STATUS=$?
  SKIP="$(sed -n 's/^skip=//p' "$work/output")"
}

# runs_ci <why> — the full CI must run, and the log must say why.
runs_ci() {
  [ "$STATUS" -eq 0 ] && ok "exits 0" || bad "exit $STATUS — $OUTPUT"
  [ "$SKIP" = "false" ] && ok "runs the full CI: $1" || bad "skip='$SKIP' — $OUTPUT"
  printf '%s' "$OUTPUT" | grep -qF "$2" && ok "the log says why ($2)" || bad "the log does not say '$2' — $OUTPUT"
}

printf '\n\033[1mA pull request rebased on main, green, squash-merged\033[0m\n'
fresh_world
decide
[ "$STATUS" -eq 0 ] && ok "exits 0" || bad "exit $STATUS — $OUTPUT"
[ "$SKIP" = "true" ] && ok "skips the CI of main" || bad "skip='$SKIP' — $OUTPUT"
printf '%s' "$OUTPUT" | grep -qF '#42' && ok "the log names the pull request" || bad "silent about the pull request — $OUTPUT"
printf '%s' "$OUTPUT" | grep -qF "run $RUN_ID" && ok "the log names the CI run it trusts" || bad "silent about the CI run — $OUTPUT"
printf '%s' "$OUTPUT" | grep -qiF 'deployment' && ok "the log says the deployment still runs" || bad "does not say what still runs — $OUTPUT"
grep -qF 'event=pull_request' "$work/gh/calls" && ok "only pull request runs count" || bad "does not filter on the event"
grep -qF "head_sha=$HEAD_SHA" "$work/gh/calls" && ok "asks about the head of the pull request" || bad "does not filter on the head"

printf '\n\033[1mMerged out of date: main moved, the trees differ\033[0m\n'
fresh_world
printf '{"tree":{"sha":"%s"}}' "$OTHER_TREE" > "$work/gh/commit-$MAIN_SHA.json"
decide
runs_ci "tree mismatch" "is not the tree of #42"

printf '\n\033[1mA commit pushed straight to main\033[0m\n'
fresh_world
echo '[]' > "$work/gh/pulls-$MAIN_SHA.json"
decide
runs_ci "no pull request" "not the merge commit"

printf '\n\033[1mA commit of a pull request that is not the merge commit (an old commit of the branch)\033[0m\n'
fresh_world
printf '[{"number":42,"merged_at":"2026-10-06T01:00:00Z","merge_commit_sha":"%s","head":{"sha":"%s"}}]' \
  "$(h c)" "$HEAD_SHA" > "$work/gh/pulls-$MAIN_SHA.json"
decide
runs_ci "another merge commit" "not the merge commit"

printf '\n\033[1mA pull request that is still open or was closed unmerged\033[0m\n'
fresh_world
printf '[{"number":42,"merged_at":null,"merge_commit_sha":"%s","head":{"sha":"%s"}}]' "$MAIN_SHA" "$HEAD_SHA" > "$work/gh/pulls-$MAIN_SHA.json"
decide
runs_ci "not merged" "not the merge commit"

printf '\n\033[1mThe CI of the pull request was red\033[0m\n'
fresh_world
set_runs '{"id":'"$RUN_ID"',"conclusion":"failure","created_at":"2026-10-06T00:10:00Z"}'
decide
runs_ci "red CI" "failure"

printf '\n\033[1mNo CI run on the head\033[0m\n'
fresh_world
set_runs
decide
runs_ci "no run" "no finished CI run"

printf '\n\033[1mA red run, then a green re-run: the latest verdict counts\033[0m\n'
fresh_world
set_runs '{"id":5,"conclusion":"failure","created_at":"2026-10-06T00:00:00Z"}' \
  '{"id":'"$RUN_ID"',"conclusion":"success","created_at":"2026-10-06T00:30:00Z"}'
decide
[ "$SKIP" = "true" ] && ok "skips: the later green run is the verdict" || bad "skip='$SKIP' — $OUTPUT"
fresh_world
set_runs '{"id":5,"conclusion":"success","created_at":"2026-10-06T00:00:00Z"}' \
  '{"id":6,"conclusion":"failure","created_at":"2026-10-06T00:30:00Z"}'
decide
runs_ci "a later red run" "failure"

printf '\n\033[1mA run still going or cancelled has no verdict\033[0m\n'
fresh_world
set_runs '{"id":'"$RUN_ID"',"conclusion":"success","created_at":"2026-10-06T00:10:00Z"}' \
  '{"id":9,"conclusion":null,"created_at":"2026-10-06T00:50:00Z"}'
decide
[ "$SKIP" = "true" ] && ok "skips: only finished runs count" || bad "skip='$SKIP' — $OUTPUT"

printf '\n\033[1mA green run whose jobs were all skipped (a draft)\033[0m\n'
fresh_world
printf '{"jobs":[{"name":"Detect changes","conclusion":"skipped"},{"name":"API tests","conclusion":"skipped"}]}' \
  > "$work/gh/jobs-$RUN_ID.json"
decide
runs_ci "a run that tested nothing" "did not run its jobs"

printf '\n\033[1mThe API does not answer: the suite runs\033[0m\n'
fresh_world
touch "$work/gh/gh-down"
decide
runs_ci "API down" "could not list"
fresh_world
rm "$work/gh/jobs-$RUN_ID.json"
decide
runs_ci "jobs unreadable" "could not read the jobs"
fresh_world
rm "$work/gh/commit-$HEAD_SHA.json"
decide
runs_ci "head commit unreadable" "could not read the tree"

printf '\n\033[1mNo CI SHA: refused\033[0m\n'
fresh_world
decide ""
[ "$STATUS" -ne 0 ] && ok "fails" || bad "accepted an empty SHA"

printf '\n'
if [ "$failures" -gt 0 ]; then
  printf '\033[31m%d failing case(s)\033[0m\n' "$failures"
  exit 1
fi
printf '\033[32mAll cases pass\033[0m\n'
