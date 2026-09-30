#!/usr/bin/env bash
#
# The PHP image the worktree tasks run in, named after what it is built from
# (MAG-137).
#
# Same recipe as CI (e2e/images.sh, MAG-135): the tag is the hash of everything
# the Dockerfile reads plus the resolved build stanza, so the image is rebuilt
# only when `.docker/php/**` (or a build arg) changes — never per test run.
#
#   wt/image.sh tag      print maggie-wt-php:<hash> (no side effect)
#   wt/image.sh ensure   make sure that tag exists locally: already there →
#                        nothing; else pull CI's image for the same hash and
#                        retag it; else build it
#   wt/image.sh prune    drop the other maggie-wt-php tags (unused ones only)
#
# The hash covers the host's UID and GID, which are baked into the image's
# `app` user. CI pushes under its own runner's uid, so a pull only hits when the
# two happen to match; the build is the normal path on a laptop and is cached.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
IMAGES="$ROOT/e2e/images.sh"
REPOSITORY='maggie-wt-php'
CACHE_ROOT="${WT_CACHE_ROOT:-${XDG_CACHE_HOME:-$HOME/.cache}/maggie}"

hash="$("$IMAGES" hash php)"
tag="$REPOSITORY:$hash"

ensure() {
    docker image inspect "$tag" >/dev/null 2>&1 && return 0

    # Two agents on the same new hash must not build it twice. The second waits,
    # then finds the image.
    mkdir -p "$CACHE_ROOT"
    exec 9>"$CACHE_ROOT/wt-image-$hash.lock"
    flock 9
    docker image inspect "$tag" >/dev/null 2>&1 && return 0

    local ci_ref
    ci_ref="$("$IMAGES" ref php)"
    if timeout 120 docker pull --quiet "$ci_ref" >/dev/null 2>&1; then
        docker tag "$ci_ref" "$tag"
        echo "wt image: pulled $ci_ref" >&2
        return 0
    fi

    echo "wt image: $tag not found locally or in CI, building it" >&2
    # Through the e2e Compose file so the build is exactly the stanza the hash
    # was computed from; E2E_IMAGE_PHP only renames the result.
    env UID="$(id -u)" GID="$(id -g)" E2E_IMAGE_PHP="$tag" \
        docker compose -f "$ROOT/docker-compose.e2e.yml" build --quiet php >/dev/null
}

prune() {
    local image
    while read -r image; do
        [ "$image" = "$tag" ] || docker rmi "$image" >/dev/null 2>&1 || true
    done < <(docker image ls "$REPOSITORY" --format '{{.Repository}}:{{.Tag}}')
}

case "${1:-}" in
    tag) echo "$tag" ;;
    ensure) ensure ;;
    prune) prune ;;
    *) echo "usage: $0 tag|ensure|prune" >&2; exit 64 ;;
esac
