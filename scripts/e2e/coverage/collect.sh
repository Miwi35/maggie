#!/usr/bin/env bash
#
# Gathers the raw coverage of the journeys a lot just played, under
# e2e/coverage/raw/<component>/<journey slug>.json (spec « Sélection e2e par
# couverture »). Run by ci.yml after the journeys of a lot, when the nightly
# asks for coverage (E2E_COVERAGE=1), the stack still up and the emulator gone.
#
# Usage: collect.sh <lot>      (web-<shard> or mobile-<device>-<lot>)
#
# What writes the raw files belongs to the components (parts A and B of the
# spec); this script only calls their hooks, each one if it exists:
#   1. `task e2e:coverage:collect` — the agent's and the API's coverage, read out
#      of the stack's containers (and whatever else the Taskfile chains to it);
#   2. every executable `scripts/e2e/coverage/collect.d/*.sh`, in name order —
#      a converter that needs nothing of the stack (the mobile JaCoCo data, say).
# Playwright writes the admin's raw files itself, during the journeys.
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

failed=0
if command -v task >/dev/null 2>&1 && task --list-all 2>/dev/null | grep -qE '^\* e2e:coverage:collect(:|[[:space:]]|$)'; then
  task e2e:coverage:collect || { failed=$((failed + 1)); echo "::warning::coverage of $lot: task e2e:coverage:collect failed"; }
fi

if [ -d "$HOOKS" ]; then
  for hook in "$HOOKS"/*.sh; do
    [ -x "$hook" ] || continue
    "$hook" || { failed=$((failed + 1)); echo "::warning::coverage of $lot: $(basename "$hook") failed"; }
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

if [ "$total" -eq 0 ]; then
  echo "::warning::coverage of $lot: no raw file under ${RAW#"$ROOT"/}"
elif [ "$failed" -gt 0 ]; then
  echo "::warning::coverage of $lot: $total raw files ($summary ) but incomplete — no map tonight"
else
  mkdir -p "$RAW/_lots" && echo ok >"$RAW/_lots/$lot.ok"
  echo "coverage of $lot: $total raw files ($summary )"
fi
exit 0
