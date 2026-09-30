#!/usr/bin/env bash
# A kubectl that knows just enough about deployment revisions for
# rollback-k3s.test.sh. State lives in $FAKE_KUBECTL_DIR: one `rev-<deployment>`
# file per deployment, and a `calls` log.
set -euo pipefail

dir="${FAKE_KUBECTL_DIR:?}"
echo "$*" >> "$dir/calls"

deploy_of() { echo "${1#deployment/}"; }

case "$1 $2" in
  "get deployment/"*)
    file="$dir/rev-$(deploy_of "$2")"
    [ -f "$file" ] && cat "$file"
    exit 0
    ;;
  "get pods") echo "fake pods" ;;
  "rollout undo")
    name="$(deploy_of "$3")"
    if [ -f "$dir/fail-undo-$name" ]; then
      echo "error: unable to find specified revision" >&2
      exit 1
    fi
    # Like the real thing: undoing creates a new revision number.
    echo $(( $(cat "$dir/rev-$name") + 10 )) > "$dir/rev-$name"
    ;;
  "rollout status") ;;
  *) echo "fake-kubectl: unexpected call: $*" >&2; exit 2 ;;
esac
