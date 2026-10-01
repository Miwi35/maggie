#!/usr/bin/env bash
set -euo pipefail
#
# The ambulance may merge its own infra fix (MAG-184).
#
# Reads the findings of check.sh on stdin. When the only ones that need a human
# are `infra-path` and the PR's branch (`cyrus/mag-n-…`) belongs to a ticket in
# the Linear state « Emergency », prints the findings with those waived (as
# `info:` lines) and exits 0: the PR may merge itself. Otherwise exits 1 with
# the reason on stderr, and the PR goes to a human as before.
#
# Never waived: secrets, permissions, policy.yaml, the guard itself, a
# destructive migration, a disabled test, skipped hooks, oversize,
# AGENT_ENABLED=false. Fails closed: no key, or no answer from Linear, no waiver.
#
#   scripts/agent-guard/emergency.sh cyrus/mag-184-fix-the-deploy < findings.txt

branch="${1:?usage: emergency.sh <branch> < findings}"
findings=$(cat)
no() { echo "no Emergency waiver: $*" >&2; exit 1; }

blocking=$(grep -v '^info: ' <<<"$findings" || true)
[ -n "$blocking" ] || no "nothing to waive"
if grep -qv '^infra-path: ' <<<"$blocking"; then
  no "a finding other than infra-path needs a human"
fi

[[ "$branch" =~ ^cyrus/(mag-[0-9]+)(-|$) ]] || no "$branch is not an agent ticket branch"
key=$(tr '[:lower:]' '[:upper:]' <<<"${BASH_REMATCH[1]}")

[ -n "${LINEAR_API_KEY:-}" ] || no "LINEAR_API_KEY is not set"
# shellcheck source=infra/scripts/linear.sh
. "$(dirname "${BASH_SOURCE[0]}")/../../infra/scripts/linear.sh"

state=$(graphql 'query($id: String!) { issue(id: $id) { state { name } } }' "$(jq -n --arg id "$key" '{id: $id}')" \
  | jq -r '.data.issue.state.name // empty') || no "Linear did not say where $key is"
[ "$state" = "$EMERGENCY_STATE" ] || no "$key is in « ${state:-unknown} », not « $EMERGENCY_STATE »"

grep -v '^infra-path: ' <<<"$findings" || true
sed -n "s/^infra-path: /info: infra-path waived, $key is in « $EMERGENCY_STATE »: /p" <<<"$blocking"
