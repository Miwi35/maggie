#!/usr/bin/env bash
# shellcheck disable=SC2015 # `cond && ok || bad`: ok and bad cannot fail
#
# Tests of infra/scripts/running-digests.sh against a fake kubectl (MAG-430).
#
# The deploy pins a service it does not rebuild to the digest this prints, and
# the post-deploy assertion holds it to the same line: it must name the pod that
# serves, even while a failed revision's pod is still Running next to it.
#
# Usage: infra/scripts/tests/running-digests.test.sh

set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RUNNING="$HERE/../running-digests.sh"
failures=0

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

ok()  { printf '  \033[32m✓\033[0m %s\n' "$1"; }
bad() { printf '  \033[31m✗\033[0m %s\n' "$1"; failures=$((failures + 1)); }

h() { printf '%64s' '' | tr ' ' "$1"; }
OLD="sha256:$(h a)"
NEW="sha256:$(h b)"
REPO=ghcr.io/miwi35/maggie

# pod <app> <name> <ready> <digest> [deleting] — one Running pod, oldest first.
pod() {
  echo "$2|${5:-}|$3|$REPO-$1@$4" >> "$work/kube/pods-$1"
}

fresh_world() {
  rm -rf "$work/kube"
  mkdir -p "$work/kube"
  : > "$work/kube/calls"
  local app
  for app in php nginx ciqual; do
    pod "$app" "$app-1" true "$OLD"
  done
}

run() {
  OUTPUT="$(FAKE_KUBECTL_DIR="$work/kube" KUBECTL="$HERE/fake-kubectl.sh" "$RUNNING" 2>&1)"
  STATUS=$?
}

agent_line() { grep "^$REPO-agent " <<< "$OUTPUT" || true; }

printf '\n\033[1mOne pod per service\033[0m\n'
fresh_world
pod agent agent-1 true "$OLD"
run
[ "$STATUS" -eq 0 ] && [ "$(wc -l <<< "$OUTPUT")" -eq 4 ] && [ "$(agent_line)" = "$REPO-agent $OLD" ] \
  && ok "one line per image, the digest its pod runs" || bad "exit $STATUS — $OUTPUT"

printf '\n\033[1mThe 13 Oct. deploy: a failed revision still Running next to the sound one\033[0m\n'
fresh_world
pod agent agent-new false "$NEW"
pod agent agent-old true "$OLD"
run
[ "$(agent_line)" = "$REPO-agent $OLD" ] && ok "the ready pod wins over the newer one that is not" \
  || bad "$OUTPUT"
fresh_world
pod agent agent-old true "$OLD"
pod agent agent-new false "$NEW"
run
[ "$(agent_line)" = "$REPO-agent $OLD" ] && ok "whatever order kubectl lists them in" || bad "$OUTPUT"

printf '\n\033[1mA pod on its way out\033[0m\n'
fresh_world
pod agent agent-old true "$OLD" "2026-10-13T13:28:12Z"
pod agent agent-new true "$NEW"
run
[ "$(agent_line)" = "$REPO-agent $NEW" ] && ok "a terminating pod is ignored" || bad "$OUTPUT"

printf '\n\033[1mTwo ready pods\033[0m\n'
fresh_world
pod agent agent-old true "$OLD"
pod agent agent-new true "$NEW"
run
[ "$(agent_line)" = "$REPO-agent $NEW" ] && ok "the newest wins" || bad "$OUTPUT"

printf '\n\033[1mNo ready pod at all\033[0m\n'
fresh_world
pod agent agent-1 false "$OLD"
run
[ "$(agent_line)" = "$REPO-agent $OLD" ] && ok "the Running pod still names the digest" || bad "$OUTPUT"

printf '\n\033[1mNothing to read\033[0m\n'
fresh_world
run
[ "$STATUS" -eq 0 ] && [ -z "$(agent_line)" ] && ok "an image with no pod prints nothing" || bad "exit $STATUS — $OUTPUT"
fresh_world
echo "agent-1||true|docker.io/library/python@$NEW" >> "$work/kube/pods-agent"
run
[ -z "$(agent_line)" ] && ok "a pod on another image is not ours" || bad "$OUTPUT"

echo
if [ "$failures" -ne 0 ]; then
  echo "$failures failure(s)"
  exit 1
fi
echo "All running-digests tests passed."
