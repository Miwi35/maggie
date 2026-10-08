#!/usr/bin/env bash
# A kubectl that knows just enough about deployment revisions for
# rollback-k3s.test.sh (and the order of the calls of deploy-k3s.test.sh). State lives in $FAKE_KUBECTL_DIR: one `rev-<deployment>`
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
  "get pod")
    # `get pod -l app=<name> -o jsonpath=…`: answers with the lines of
    # `pods-<name>`, already in the `name|deletionTimestamp|imageID` shape.
    for arg in "$@"; do
      case "$arg" in
        app=*) [ -f "$dir/pods-${arg#app=}" ] && cat "$dir/pods-${arg#app=}" ;;
      esac
    done
    exit 0
    ;;
  "get secret")
    # `get secret maggie-env -o jsonpath={.data.<KEY>}`: the base64 of `secret-<KEY>`.
    for arg in "$@"; do
      case "$arg" in
        jsonpath=*) key="${arg#*.data.}"; key="${key%\}}" ;;
      esac
    done
    [ -f "$dir/secret-$key" ] && base64 -w0 "$dir/secret-$key"
    exit 0
    ;;
  "exec -n")
    # `exec -n shared <pod> -- sh -c "… pg_dump … -d '<db>'"`: the content of
    # `dump-<db>`, nothing when absent.
    db="$(printf '%s' "${*: -1}" | sed -n "s|.*-d '\([^']*\)'.*|\1|p")"
    [ -f "$dir/dump-$db" ] && cat "$dir/dump-$db"
    exit 0
    ;;
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
  "get nodes") ;;
  "get deployment") echo 1 ;;
  "apply -k") ;;
  "delete pod") ;;
  "exec deployment/"*) ;;
  "run migrate")
    # The one-shot migration pod: fails when `fail-migrate` exists.
    if [ -f "$dir/fail-migrate" ]; then
      echo "migration failed" >&2
      exit 1
    fi
    ;;
  *) echo "fake-kubectl: unexpected call: $*" >&2; exit 2 ;;
esac
