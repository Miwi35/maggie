#!/usr/bin/env bash
set -euo pipefail

# =============================================================================
# What the CD publishes of the Android app, and since when (MAG-254)
#
# Usage:
#   mobile-release.sh range <head-sha>
#       prints `mobile_base=<sha or empty>` and `mobile=true|false`, ready for
#       $GITHUB_OUTPUT: does the app need a new publication at <head-sha>?
#   mobile-release.sh notes <head-sha> [<base-sha>]
#       prints the release notes: the subject (= the PR title) of every commit
#       that touched mobile/ since <base-sha>, one per line, capped for Firebase.
#
# The record of a publication is the tag `mobile-<versionCode>` the publish job
# creates once Firebase has accepted the build, so a failed upload leaves no
# tag and the next merge that touches mobile/ publishes everything again. The
# base is the commit of the newest tag; without one (the first publication, or
# a tag that is not in this history) the last commit alone is compared.
# =============================================================================

NOTES_MAX_CHARS=4000

latest_base() {
  local tag sha
  tag=$(git tag --list 'mobile-[0-9]*' --sort=-version:refname | sed -n 1p)
  [ -n "$tag" ] || return 0
  sha=$(git rev-parse --verify --quiet "${tag}^{commit}") || return 0
  printf '%s' "$sha"
}

case "${1:-}" in
  range)
    head="${2:?usage: mobile-release.sh range <head-sha>}"
    base=$(latest_base)
    if [ -z "$base" ] || ! git merge-base --is-ancestor "$base" "$head" 2>/dev/null; then
      base=""
      compare=$(git rev-parse --verify --quiet "${head}~1" || git hash-object -t tree /dev/null)
    else
      compare="$base"
    fi
    if git diff --name-only "$compare" "$head" | grep -q '^mobile/'; then changed=true; else changed=false; fi
    echo "mobile_base=$base"
    echo "mobile=$changed"
    ;;
  notes)
    head="${2:?usage: mobile-release.sh notes <head-sha> [<base-sha>]}"
    base="${3:-}"
    if [ -n "$base" ] && git merge-base --is-ancestor "$base" "$head" 2>/dev/null; then
      range="$base..$head"
    else
      range="${head}~1..$head"
      git rev-parse --verify --quiet "${head}~1" >/dev/null || range="$head"
    fi
    notes=$(git log --format='- %s' "$range" -- mobile/ | head -c "$NOTES_MAX_CHARS" || true)
    printf '%s\n' "${notes:-- ${head:0:7}}"
    ;;
  *)
    echo "usage: mobile-release.sh range <head-sha> | notes <head-sha> [<base-sha>]" >&2
    exit 2
    ;;
esac
