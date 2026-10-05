#!/usr/bin/env bash
#
# Turn one component's coverage report into a line-coverage measure (MAG-105).
#
#   scripts/coverage/extract.sh <component> <format> <report file>
#
# Prints {"component","covered","total","percent"} on stdout. Formats:
#   clover     PHPUnit  --coverage-clover
#   cobertura  pytest-cov  --cov-report=xml
#   vitest     Vitest v8  json-summary reporter
#   jacoco     Kover  koverXmlReport (JaCoCo XML)
#
# An empty report (no line at all) is an error, not 100 %: a measurement that
# silently stopped measuring must not read as a pass.

set -euo pipefail

[ "$#" -eq 3 ] || { echo "usage: extract.sh <component> <clover|cobertura|vitest|jacoco> <report>" >&2; exit 64; }
component="$1" format="$2" report="$3"
[ -s "$report" ] || { echo "extract.sh: $report is missing or empty" >&2; exit 1; }

# `grep -o … | tail -1 | grep -o '[0-9]*$'` would read the last number of a tag;
# every attribute is therefore read by name.
attr() { grep -o " $1=\"[0-9]*\"" | tail -1 | grep -o '[0-9]*' || true; }

case "$format" in
  clover)
    # Only the project-level <metrics> carries files="…"; the file-level ones do not.
    tag="$(grep -o '<metrics files="[^>]*>' "$report" | tail -1 || true)"
    total="$(attr statements <<<"$tag")"
    covered="$(attr coveredstatements <<<"$tag")"
    ;;
  cobertura)
    tag="$(grep -o '<coverage [^>]*>' "$report" | head -1 || true)"
    total="$(attr lines-valid <<<"$tag")"
    covered="$(attr lines-covered <<<"$tag")"
    ;;
  jacoco)
    # The report-level counters close the file; package and class ones come first.
    tag="$(grep -o '<counter type="LINE"[^>]*>' "$report" | tail -1 || true)"
    covered="$(attr covered <<<"$tag")"
    missed="$(attr missed <<<"$tag")"
    total=""
    [ -z "$covered" ] || [ -z "$missed" ] || total=$((covered + missed))
    ;;
  vitest)
    covered="$(jq -r '.total.lines.covered // empty' "$report")"
    total="$(jq -r '.total.lines.total // empty' "$report")"
    ;;
  *)
    echo "extract.sh: unknown format '$format'" >&2
    exit 64
    ;;
esac

if [ -z "${total:-}" ] || [ -z "${covered:-}" ] || [ "$total" -eq 0 ]; then
  echo "extract.sh: no line coverage found in $report ($format)" >&2
  exit 1
fi

jq -nc --arg component "$component" --argjson covered "$covered" --argjson total "$total" \
  '{component: $component, covered: $covered, total: $total, percent: (($covered * 10000 / $total | round) / 100)}'
