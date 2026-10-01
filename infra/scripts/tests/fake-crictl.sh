#!/usr/bin/env bash
# A crictl that knows the aliases of an image for verify-digests.test.sh.
# `inspecti -o json <repo>@<digest>` answers with the repoDigests of the image:
# the digest asked for, plus every `<digest> <alias>` pair listed in
# $FAKE_KUBECTL_DIR/aliases. The output carries a layer digest and a config
# digest too, like the real one, which must never be taken for an index.
set -euo pipefail

dir="${FAKE_KUBECTL_DIR:?}"
echo "crictl $*" >> "$dir/calls"

[ -f "$dir/crictl-down" ] && { echo "crictl: connection refused" >&2; exit 1; }
[ "$1 $2 $3" = "inspecti -o json" ] || { echo "fake-crictl: unexpected call: $*" >&2; exit 2; }

ref="$4"
repo="${ref%@*}"
digest="${ref#*@}"

digests=("$repo@$digest")
if [ -f "$dir/aliases" ]; then
  while read -r own alias; do
    [ "$own" = "$digest" ] && digests+=("$repo@$alias")
  done < "$dir/aliases"
fi

printf '{"status":{"id":"sha256:%064d","repoDigests":[' 1
sep=""
for d in "${digests[@]}"; do printf '%s"%s"' "$sep" "$d"; sep=","; done
printf ']},"info":{"imageSpec":{"rootfs":{"diff_ids":["sha256:%064d"]}}}}\n' 2
