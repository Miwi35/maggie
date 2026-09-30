#!/usr/bin/env bash
#
# The CI dependency cache, locally.
#
# `ci.yml` keys `api/vendor` on `hashFiles('api/composer.lock')` and
# `admin/node_modules` on the package-lock. This does the same on this machine,
# in one place shared by every worktree: install once per lockfile, then mount
# the result read-only into whatever needs it. An agent opening a new worktree
# gets a working tree in seconds instead of a two-minute install, and two
# agents on the same lockfile share one copy.
#
# Filling is atomic — install into a temp directory, then `mv` — so two agents
# starting at once cannot read a half-written cache. `mv` on the same
# filesystem is a rename; the loser of the race deletes its own copy.
#
# Usage:
#   wt/deps-cache.sh api <php-image>   # prints the vendor directory
#   wt/deps-cache.sh admin <node-image>  # prints the node_modules directory
#   wt/deps-cache.sh prune
#
# Prints the cache directory on stdout; everything else goes to stderr, so the
# caller can capture it with $(...).

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CACHE_ROOT="${WT_CACHE_ROOT:-${XDG_CACHE_HOME:-$HOME/.cache}/maggie}"

# Keep this many entries per component, and drop anything untouched for this
# long. A changed lockfile simply makes a new entry, exactly like a new CI
# cache key.
KEEP_PER_COMPONENT="${WT_CACHE_KEEP:-3}"
KEEP_DAYS="${WT_CACHE_KEEP_DAYS:-14}"

log() { printf '%s\n' "$*" >&2; }

# Set by prepare() while a fill is in flight. The trap is at script scope
# rather than a RETURN trap inside the function, because `set -e` tears the
# shell down without the function ever returning — so a failed install (a
# registry blip, an OOM kill from the memory cap) used to leave a partial
# vendor tree of several hundred MB behind, forever.
TMP_IN_FLIGHT=""
cleanup() {
    # `return 0` matters: as the last command of an EXIT trap, a false test
    # would become the script's exit status and fail an otherwise fine run.
    if [ -n "$TMP_IN_FLIGHT" ]; then
        rm -rf "$TMP_IN_FLIGHT"
    fi
    return 0
}
trap cleanup EXIT

# Tool caches (Composer's, npm's, uv's) live beside the dependency caches, as
# host directories rather than named volumes: Docker creates a named volume
# owned by root, and every container here runs as the host user, so the tool
# cannot write to it. Creating them ourselves gets the ownership right.
mkdir -p "$CACHE_ROOT/composer" "$CACHE_ROOT/npm" "$CACHE_ROOT/uv"

hash_of() {
    sha256sum "$1" | cut -c1-16
}

# prepare <component> <key> <fill-command...>
#
# Fills $CACHE_ROOT/<component>/<key> if missing, touches it, prints its path.
prepare() {
    local component="$1" key="$2"
    shift 2

    local dir="$CACHE_ROOT/$component/$key"

    if [ -d "$dir" ]; then
        touch "$dir"
        log "Cache hit: $component/$key"
        printf '%s\n' "$dir"
        return 0
    fi

    mkdir -p "$CACHE_ROOT/$component"
    local tmp
    tmp="$(mktemp -d "$CACHE_ROOT/$component/.tmp-XXXXXX")"
    TMP_IN_FLIGHT="$tmp"

    log "Cache miss: $component/$key — filling once, shared by every worktree"
    "$@" "$tmp"

    # Atomic against another agent doing the same thing: whoever renames first
    # wins, the other drops its copy. `mv -T` fails and leaves the source in
    # place when the destination exists and is non-empty, so the loser has to
    # delete its own tree — clearing TMP_IN_FLIGHT without that would hand the
    # trap nothing to clean and leak a whole vendor copy.
    if mv -T "$tmp" "$dir" 2>/dev/null; then
        log "Cache filled: $dir"
    else
        log "Another run filled $component/$key first — dropping our copy"
        rm -rf "$tmp"
    fi

    TMP_IN_FLIGHT=""
    printf '%s\n' "$dir"
}

