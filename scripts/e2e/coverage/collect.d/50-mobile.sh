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
# Except for a flow in quarantine (e2e/impact-map.yml, read through lib.sh as
# mobile-journeys.sh does): one cut short — the quarantine's time limit, a
# Maestro that died — is what the quarantine is for, and must not cost the
# night its map (12 Oct.: 05-deep-links, and no map). Its raw file is removed
# and it is marked dropped (raw/_lots/<slug>.dropped, the journey's id):
# build-map.py then leaves it out of the map altogether, its server lines
# included, and impacted.sh selects a journey absent from the map by its zones.
# A warning says so.
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
# shellcheck source=scripts/e2e/lib.sh
. "$HERE/../../lib.sh"

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

quarantined="$(e2e_quarantined_json)"
# flaw <journey> <slug> <what went wrong> — a flow in quarantine is dropped from
# the map with a warning; any other makes the lot incomplete.
flaw() {
  if [ -n "$1" ] && jq -e --arg id "$1" 'index($id) != null' >/dev/null <<<"$quarantined"; then
    echo "::warning::mobile coverage of $lot: $3 — $1 is in quarantine: left out of tonight's map, the lot stays complete"
    rm -f "$dir/raw/mobile/$2.json"
    mkdir -p "$dir/raw/_lots" && printf '%s\n' "$1" >"$dir/raw/_lots/$2.dropped"
  else
    echo "mobile coverage of $lot: $3" >&2
    status=1
  fi
}

for file in "${missing[@]}"; do
  journey="$(head -1 "$file")"
  flaw "$journey" "$(basename "$file" .missing)" "$journey left no JaCoCo data (see run.sh's warning)"
done
for file in "${data[@]}"; do
  slug="$(basename "$file" .ec)"
  journey="$(head -1 "$exec_dir/$slug.journey" 2>/dev/null || true)"
  out="$dir/raw/mobile/$slug.json"
  if [ ! -s "$out" ]; then
    flaw "$journey" "$slug" "$slug.ec was not converted"
  # Every flow launches the app: no line at all means JaCoCo matched none of the
  # classes — not the build the APK was made from.
  elif ! python3 -c 'import json, sys; sys.exit(0 if json.load(open(sys.argv[1]))["files"] else 1)' "$out"; then
    flaw "$journey" "$slug" "$slug ran no line of the app — classes of another build than the APK's?"
  fi
done
exit "$status"
