#!/usr/bin/env bash
#
# Two red CI runs in a row on a pull request: the agent gives up (MAG-128).
#
# Reads on stdin the CI runs of the PR's branch, newest first:
#   gh run list --workflow CI --branch <branch> --event pull_request \
#     --json conclusion,headSha --limit 20 | scripts/agent-guard/ci-streak.sh
#
# Prints the commits of the red runs and exits 10 when the two latest commits
# that got a verdict are both red; exits 0 otherwise. A cancelled run (a newer
# push superseded it) has no verdict, and a commit counts once however many
# times CI ran on it.

set -euo pipefail

reds="$(jq -r '
  [.[] | select(.conclusion == "success" or .conclusion == "failure")]
  | reduce .[] as $r ([]; if any(.[]; .headSha == $r.headSha) then . else . + [$r] end)
  | .[:2] | if length == 2 and all(.[]; .conclusion == "failure") then .[].headSha else empty end
')"

[ -z "$reds" ] && exit 0
echo "$reds"
exit 10
