#!/usr/bin/env bash
#
# Load guard for the worktree-local tasks.
#
# Several agents share one machine, and the dev stack is already on it. A task
# that starts anyway when memory is short does not fail alone: it takes the
# stack, the other agents and the editor with it. So every `task wt:*` asks
# here first, waits a little if the answer is no, and then tells the caller to
# use CI rather than queueing indefinitely.
#
# Thresholds come from wt/limits.env; exported variables win over it.
#
#   guard.sh         for `task wt:*`: WT_MIN_AVAILABLE_MB, and at most
#                    WT_MAX_STACKS live worktree stacks besides the caller's own
#                    (WT_SELF_PROJECT)
#   guard.sh e2e     for `task e2e:up`, eleven containers: E2E_MIN_AVAILABLE_MB.
#                    Skipped on CI, where the runner is the machine.
#
# Every call first removes the worktree stacks idle for too long (MAG-137), so a
# forgotten stack never counts against the next agent, and records activity on
# the caller's own.

set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
profile="${1:-wt}"

if [ "$profile" = e2e ] && [ -n "${CI:-}" ]; then
    exit 0
fi

available_mb() {
    awk '/^MemAvailable:/ { printf "%d", $2 / 1024 }' /proc/meminfo
}

load1() {
    cut -d' ' -f1 /proc/loadavg
}

cores() {
    nproc
}

if [ "$profile" = e2e ]; then
    min_mb="${E2E_MIN_AVAILABLE_MB:-5120}"
else
    min_mb="${WT_MIN_AVAILABLE_MB:-3072}"
fi
max_stacks="${WT_MAX_STACKS:-3}"
max_ratio="${WT_MAX_LOAD_RATIO:-0.8}"
retries="${WT_GUARD_RETRIES:-6}"
wait_seconds="${WT_GUARD_WAIT_SECONDS:-20}"

max_load="$(awk -v c="$(cores)" -v r="$max_ratio" 'BEGIN { printf "%.2f", c * r }')"

attempt=0
while :; do
    "$here/stack.sh" prune >&2 || true
    stacks=0
    if [ "$profile" != e2e ]; then
        stacks="$("$here/stack.sh" count "${WT_SELF_PROJECT:-}" 2>/dev/null || echo 0)"
    fi
    mem="$(available_mb)"
    load="$(load1)"

    mem_ok=$([ "$mem" -ge "$min_mb" ] && echo yes || echo no)
    load_ok="$(awk -v l="$load" -v m="$max_load" 'BEGIN { print (l <= m) ? "yes" : "no" }')"

    stacks_ok=$([ "$stacks" -lt "$max_stacks" ] && echo yes || echo no)

    if [ "$mem_ok" = yes ] && [ "$load_ok" = yes ] && [ "$stacks_ok" = yes ]; then
        printf 'Load guard: %s MB available, load %s, %s other wt stack(s) (limits: %s MB, %s, %s)\n' \
            "$mem" "$load" "$stacks" "$min_mb" "$max_load" "$max_stacks"
        if [ -n "${WT_SELF_PROJECT:-}" ]; then
            "$here/stack.sh" touch-if-running "$WT_SELF_PROJECT" || true
        fi
        exit 0
    fi

    attempt=$((attempt + 1))
    reason=""
    [ "$mem_ok" = no ] && reason="memory ${mem} MB < ${min_mb} MB"
    [ "$load_ok" = no ] && reason="${reason:+$reason, }load ${load} > ${max_load}"
    [ "$stacks_ok" = no ] && reason="${reason:+$reason, }${stacks} other worktree stacks up (max ${max_stacks})"

    if [ "$attempt" -gt "$retries" ]; then
        cat >&2 <<EOF

Load guard: refusing to start — $reason

The machine is too busy to run this locally without hurting the other agents
and the dev stack. Push the branch and let CI run it instead; that is the
documented fallback, not a failure of the change.

Thresholds live in wt/limits.env if they are wrong for this machine.
EOF
        exit 75 # EX_TEMPFAIL — "try later", not "the code is broken"
    fi

    printf 'Load guard: %s — waiting %ss (attempt %d/%d)\n' \
        "$reason" "$wait_seconds" "$attempt" "$retries" >&2
    sleep "$wait_seconds"
done
