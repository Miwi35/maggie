#!/usr/bin/env bash
# shellcheck disable=SC2015 # `cond && ok || bad`: ok and bad cannot fail
#
# Tests of scripts/agent-guard/emergency.sh against a fake curl (MAG-184): the
# fix of a ticket in « Emergency » may merge its infra change alone, nothing
# else is waived, and any doubt hands the PR to a human.
#
# Usage: infra/scripts/tests/agent-guard-emergency.test.sh

set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SCRIPT="$HERE/../../../scripts/agent-guard/emergency.sh"
failures=0

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
mkdir -p "$work/bin" "$work/api"
ln -s "$HERE/fake-curl.sh" "$work/bin/curl"

ok()  { printf '  \033[32m✓\033[0m %s\n' "$1"; }
bad() { printf '  \033[31m✗\033[0m %s\n' "$1"; failures=$((failures + 1)); }

# ticket_in <key> <state>
ticket_in() { printf '{"data":{"issue":{"state":{"name":"%s"}}}}' "$2" > "$work/api/issue-$1"; }

# waive <branch> <findings>
waive() {
  : > "$work/api/requests"
  OUTPUT="$(PATH="$work/bin:$PATH" FAKE_CURL_DIR="$work/api" LINEAR_API_KEY="${KEY-lin_api_secret}" \
    "$SCRIPT" "$1" <<<"$2" 2>&1)"
  STATUS=$?
}
calls() { wc -l < "$work/api/requests"; }

INFRA=$'infra-path: infra/k8s/php-deployment.yaml\ninfra-path: .github/workflows/main.yml\ninfo: 12 counted lines, limit 800'
ticket_in MAG-184 Emergency
ticket_in MAG-150 "In Progress"

printf '\n\033[1mThe fix of an « Emergency » ticket merges its infra change alone\033[0m\n'
waive cyrus/mag-184-fix-the-deploy "$INFRA"
[ "$STATUS" -eq 0 ] && ok "waived" || bad "exit $STATUS — $OUTPUT"
! grep -q '^infra-path: ' <<<"$OUTPUT" && ok "no infra-path left" || bad "left: $OUTPUT"
[ "$(grep -c '^info: infra-path waived, MAG-184 is in « Emergency »' <<<"$OUTPUT")" -eq 2 ] && ok "each waiver traced as info" || bad "trace: $OUTPUT"
grep -qx 'info: 12 counted lines, limit 800' <<<"$OUTPUT" && ok "keeps the other info" || bad "lost: $OUTPUT"
jq -e '.variables.id == "MAG-184"' "$work/api/requests" >/dev/null && ok "asks Linear for MAG-184" || bad "asked: $(cat "$work/api/requests")"
waive cyrus/mag-184 "$INFRA"
[ "$STATUS" -eq 0 ] && ok "a bare cyrus/mag-184 branch too" || bad "exit $STATUS — $OUTPUT"

printf '\n\033[1mAnything else still needs a human\033[0m\n'
waive cyrus/mag-150-recipe-filter "$INFRA"
[ "$STATUS" -ne 0 ] && ok "a ticket not in « Emergency »" || bad "waived: $OUTPUT"
echo "$OUTPUT" | grep -q 'MAG-150 is in « In Progress »' && ok "says where it is" || bad "unclear: $OUTPUT"
for other in 'sensitive-path: .github/workflows/main.yml: uses a secret' 'permissions: .github/workflows/main.yml:   contents: write' \
  'sensitive-path: scripts/agent-guard/check.sh' 'sensitive-path: agent/data/policy.yaml' \
  'destructive-migration: api/migrations/Version1.php: DROP TABLE x' 'disabled-test: api/tests/FooTest.php' \
  'no-verify: scripts/x.sh' 'oversize: 900 lines outside tests, limit 800: split the ticket' \
  'agent-disabled: AGENT_ENABLED is false, the autonomous agent is stopped'; do
  waive cyrus/mag-184-fix-the-deploy "$INFRA"$'\n'"$other"
  [ "$STATUS" -ne 0 ] && [ "$(calls)" -eq 0 ] && ok "never waived, Linear not even asked: ${other%%:*}" || bad "waived past '$other': $OUTPUT"
done
waive meven35/mag-184-fix "$INFRA"
[ "$STATUS" -ne 0 ] && ok "a human branch is not an agent ticket" || bad "waived: $OUTPUT"
waive cyrus/mag-184-fix-the-deploy $'info: 3 counted lines, limit 800'
[ "$STATUS" -ne 0 ] && ok "nothing to waive: no waiver" || bad "exit $STATUS"

printf '\n\033[1mEnd to end, from a diff\033[0m\n'
CHECK="$HERE/../../../scripts/agent-guard/check.sh"
diff_of() { printf 'diff --git a/%s b/%s\n--- a/%s\n+++ b/%s\n@@ -1 +1 @@\n+%s\n' "$1" "$1" "$1" "$1" "$2"; }
waive cyrus/mag-184-fix-the-deploy "$(diff_of infra/k8s/php-deployment.yaml 'image: x' | "$CHECK")"
[ "$STATUS" -eq 0 ] && ok "a k8s manifest fix is waived" || bad "exit $STATUS — $OUTPUT"
waive cyrus/mag-184-fix-the-deploy "$( { diff_of .github/workflows/main.yml '    secrets: inherit'; } | "$CHECK")"
[ "$STATUS" -ne 0 ] && ok "a workflow reaching for secrets is not" || bad "waived: $OUTPUT"
waive cyrus/mag-184-fix-the-deploy "$(diff_of infra/scripts/incident-gate.sh 'exit 0' | "$CHECK")"
[ "$STATUS" -ne 0 ] && ok "loosening the freeze is not" || bad "waived: $OUTPUT"

printf '\n\033[1mFails closed\033[0m\n'
KEY='' waive cyrus/mag-184-fix-the-deploy "$INFRA"
[ "$STATUS" -ne 0 ] && ok "no API key: no waiver" || bad "waived without a key"
rm "$work/api/issue-MAG-184"
waive cyrus/mag-184-fix-the-deploy "$INFRA"
[ "$STATUS" -ne 0 ] && ok "Linear error: no waiver" || bad "waived on an error: $OUTPUT"
printf '%s' 'not json' > "$work/api/issue-MAG-184"
waive cyrus/mag-184-fix-the-deploy "$INFRA"
[ "$STATUS" -ne 0 ] && ok "garbage: no waiver" || bad "waived on garbage: $OUTPUT"

printf '\n'
[ "$failures" -eq 0 ] && echo "All agent-guard emergency tests passed." || echo "$failures failure(s)."
exit "$failures"
