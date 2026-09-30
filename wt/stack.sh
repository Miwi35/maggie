#!/usr/bin/env bash
#
# Lifecycle of the worktree stacks kept alive between test runs (MAG-137).
#
# `task wt:up` keeps a Postgres up so the `wt:test:*` loop does not pay ~40 s of
# start-up per run. A stack nobody stops is a leak, so three things end it:
# the agent (`task wt:down`), the load guard (it prunes before measuring), and
# a detached reaper started by `wt:up`. Each applies the same rule: a stack
# with no `wt:*` launch for WT_IDLE_MINUTES goes away.
#
# "Last activity" is the mtime of a stamp file per project, touched by every
# wt task through the guard. A container label cannot do it: labels are
# immutable once the container exists.
#
# Only containers carrying the `maggie.wt.stack` label are ever touched, and a
# project name must start with `maggie-wt-` — so neither the dev stack, nor an
# e2e stack, nor another worktree's stack can be reached from here.
#
#   stack.sh running <project>      exit 0 if that stack's database is up
#   stack.sh touch <project>        record activity
#   stack.sh touch-if-running <p>   same, only when the stack exists
#   stack.sh start-reaper <project> detached watcher, one per project
#   stack.sh down <project>         remove that stack and nothing else
#   stack.sh prune                  remove every stack idle for too long
#   stack.sh count [exclude]        number of live stacks, minus one
#   stack.sh list                   live stacks and their idle time

set -euo pipefail

CACHE_ROOT="${WT_CACHE_ROOT:-${XDG_CACHE_HOME:-$HOME/.cache}/maggie}"
STATE_DIR="$CACHE_ROOT/wt-stacks"
IDLE_MINUTES="${WT_IDLE_MINUTES:-15}"
REAPER_INTERVAL="${WT_REAPER_INTERVAL_SECONDS:-60}"
LABEL='maggie.wt.stack'

require_project() {
    case "${1:-}" in
        maggie-wt-?*) ;;
        *)
            echo "stack.sh: refusing project '${1:-}': not a wt project name (maggie-wt-*)" >&2
            exit 64
            ;;
    esac
}

projects() {
    docker ps -a --filter "label=$LABEL" --format "{{.Label \"$LABEL\"}}" | sort -u
}

running() {
    require_project "$1"
    [ -n "$(docker ps -q --filter "label=$LABEL=$1" --filter 'label=com.docker.compose.service=database' --filter status=running)" ]
}

stamp() { printf '%s/%s.stamp' "$STATE_DIR" "$1"; }

touch_stamp() {
    mkdir -p "$STATE_DIR"
    touch "$(stamp "$1")"
}

# Seconds since the last activity. Without a stamp (wiped cache directory, a
# stack started by hand) the oldest container's creation time stands in, so such
# a stack still ages out instead of living forever.
idle_seconds() {
    local project="$1" ref="" created
    if [ -f "$(stamp "$project")" ]; then
        ref="$(stat -c %Y "$(stamp "$project")")"
    else
        created="$(docker ps -a --filter "label=$LABEL=$project" --format '{{.CreatedAt}}' | sort | head -n1)"
        ref="$(date -d "${created%% [A-Z]*}" +%s 2>/dev/null || echo 0)"
    fi
    echo $(($(date +%s) - ref))
}

down() {
    require_project "$1"
    local project="$1" ids
    ids="$(docker ps -aq --filter "label=$LABEL=$project")"
    if [ -n "$ids" ]; then
        # shellcheck disable=SC2086
        docker rm -f -v $ids >/dev/null
    fi
    docker network rm "${project}-net" >/dev/null 2>&1 || true
    rm -f "$(stamp "$project")" "$STATE_DIR/$project.pid"
    echo "wt stack $project: removed"
}

prune() {
    local project idle limit=$((IDLE_MINUTES * 60))
    for project in $(projects); do
        idle="$(idle_seconds "$project")"
        if [ "$idle" -gt "$limit" ]; then
            echo "wt stack $project: idle $((idle / 60)) min (> $IDLE_MINUTES), removing" >&2
            down "$project" >&2
        fi
    done
}

# Detached, and one per project: `wt:up` called twice must not stack watchers.
# `setsid` so it outlives the shell that started it — the whole point is to catch
# the stack its owner forgot. It exits by itself once the stack is gone.
start_reaper() {
    require_project "$1"
    mkdir -p "$STATE_DIR"
    local pidfile="$STATE_DIR/$1.pid" pid
    if [ -f "$pidfile" ] && pid="$(cat "$pidfile")" && kill -0 "$pid" 2>/dev/null; then
        return 0
    fi
    setsid nohup "$0" reaper "$1" >/dev/null 2>&1 &
    echo $! >"$pidfile"
}

reaper() {
    require_project "$1"
    local project="$1"
    while :; do
        sleep "$REAPER_INTERVAL"
        if [ -z "$(docker ps -aq --filter "label=$LABEL=$project")" ]; then
            rm -f "$STATE_DIR/$project.pid"
            exit 0
        fi
        if [ "$(idle_seconds "$project")" -gt $((IDLE_MINUTES * 60)) ]; then
            down "$project" >/dev/null
            exit 0
        fi
    done
}

count() {
    local exclude="${1:-}" n=0 project
    for project in $(projects); do
        [ "$project" = "$exclude" ] || n=$((n + 1))
    done
    echo "$n"
}

list() {
    local project
    for project in $(projects); do
        printf '%s  idle %d min\n' "$project" $(($(idle_seconds "$project") / 60))
    done
}

case "${1:-}" in
    running) running "${2:-}" ;;
    touch) require_project "${2:-}"; touch_stamp "$2" ;;
    touch-if-running)
        require_project "${2:-}"
        if running "$2"; then touch_stamp "$2"; fi
        ;;
    start-reaper) start_reaper "${2:-}" ;;
    reaper) reaper "${2:-}" ;;
    down) down "${2:-}" ;;
    prune) prune ;;
    count) count "${2:-}" ;;
    list) list ;;
    *)
        echo "usage: $0 running|touch|touch-if-running|start-reaper|down <project> | prune | count [exclude] | list" >&2
        exit 64
        ;;
esac
