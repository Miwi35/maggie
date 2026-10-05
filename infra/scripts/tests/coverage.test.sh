#!/usr/bin/env bash
# shellcheck disable=SC2015 # `cond && ok || bad`: ok and bad cannot fail
#
# Tests of scripts/coverage/ — the coverage ratchet (MAG-105).
#
# What matters: each tool's report is read right, a drop below the baseline
# fails (and a hold or a gain does not), an empty report fails instead of
# passing, and the baseline only ever moves up.
#
# Usage: infra/scripts/tests/coverage.test.sh

set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DIR="$HERE/../../../scripts/coverage"
failures=0

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

ok()  { printf '  \033[32m✓\033[0m %s\n' "$1"; }
bad() { printf '  \033[31m✗\033[0m %s\n' "$1"; failures=$((failures + 1)); }

# measure <component> <format> <report> — fills $OUT, $STATUS.
measure() {
  OUT="$("$DIR/extract.sh" "$1" "$2" "$3" 2>&1)"
  STATUS=$?
}

printf '\n\033[1mReading each tool'"'"'s report\033[0m\n'

cat > "$work/clover.xml" <<'XML'
<?xml version="1.0" encoding="UTF-8"?>
<coverage generated="1">
  <project timestamp="1">
    <file name="/app/src/A.php">
      <line num="1" type="stmt" count="1"/>
      <metrics loc="10" ncloc="10" classes="1" methods="1" coveredmethods="1" conditionals="0" coveredconditionals="0" statements="4" coveredstatements="4" elements="5" coveredelements="5"/>
    </file>
    <metrics files="2" loc="30" ncloc="30" classes="2" methods="3" coveredmethods="2" conditionals="0" coveredconditionals="0" statements="200" coveredstatements="150" elements="203" coveredelements="152"/>
  </project>
</coverage>
XML
measure api clover "$work/clover.xml"
[ "$STATUS" -eq 0 ] && [ "$(jq -c '[.covered,.total,.percent]' <<<"$OUT")" = "[150,200,75]" ] \
  && ok "clover: project-level statements, not the file's" || bad "clover — $STATUS $OUT"

cat > "$work/cobertura.xml" <<'XML'
<?xml version="1.0" ?>
<coverage version="7.6" timestamp="1" lines-valid="300" lines-covered="201" line-rate="0.67" branches-covered="0" branches-valid="0" branch-rate="0" complexity="0">
  <packages><package name="app" line-rate="1"><classes/></package></packages>
</coverage>
XML
measure agent cobertura "$work/cobertura.xml"
[ "$STATUS" -eq 0 ] && [ "$(jq -c '[.covered,.total,.percent]' <<<"$OUT")" = "[201,300,67]" ] \
  && ok "cobertura: root lines-valid / lines-covered" || bad "cobertura — $STATUS $OUT"

echo '{"total":{"lines":{"total":1234,"covered":1000,"skipped":0,"pct":81.04},"statements":{"total":9,"covered":9,"pct":100}}}' > "$work/vitest.json"
measure admin vitest "$work/vitest.json"
[ "$STATUS" -eq 0 ] && [ "$(jq -c '[.covered,.total,.percent]' <<<"$OUT")" = "[1000,1234,81.04]" ] \
  && ok "vitest: total.lines, two decimals" || bad "vitest — $STATUS $OUT"

cat > "$work/jacoco.xml" <<'XML'
<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<report name="Kover Gradle Plugin">
  <package name="com/maggie/app">
    <class name="com/maggie/app/A"><counter type="INSTRUCTION" missed="1" covered="1"/><counter type="LINE" missed="5" covered="5"/></class>
    <counter type="LINE" missed="40" covered="10"/>
  </package>
  <counter type="INSTRUCTION" missed="9" covered="9"/>
  <counter type="LINE" missed="600" covered="400"/>
  <counter type="METHOD" missed="1" covered="1"/>
</report>
XML
measure mobile jacoco "$work/jacoco.xml"
[ "$STATUS" -eq 0 ] && [ "$(jq -c '[.covered,.total,.percent]' <<<"$OUT")" = "[400,1000,40]" ] \
  && ok "jacoco: the report-level LINE counter, not a package's" || bad "jacoco — $STATUS $OUT"

printf '\n\033[1mA broken measurement fails\033[0m\n'
echo '<coverage lines-valid="0" lines-covered="0"/>' > "$work/zero.xml"
measure agent cobertura "$work/zero.xml"
[ "$STATUS" -ne 0 ] && ok "no line at all is an error, not 100 %" || bad "zero lines passed — $OUT"
echo '<html>nope</html>' > "$work/junk.xml"
measure api clover "$work/junk.xml"
[ "$STATUS" -ne 0 ] && ok "a report with no metrics is an error" || bad "junk passed — $OUT"
measure api clover "$work/absent.xml"
[ "$STATUS" -ne 0 ] && ok "a missing report is an error" || bad "absent report passed — $OUT"
measure api lcov "$work/clover.xml"
[ "$STATUS" -eq 64 ] && ok "an unknown format is a usage error" || bad "lcov — $STATUS $OUT"