# api/composer.json declares the six bundles as `path` repositories with
# `symlink: true`, so Composer needs them present to install — and what lands
# in vendor/ is a relative symlink (`vendor/maggie/core -> ../../modules/core`).
# That is what makes one shared cache usable from every worktree: the link
# resolves against whichever /app the container mounts, not against the
# worktree the cache was filled from.
fill_api() {
    local image="$1" target="$2"

    docker run --rm \
        --memory "${WT_MEMORY:-2g}" --cpus "${WT_CPUS:-4}" \
        -u "$(id -u):$(id -g)" \
        -e COMPOSER_HOME=/tmp/composer \
        -v "$REPO_ROOT/api/composer.json":/app/composer.json:ro \
        -v "$REPO_ROOT/api/composer.lock":/app/composer.lock:ro \
        -v "$REPO_ROOT/api/modules":/app/modules:ro \
        -v "$target":/app/vendor \
        -v "$CACHE_ROOT/composer":/tmp/composer \
        -w /app \
        --entrypoint sh "$image" \
        -c 'composer install --no-interaction --no-progress --no-scripts -q' >&2
}

fill_admin() {
    local image="$1" target="$2"

    docker run --rm \
        --memory "${WT_MEMORY:-2g}" --cpus "${WT_CPUS:-4}" \
        -u "$(id -u):$(id -g)" \
        -e HOME=/tmp -e npm_config_cache=/tmp/npm-cache -e npm_config_update_notifier=false \
        -v "$REPO_ROOT/admin/package.json":/app/package.json:ro \
        -v "$REPO_ROOT/admin/package-lock.json":/app/package-lock.json:ro \
        -v "$target":/app/node_modules \
        -v "$CACHE_ROOT/npm":/tmp/npm-cache \
        -w /app "$image" \
        sh -c '
          npm ci --no-audit --no-fund
          # Vite writes into node_modules/.vite-temp while loading
          # vite.config.ts, and caches there too — both paths are hard-coded
          # relative to the project root. The cache is mounted read-only, so
          # the task covers them with a tmpfs; Docker can only do that if the
          # mount points already exist.
          mkdir -p node_modules/.vite-temp node_modules/.vite
        ' >&2
}

prune() {
    local component dir index
    local dirs=()

    for component in api admin; do
        [ -d "$CACHE_ROOT/$component" ] || continue

        # Leftovers from a fill that was killed outright. They are plain
        # directories, so without this they would occupy keep slots ahead of
        # real entries.
        #
        # -mmin +60, not all of them: another agent's install may be running
        # right now, and deleting its bind-mount source mid-run would fail it
        # for a reason it could never explain. An hour is far longer than any
        # install here takes.
        while IFS= read -r dir; do
            log "Removing partial fill $dir"
            rm -rf "$dir"
        done < <(find "$CACHE_ROOT/$component" -mindepth 1 -maxdepth 1 -type d -name '.tmp-*' -mmin +60)

        # Newest first, so the keep count is the most recently used ones.
        dirs=()
        while IFS= read -r dir; do
            dirs+=("$dir")
        done < <(find "$CACHE_ROOT/$component" -mindepth 1 -maxdepth 1 -type d -not -name '.tmp-*' \
            -printf '%T@ %p\n' | sort -rn | cut -d' ' -f2-)

        index=0
        for dir in ${dirs[@]+"${dirs[@]}"}; do
            index=$((index + 1))
            if [ "$index" -le "$KEEP_PER_COMPONENT" ]; then
                continue
            fi
            if [ -n "$(find "$dir" -maxdepth 0 -mtime "+$KEEP_DAYS")" ]; then
                log "Pruning $dir"
                rm -rf "$dir"
            fi
        done
    done
}

command="${1:?usage: deps-cache.sh api|admin|prune [image]}"

case "$command" in
    api)
        image="${2:?an image is required}"
        # The key names the runtime as well as the lockfile. Hashing the
        # Dockerfile catches a PHP bump or an extension change, either of
        # which can make a vendor tree installed under the old runtime wrong —
        # and does it without needing the image to exist yet.
        runtime="$(hash_of "$REPO_ROOT/.docker/php/Dockerfile")"
        # composer.json is part of the key, not just the lockfile: the
        # generated autoloader is built from it, so adding a PSR-4 namespace
        # changes vendor/ without changing a single dependency. Keyed on the
        # lockfile alone, a new test namespace resolved in CI (which installs
        # from scratch) and nowhere else — a "class not found" that no amount
        # of reading the diff explains.
        manifest="$(hash_of "$REPO_ROOT/api/composer.json")"
        prepare api "php-$runtime-$(hash_of "$REPO_ROOT/api/composer.lock")-$manifest" fill_api "$image"
        ;;
    admin)
        image="${2:?an image is required}"
        node_tag="$(printf '%s' "$image" | tr -c 'a-zA-Z0-9' '-')"
        prepare admin "$node_tag-$(hash_of "$REPO_ROOT/admin/package-lock.json")" fill_admin "$image"
        ;;
    prune)
        prune
        ;;
    *)
        log "Unknown command: $command"
        exit 64
        ;;
esac
