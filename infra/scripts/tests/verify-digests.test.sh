#!/usr/bin/env bash
# shellcheck disable=SC2015 # `cond && ok || bad`: ok and bad cannot fail
#
# Tests of infra/scripts/verify-digests.sh against a fake kubectl (MAG-96).
#
# The assertion is the last line of defence against the outage that was fixed
# eleven times: a deploy that goes green while a service runs a stale image.
# What matters is that it goes red for that, and only for that.
#
# Usage: infra/scripts/tests/verify-digests.test.sh

set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
VERIFY="$HERE/../verify-digests.sh"
failures=0

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

ok()  { printf '  \033[32m✓\033[0m %s\n' "$1"; }
bad() { printf '  \033[31m✗\033[0m %s\n' "$1"; failures=$((failures + 1)); }

h() { printf '%64s' '' | tr ' ' "$1"; }
OLD="sha256:$(h a)"
NEW="sha256:$(h b)"
STALE="sha256:$(h c)"
REPO=ghcr.io/miwi35/maggie

# pod <app> <name> <digest> [deleting] — one running pod of <image>.
pod() {
  local app="$1" name="$2" digest="$3" deleting="${4:-}" image
  case "$app" in
    php | worker | cron) image=php ;;
    *) image="$app" ;;
  esac
  echo "$name|$deleting|$REPO-$image@$digest" >> "$work/kube/pods-$app"
}

# fresh_world — every workload on $OLD, and $OLD recorded before the deploy.
fresh_world() {
  rm -rf "$work/kube" "$work/state"
  mkdir -p "$work/kube" "$work/state"
  : > "$work/kube/calls"
  local app
  for app in php worker cron nginx agent ciqual; do
    pod "$app" "$app-1" "$OLD"
  done
  for app in php nginx agent ciqual; do
    echo "$REPO-$app $OLD" >> "$work/state/pre-deploy-digests"
  done
}

run_verify() {
  OUTPUT="$(FAKE_KUBECTL_DIR="$work/kube" KUBECTL="$HERE/fake-kubectl.sh" \
    MAGGIE_STATE_DIR="$work/state" EXPECTED_DIGESTS="$1" "$VERIFY" 2>&1)"
  STATUS=$?
}

set_digest() { # <app> <digest>: the app's single pod now runs <digest>
  local app="$1"
  rm -f "$work/kube/pods-$app"
  pod "$app" "$app-2" "$2"
}

printf '\n\033[1mNothing rebuilt, nothing moved\033[0m\n'
fresh_world
run_verify ""
[ "$STATUS" -eq 0 ] && ok "passes" || bad "exit $STATUS — $OUTPUT"

printf '\n\033[1mEvery built service runs what was built\033[0m\n'
fresh_world
for app in php worker cron; do set_digest "$app" "$NEW"; done
set_digest nginx "$NEW"
run_verify "php=$NEW nginx=$NEW agent= ciqual="
[ "$STATUS" -eq 0 ] && ok "passes, php image on php, worker and cron alike" || bad "exit $STATUS — $OUTPUT"

printf '\n\033[1mThe series: a service redeployed on a stale image\033[0m\n'
fresh_world
for app in php worker cron nginx; do set_digest "$app" "$NEW"; done
set_digest agent "$STALE"
run_verify "php=$NEW nginx=$NEW agent=$NEW"
[ "$STATUS" -ne 0 ] && ok "fails" || bad "passed although agent runs a stale image"
printf '%s' "$OUTPUT" | grep -qF 'agent (agent-2) runs' && ok "names the deployment and the pod" || bad "does not say which service drifted — $OUTPUT"

printf '\n\033[1mThe worker runs a different php image than the php pod\033[0m\n'
fresh_world
for app in php cron nginx; do set_digest "$app" "$NEW"; done
run_verify "php=$NEW nginx=$NEW"
[ "$STATUS" -ne 0 ] && ok "fails: worker was left behind" || bad "passed although worker still runs the old image"

printf '\n\033[1mA service nothing rebuilt must stay where it was\033[0m\n'
fresh_world
set_digest ciqual "$STALE"
run_verify ""
[ "$STATUS" -ne 0 ] && ok "fails when ciqual drifted on its own" || bad "passed although ciqual changed without a build"

printf '\n\033[1mOne old pod out of two is enough to fail\033[0m\n'
fresh_world
pod php php-3 "$NEW"
run_verify "php=$NEW"
[ "$STATUS" -ne 0 ] && ok "fails" || bad "passed with php-1 still on the old image"

printf '\n\033[1mA pod on its way out is not counted\033[0m\n'
fresh_world
for app in php worker cron; do set_digest "$app" "$NEW"; done
pod php php-old "$OLD" "2026-10-01T00:00:00Z"
run_verify "php=$NEW"
[ "$STATUS" -eq 0 ] && ok "passes" || bad "exit $STATUS — $OUTPUT"

printf '\n\033[1mNo running pod is a failure\033[0m\n'
fresh_world
rm -f "$work/kube/pods-agent"
run_verify ""
[ "$STATUS" -ne 0 ] && ok "fails" || bad "passed with no agent pod at all"

printf '\n\033[1mNo baseline: says so instead of guessing\033[0m\n'
fresh_world
rm -f "$work/state/pre-deploy-digests"
run_verify "php=$NEW"
printf '%s' "$OUTPUT" | grep -qF 'not checked' && ok "warns about the unchecked services" || bad "silent about a service it did not check"

printf '\n\033[1mA malformed digest is refused\033[0m\n'
fresh_world
run_verify "php=latest"
[ "$STATUS" -ne 0 ] && ok "fails" || bad "accepted 'latest' as a digest"

printf '\n'
if [ "$failures" -gt 0 ]; then
  printf '\033[31m%d failing case(s)\033[0m\n' "$failures"
  exit 1
fi
printf '\033[32mAll cases pass\033[0m\n'
