#!/usr/bin/env bash
#
# Coverage hook (collect-lot.sh): the admin's raw files of a web lot.
#
# Usage: 40-admin.sh <lot>
#
# Playwright writes them itself, during the journeys (e2e/web/global-teardown.ts
# folds each spec's parts into e2e/coverage/raw/admin/<slug>.json): nothing to
# convert here. A web lot that left none recorded nothing of the admin — a bundle
# without source maps, a browser other than Chromium — and must not count as
# complete: the map would then say no web journey runs an admin line.
#
# E2E_COVERAGE_DIR (default e2e/coverage) is where raw/admin/ is.

set -euo pipefail

ROOT="${E2E_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd)}"
raw="${E2E_COVERAGE_DIR:-$ROOT/e2e/coverage}/raw/admin"
lot="${1:-}"

case "$lot" in
  web-*) ;;
  *) exit 0 ;;
esac

count="$(find "$raw" -maxdepth 1 -name '*.json' -type f 2>/dev/null | wc -l)"
if [ "$count" -eq 0 ]; then
  echo "admin coverage of $lot: no raw file under ${raw#"$ROOT"/} — see the Playwright step's '[e2e coverage]' lines" >&2
  exit 1
fi
echo "admin coverage of $lot: $count spec(s)"
