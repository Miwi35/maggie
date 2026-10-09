#!/usr/bin/env bash
#
# The Android app's coverage per Maestro flow, turned into the raw files of
# « Sélection e2e par couverture »: e2e/coverage/raw/mobile/<slug>.json.
#
# Usage:  scripts/e2e/coverage/mobile.sh <exec dir> <classes dir>
#
#   <exec dir>     what `e2e/mobile/run.sh` pulled under E2E_COVERAGE=1: one
#                  <slug>.ec (JaCoCo execution data) and one <slug>.journey
#                  (the flow's id) per flow.
#   <classes dir>  the e2e build's original, uninstrumented classes — what
#                  `E2E_COVERAGE=1 e2e/mobile/build-apk.sh` copies next to the APK
#                  (e2e/mobile/apk/classes). They must be the very build the APK
#                  was made from: JaCoCo matches classes by a checksum and skips
#                  the others.
#
# Needs java (17+) and python3. Downloads JaCoCo's CLI once, checksum verified,
# into .e2e-cache/jacoco/ — the version the app is instrumented with
# (`testCoverage.jacocoVersion` in mobile/app/build.gradle.kts).
#
# E2E_COVERAGE_DIR (default e2e/coverage) is where raw/mobile/ goes;
# E2E_COVERAGE_MOBILE_SOURCES (default: the e2e variant's source dirs) lists,
# space-separated and repo-relative, where JaCoCo's `package/File.kt` are found.

set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="${E2E_ROOT:-$(cd "$HERE/../../.." && pwd)}"

JACOCO_VERSION=0.8.12
JACOCO_SHA256=594c01125b84864a7fc5a9efab19b71b7de36b27346863f6d7cd0cd45400ceb0
JACOCO_URL="https://repo1.maven.org/maven2/org/jacoco/org.jacoco.cli/$JACOCO_VERSION/org.jacoco.cli-$JACOCO_VERSION-nodeps.jar"

die() { echo "mobile.sh: $*" >&2; exit 2; }

exec_dir="${1:-}"
classes_dir="${2:-}"
[ -d "$exec_dir" ] || die "usage: mobile.sh <exec dir> <classes dir> — no exec dir at '$exec_dir'"
[ -d "$classes_dir" ] || die "no classes at '$classes_dir': build the APK with E2E_COVERAGE=1 e2e/mobile/build-apk.sh"
command -v java >/dev/null || die "java is required"
command -v python3 >/dev/null || die "python3 is required"

out_dir="${E2E_COVERAGE_DIR:-$ROOT/e2e/coverage}/raw/mobile"
read -ra sources <<<"${E2E_COVERAGE_MOBILE_SOURCES:-mobile/app/src/main/java mobile/app/src/e2e/java}"

cli="$ROOT/.e2e-cache/jacoco/jacococli-$JACOCO_VERSION.jar"
if [ ! -f "$cli" ] || ! echo "$JACOCO_SHA256  $cli" | sha256sum -c --status; then
  mkdir -p "$(dirname "$cli")"
  curl -fsSL -o "$cli.part" "$JACOCO_URL"
  echo "$JACOCO_SHA256  $cli.part" | sha256sum -c --status || { rm -f "$cli.part"; die "the JaCoCo CLI downloaded from $JACOCO_URL does not match its checksum"; }
  mv "$cli.part" "$cli"
fi

scratch="$(mktemp -d)"
trap 'rm -rf "$scratch"' EXIT
mkdir -p "$out_dir"

count=0
for exec_file in "$exec_dir"/*.ec; do
  [ -e "$exec_file" ] || continue
  slug="$(basename "$exec_file" .ec)"
  journey_file="$exec_dir/$slug.journey"
  [ -s "$journey_file" ] || { echo "  ! $slug.ec has no $slug.journey beside it: skipped" >&2; continue; }
  journey="$(head -1 "$journey_file")"

  # --quiet: one line per class whose checksum is unknown would bury the rest.
  # One flow that cannot be read must not cost the others theirs.
  if ! java -jar "$cli" report "$exec_file" --classfiles "$classes_dir" --xml "$scratch/$slug.xml" --quiet \
    || ! python3 "$HERE/jacoco_lines.py" "$scratch/$slug.xml" "$journey" "$ROOT" "${sources[@]}" >"$out_dir/$slug.json"; then
    rm -f "$out_dir/$slug.json"
    echo "  ! $journey: JaCoCo could not read its counts, skipped" >&2
    continue
  fi
  files="$(python3 -c 'import json, sys; print(len(json.load(open(sys.argv[1]))["files"]))' "$out_dir/$slug.json")"
  echo "  $journey: $files file(s)"
  count=$((count + 1))
done

echo "mobile coverage: $count flow(s) written to ${out_dir#"$ROOT"/}"
