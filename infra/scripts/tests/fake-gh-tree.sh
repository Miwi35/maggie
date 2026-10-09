#!/usr/bin/env bash
# A gh that stands in for the GitHub API in tested-tree.test.sh. Every answer is a
# file of $FAKE_GH_DIR; a missing file is a 404, `gh-down` fails every call.
#   pulls-<sha>.json     commits/<sha>/pulls
#   commit-<sha>.json    git/commits/<sha>
#   runs-<head-sha>.json actions/workflows/ci.yml/runs?head_sha=<head-sha>&event=pull_request…
#   group-runs-<sha>.json  actions/workflows/ci.yml/runs?head_sha=<sha>&event=merge_group…
#   jobs-<run-id>.json   actions/runs/<run-id>/jobs
set -euo pipefail

dir="${FAKE_GH_DIR:?}"
echo "$*" >> "$dir/calls"

[ ! -f "$dir/gh-down" ] || { echo "gh: HTTP 502" >&2; exit 1; }
[ "$1" = "api" ] || { echo "fake-gh-tree: unexpected call: $*" >&2; exit 2; }
path="$2"

case "$path" in
  */commits/*/pulls) file="pulls-$(sed -n 's|.*/commits/\([0-9a-f]*\)/pulls|\1|p' <<<"$path").json" ;;
  */git/commits/*) file="commit-${path##*/}.json" ;;
  */actions/workflows/ci.yml/runs\?*)
    # A script that forgets a filter would be told about runs it did not ask for.
    sha="$(sed -n 's/.*[?&]head_sha=\([0-9a-f]*\).*/\1/p' <<<"$path")"
    case "$path" in
      *"event=pull_request"*) file="runs-$sha.json" ;;
      *"event=merge_group"*) file="group-runs-$sha.json" ;;
      *) echo "fake-gh-tree: no event filter: $path" >&2; exit 2 ;;
    esac
    ;;
  */actions/runs/*/jobs\?*) file="jobs-$(sed -n 's|.*/actions/runs/\([0-9]*\)/jobs.*|\1|p' <<<"$path").json" ;;
  *) echo "fake-gh-tree: unexpected path: $path" >&2; exit 2 ;;
esac

[ -f "$dir/$file" ] || { echo "gh: HTTP 404 ($file)" >&2; exit 1; }
cat "$dir/$file"
