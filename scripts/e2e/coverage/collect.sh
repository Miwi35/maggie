#!/usr/bin/env bash
#
# Gathers the raw coverage of the journeys a lot just played, under
# e2e/coverage/raw/<component>/<journey slug>.json (spec « Sélection e2e par
# couverture »). Run by ci.yml after the journeys of a lot, when the nightly
# asks for coverage (E2E_COVERAGE=1), the stack still up and the emulator gone.
#
# Usage: collect.sh <label>      (the label only names the lot in the messages)
#
# What writes the raw files belongs to the components (parts A and B of the
# spec); this script only calls their hooks, each one if it exists:
#   1. `task e2e:coverage:collect` — the agent's and the API's coverage, read out
#      of the stack's containers (and whatever else the Taskfile chains to it);
#   2. every executable `scripts/e2e/coverage/collect.d/*.sh`, in name order —
#      a converter that needs nothing of the stack (the mobile JaCoCo data, say).
# Playwright writes the admin's raw files itself, during the journeys.
#
# Never fails: coverage must not change the night's verdict. A hook that fails
# or a lot that left no raw file is a warning, and its files fall back to
# e2e/impact-map.yml on the pull requests.

set -uo pipefail

ROOT="${E2E_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)}"
RAW="${E2E_COVERAGE_RAW:-$ROOT/e2e/coverage/raw}"
HOOKS="${E2E_COVERAGE_HOOKS:-$ROOT/scripts/e2e/coverage/collect.d}"
label="${1:-this lot}"

cd "$ROOT" || exit 0

if command -v task >/dev/null 2>&1 && task --list-all 2>/dev/null | grep -qE '^\* e2e:coverage:collect(:|[[:space:]]|$)'; then
  task e2e:coverage:collect || echo "::warning::coverage of $label: task e2e:coverage:collect failed"
fi

if [ -d "$HOOKS" ]; then
  for hook in "$HOOKS"/*.sh; do
    [ -x "$hook" ] || continue
    "$hook" || echo "::warning::coverage of $label: $(basename "$hook") failed"
  done
fi

total=0
summary=''
for dir in "$RAW"/*/; do
  [ -d "$dir" ] || continue
  n="$(find "$dir" -name '*.json' -type f | wc -l)"
  total=$((total + n))
  summary="$summary $(basename "$dir")=$n"
done
if [ "$total" -eq 0 ]; then
  echo "::warning::coverage of $label: no raw file under ${RAW#"$ROOT"/}"
else
  echo "coverage of $label: $total raw files ($summary )"
fi
exit 0
