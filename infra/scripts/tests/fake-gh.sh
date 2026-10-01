#!/usr/bin/env bash
# A gh that stands in for the GitHub API in should-deploy.test.sh. State lives in
# $FAKE_GH_DIR: `main-sha` (the head of main), `deployed-shas` (one SHA per
# successful CD run) and a `calls` log. `gh-down` makes every call fail.
set -euo pipefail

dir="${FAKE_GH_DIR:?}"
echo "$*" >> "$dir/calls"

[ ! -f "$dir/gh-down" ] || { echo "gh: HTTP 502" >&2; exit 1; }
[ "$1" = "api" ] || { echo "fake-gh: unexpected call: $*" >&2; exit 2; }
path="$2"

case "$path" in
  */commits/main)
    printf '{"sha":"%s"}\n' "$(cat "$dir/main-sha")"
    ;;
  */actions/workflows/cd.yml/runs\?*)
    # Honours the filters the real API honours; a script that forgets one gets
    # an answer about every run, not about the one it asked for.
    count=0
    if [[ "$path" == *"status=success"* ]]; then
      sha="$(sed -n 's/.*[?&]head_sha=\([0-9a-f]*\).*/\1/p' <<<"$path")"
      count=$(grep -c "^${sha:-.}" "$dir/deployed-shas" || true)
    fi
    printf '{"total_count":%s,"workflow_runs":[]}\n' "$count"
    ;;
  *) echo "fake-gh: unexpected path: $path" >&2; exit 2 ;;
esac
