#!/usr/bin/env bash
#
# Coverage hook (collect-lot.sh): the Android app's raw files of a mobile lot.
#
# Usage: 50-mobile.sh <lot>
#
# `e2e/mobile/run.sh` under E2E_COVERAGE=1 leaves, per flow, the app's JaCoCo
# data in e2e/coverage/exec/mobile/<slug>.ec (+ <slug>.journey) — or
# <slug>.missing when it could not take it. This turns them into
# e2e/coverage/raw/mobile/<slug>.json with scripts/e2e/coverage/mobile.sh, against
# the classes the APK was built from (E2E_MOBILE_CLASSES, default
# e2e/mobile/apk/classes: `E2E_COVERAGE=1 build-apk.sh` puts them there, CI
# downloads them there).
#
# Fails — the lot is then not complete, and the night builds no map — when a
# mobile lot left no data at all, when a flow left none, or when a flow's data
# could not be converted or matched no class of the app: a flow missing from
# the map would not be played by a pull request that changes a line it runs.
#
# Any other lot (web-*) with no mobile data: nothing to do.
#
# E2E_COVERAGE_DIR (default e2e/coverage), as for run.sh and mobile.sh.

set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="${E2E_ROOT:-$(cd "$HERE/../../../.." && pwd)}"
dir="${E2E_COVERAGE_DIR:-$ROOT/e2e/coverage}"
exec_dir="$dir/exec/mobile"
classes="${E2E_MOBILE_CLASSES:-$ROOT/e2e/mobile/apk/classes}"
# Overridable for the tests, which have neither java nor JaCoCo data.
convert="${E2E_COVERAGE_MOBILE_SH:-$HERE/../mobile.sh}"
lot="${1:-}"

shopt -s nullglob
data=("$exec_dir"/*.ec)
missing=("$exec_dir"/*.missing)
if [ "${#data[@]}" -eq 0 ] && [ "${#missing[@]}" -eq 0 ]; then
  case "$lot" in
    mobile-*)
      echo "mobile coverage of $lot: no JaCoCo data under ${exec_dir#"$ROOT"/} — was run.sh run with E2E_COVERAGE=1?" >&2
      exit 1
      ;;
    *) exit 0 ;;
  esac
fi

status=0
if [ "${#data[@]}" -gt 0 ]; then
  E2E_ROOT="$ROOT" E2E_COVERAGE_DIR="$dir" "$convert" "$exec_dir" "$classes" || status=1
fi

for file in "${missing[@]}"; do
  echo "mobile coverage of $lot: $(head -1 "$file") left no JaCoCo data (see run.sh's warning)" >&2
  status=1
done
for file in "${data[@]}"; do
  slug="$(basename "$file" .ec)"
  out="$dir/raw/mobile/$slug.json"
  if [ ! -s "$out" ]; then
    echo "mobile coverage of $lot: $slug.ec was not converted" >&2
    status=1
  # Every flow launches the app: no line at all means JaCoCo matched none of the
  # classes — not the build the APK was made from.
  elif ! python3 -c 'import json, sys; sys.exit(0 if json.load(open(sys.argv[1]))["files"] else 1)' "$out"; then
    echo "mobile coverage of $lot: $slug ran no line of the app — classes of another build than the APK's?" >&2
    status=1
  fi
done
exit "$status"
