#!/usr/bin/env bash
#
# Gathers the raw coverage of the journeys a lot just played, under
# e2e/coverage/raw/<component>/<journey slug>.json (spec « Sélection e2e par
# couverture »). Run by ci.yml after the journeys of a lot, when the nightly
# asks for coverage (E2E_COVERAGE=1), the stack still up and the emulator gone.
#
# Usage: collect-lot.sh <lot>      (web-<shard> or mobile-<device>-<lot>)
#
# What writes the raw files belongs to the components (parts A and B of the
# spec); this script only calls their hooks, each one if it exists:
#   1. `task e2e:coverage:collect` — the agent's and the API's coverage, read out
#      of the stack's containers (scripts/e2e/coverage/collect.sh, README.md
#      there). It restarts the agent, so it must run in the job that started
#      the stack, with the same Compose variables (E2E_IMAGE_*, E2E_NOW →
#      E2E_FAKETIME): ci.yml keeps them in $GITHUB_ENV;
#   2. every executable `scripts/e2e/coverage/collect.d/*.sh`, in name order,
#      given the lot's name — what needs nothing of the stack: `40-admin.sh`
#      checks the raw files Playwright wrote itself (global-teardown.ts), and
#      `50-mobile.sh` turns the app's JaCoCo data into raw files.
#
# Under E2E_COVERAGE=1 the servers' coverage is expected: a task that cannot be
# found, or a lot that left no API raw file (every journey goes through the
# API), makes the lot incomplete instead of quietly giving a client-only map.
# The agent is not required per lot — a lot of two flows may never reach it —
# but its absence is said.
#
# A lot whose hooks all succeeded and that left raw files is marked
# complete: e2e/coverage/raw/_lots/<lot>.ok. The nightly builds no map unless
# every lot it played is (`build-map.py --expect`): a lot missing would leave
# lines "covered" by only some of the journeys that run them, and a pull request
# would then play too few.
#
# Never fails: coverage must not change the night's verdict. A hook that fails
# or a lot that left no raw file is a warning, and no map that night.

set -uo pipefail

ROOT="${E2E_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)}"
RAW="${E2E_COVERAGE_RAW:-$ROOT/e2e/coverage/raw}"
HOOKS="${E2E_COVERAGE_HOOKS:-$ROOT/scripts/e2e/coverage/collect.d}"
lot="${1:-unnamed}"

cd "$ROOT" || exit 0

server_expected=false
[ "${E2E_COVERAGE:-}" = 1 ] && server_expected=true

failed=0
# `--summary`'s exit status, not a grep of `--list-all`: under CI=true Task
# colours that listing (v3.54), and the grep missed it every night.
if command -v task >/dev/null 2>&1 && task --summary e2e:coverage:collect >/dev/null 2>&1; then
  task e2e:coverage:collect || { failed=$((failed + 1)); echo "::warning::coverage of $lot: task e2e:coverage:collect failed"; }
elif $server_expected; then
  failed=$((failed + 1))
  echo "::warning::coverage of $lot: E2E_COVERAGE=1 but no task e2e:coverage:collect to run — no API nor agent coverage"
fi

if [ -d "$HOOKS" ]; then
  for hook in "$HOOKS"/*.sh; do
    [ -x "$hook" ] || continue
    "$hook" "$lot" || { failed=$((failed + 1)); echo "::warning::coverage of $lot: $(basename "$hook") failed"; }
  done
fi

total=0
summary=''
for dir in "$RAW"/*/; do
  [ -d "$dir" ] || continue
  [ "$(basename "$dir")" = _lots ] && continue
  n="$(find "$dir" -name '*.json' -type f | wc -l)"
  total=$((total + n))
  summary="$summary $(basename "$dir")=$n"
done

# collect.sh always writes under e2e/coverage/raw: keep E2E_COVERAGE_RAW there.
count() { find "$RAW/$1" -name '*.json' -type f 2>/dev/null | wc -l; }
if $server_expected && [ "$total" -gt 0 ]; then
  if [ "$(count api)" -eq 0 ]; then
    failed=$((failed + 1))
    echo "::warning::coverage of $lot: no API raw file — did the stack start with E2E_COVERAGE=1, and the requests carry X-E2E-Journey?"
  fi
  [ "$(count agent)" -gt 0 ] || echo "coverage of $lot: no agent raw file (no journey of this lot reached the agent?)"
fi

if [ "$total" -eq 0 ]; then
  echo "::warning::coverage of $lot: no raw file under ${RAW#"$ROOT"/}"
elif [ "$failed" -gt 0 ]; then
  echo "::warning::coverage of $lot: $total raw files ($summary ) but incomplete — no map tonight"
else
  mkdir -p "$RAW/_lots" && echo ok >"$RAW/_lots/$lot.ok"
  echo "coverage of $lot: $total raw files ($summary )"
fi
exit 0