printf '\n\033[1mThe ratchet\033[0m\n'
echo '{"api": 75.0, "admin": 50.0}' > "$work/baseline.json"

# verdict <component> <covered> <total> [<percent>] — fills $STATUS, $OUT, $VERDICT.
verdict() {
  jq -nc --arg c "$1" --argjson covered "$2" --argjson total "$3" \
    '{component: $c, covered: $covered, total: $total, percent: (($covered * 10000 / $total | round) / 100)}' > "$work/m.json"
  OUT="$("$DIR/check.sh" "$work/m.json" "$work/baseline.json" 2>&1)"
  STATUS=$?
  VERDICT="$(jq -r .status "$work/m.json")"
}

verdict api 150 200
[ "$STATUS" -eq 0 ] && [ "$VERDICT" = ok ] && ok "equal to the baseline: held" || bad "equal — $STATUS $VERDICT $OUT"
verdict api 1499 2000
[ "$STATUS" -eq 0 ] && [ "$VERDICT" = ok ] && ok "0.05 point under: inside the tolerance" || bad "tolerance — $STATUS $VERDICT $OUT"
verdict api 1490 2000
[ "$STATUS" -eq 10 ] && [ "$VERDICT" = dropped ] && ok "0.5 point under: fails (exit 10)" || bad "drop — $STATUS $VERDICT $OUT"
printf '%s' "$OUT" | grep -q '::error' && ok "the failure is an error annotation naming the component" || bad "no annotation — $OUT"
verdict api 160 200
[ "$STATUS" -eq 0 ] && [ "$VERDICT" = improved ] && ok "above the baseline: improved, passes" || bad "gain — $STATUS $VERDICT $OUT"
verdict mobile 40 100
[ "$STATUS" -eq 0 ] && [ "$VERDICT" = new ] && ok "no baseline yet: passes as new" || bad "new — $STATUS $VERDICT $OUT"
[ "$(jq -r .baseline "$work/m.json")" = null ] && ok "and carries no baseline" || bad "baseline of a new component"
COVERAGE_TOLERANCE=0 verdict api 1499 2000
[ "$VERDICT" = dropped ] && ok "COVERAGE_TOLERANCE=0 makes it strict" || bad "strict — $VERDICT"

printf '\n\033[1mThe report\033[0m\n'
mkdir -p "$work/out"
rm -f "$work/out"/*.json
verdict api 1490 2000; cp "$work/m.json" "$work/out/api.json"
verdict admin 60 100;  cp "$work/m.json" "$work/out/admin.json"
REPORT="$("$DIR/report.sh" 'Coverage' coverage-test "$work/out")"
[ "$(head -1 <<<"$REPORT")" = '<!-- coverage-test -->' ] && ok "starts with the marker the comment is found by" || bad "marker — $REPORT"
grep -q '^| admin | 60 / 100 | 60 % | 50 % | ⬆️' <<<"$REPORT" && ok "a gain row" || bad "admin row — $REPORT"
grep -q '^| api | 1490 / 2000 | 74.5 % | 75 % | ❌' <<<"$REPORT" && ok "a drop row" || bad "api row — $REPORT"
rm -f "$work/out"/*.json
"$DIR/report.sh" 'Coverage' coverage-test "$work/out" | grep -q 'No component was measured' && ok "nothing measured says so" || bad "empty report"

printf '\n\033[1mThe baseline only moves up\033[0m\n'
cp "$work/baseline.json" "$work/b.json"
mkdir -p "$work/up"
rm -f "$work/up"/*.json
jq -nc '{component:"api", percent: 70}' > "$work/up/api.json"
jq -nc '{component:"admin", percent: 61.5}' > "$work/up/admin.json"
jq -nc '{component:"mobile", percent: 12.25}' > "$work/up/mobile.json"
"$DIR/update.sh" "$work/up" "$work/b.json" > /dev/null
[ "$(jq .api "$work/b.json")" = 75 ] && ok "a lower measure leaves the baseline alone" || bad "api lowered — $(cat "$work/b.json")"
[ "$(jq .admin "$work/b.json")" = 61.5 ] && ok "a higher one raises it" || bad "admin not raised — $(cat "$work/b.json")"
[ "$(jq .mobile "$work/b.json")" = 12.25 ] && ok "a new component is recorded" || bad "mobile not recorded — $(cat "$work/b.json")"
rm -f "$work/up"/*.json
"$DIR/update.sh" "$work/up" "$work/b.json" > /dev/null 2>&1
[ "$?" -ne 0 ] && ok "no measure at all is an error" || bad "empty dir passed"

echo
if [ "$failures" -eq 0 ]; then
  printf '\033[32mAll good.\033[0m\n'
else
  printf '\033[31m%d failure(s).\033[0m\n' "$failures"
  exit 1
fi
