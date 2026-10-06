#!/usr/bin/env bash
#
# Two red CI runs in a row on a pull request: the agent gives up (MAG-128).
#
# Reads on stdin the CI runs of the PR's branch, newest first:
#   gh run list --workflow ci.yml --branch <branch> --event pull_request \
#     --json conclusion,headSha,displayTitle --limit 20 | scripts/agent-guard/ci-streak.sh
#
# Prints the commits of the red runs and exits 10 when the two latest commits
# that got a verdict are both red; exits 0 otherwise. A cancelled run (a newer
# push superseded it) has no verdict, and a commit counts once however many
# times CI ran on it. The run of a draft (run-name ending ` · draft`) has none
# either: its `PR checks passed` is red by design (MAG-267), whatever the code does.

set -euo pipefail

reds="$(jq -r '
  [.[] | select((.conclusion == "success" or .conclusion == "failure")
                and ((.displayTitle // "") | endswith(" · draft") | not))]
  | reduce .[] as $r ([]; if any(.[]; .headSha == $r.headSha) then . else . + [$r] end)
  | .[:2] | if length == 2 and all(.[]; .conclusion == "failure") then .[].headSha else empty end
')"

[ -z "$reds" ] && exit 0
echo "$reds"
exit 10
