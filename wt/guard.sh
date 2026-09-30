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

set -euo pipefail

available_mb() {
    awk '/^MemAvailable:/ { printf "%d", $2 / 1024 }' /proc/meminfo
}

load1() {
    cut -d' ' -f1 /proc/loadavg
}

cores() {
    nproc
}

min_mb="${WT_MIN_AVAILABLE_MB:-3072}"
max_ratio="${WT_MAX_LOAD_RATIO:-0.8}"
retries="${WT_GUARD_RETRIES:-6}"
wait_seconds="${WT_GUARD_WAIT_SECONDS:-20}"

max_load="$(awk -v c="$(cores)" -v r="$max_ratio" 'BEGIN { printf "%.2f", c * r }')"

attempt=0
while :; do
    mem="$(available_mb)"
    load="$(load1)"

    mem_ok=$([ "$mem" -ge "$min_mb" ] && echo yes || echo no)
    load_ok="$(awk -v l="$load" -v m="$max_load" 'BEGIN { print (l <= m) ? "yes" : "no" }')"

    if [ "$mem_ok" = yes ] && [ "$load_ok" = yes ]; then
        printf 'Load guard: %s MB available, load %s (limits: %s MB, %s)\n' \
            "$mem" "$load" "$min_mb" "$max_load"
        exit 0
    fi

    attempt=$((attempt + 1))
    reason=""
    [ "$mem_ok" = no ] && reason="memory ${mem} MB < ${min_mb} MB"
    [ "$load_ok" = no ] && reason="${reason:+$reason, }load ${load} > ${max_load}"

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
