#!/usr/bin/env bash
# shellcheck disable=SC2015 # `cond && ok || bad`: ok and bad cannot fail
#
# Tests of `e2e/clock.sh ci`, the instant CI pins its journeys to (MAG-267).
#
# CI used to run on the wall clock, so the meals and agenda journeys depended on
# the week they ran in. The instant is now fixed: two runs on different days must
# read the same one. It also has to say so loudly when it is reached, instead of
# falling back to the wall clock.
#
# Usage: infra/scripts/tests/clock.test.sh

set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CLOCK="$HERE/../../../e2e/clock.sh"
failures=0

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

ok()  { printf '  \033[32m✓\033[0m %s\n' "$1"; }
bad() { printf '  \033[31m✗\033[0m %s\n' "$1"; failures=$((failures + 1)); }

REAL_DATE="$(command -v date)"

# A `date` whose "now" (`date +%s`, no other argument) is $FAKE_NOW, and which is
# the real one for every other call.
cat > "$work/date" <<SHIM
#!/usr/bin/env bash
if [ "\$#" -eq 1 ] && [ "\$1" = '+%s' ]; then
  echo "\$FAKE_NOW"
else
  exec "$REAL_DATE" "\$@"
fi
SHIM
chmod +x "$work/date"

# at <day> [E2E_CI_NOW] — `clock.sh ci` as run on that day at 14:00 UTC
at() {
  local now
  now="$("$REAL_DATE" -u -d "$1 14:00:00" +%s)"
  OUTPUT="$(PATH="$work:$PATH" FAKE_NOW="$now" E2E_CI_NOW="${2:-}" "$CLOCK" ci 2>&1)"
  STATUS=$?
}

printf '\n\033[1mThe same instant, whatever day the run starts on\033[0m\n'
at 2026-10-06
first="$OUTPUT"
[ "$STATUS" -eq 0 ] && ok "exits 0 on 2026-10-06" || bad "exit $STATUS — $OUTPUT"
at 2026-10-13
[ "$STATUS" -eq 0 ] && [ "$OUTPUT" = "$first" ] && ok "2026-10-13 reads the same instant" || bad "2026-10-13: '$OUTPUT' instead of '$first'"
at 2029-07-04
[ "$STATUS" -eq 0 ] && [ "$OUTPUT" = "$first" ] && ok "so does 2029-07-04" || bad "2029-07-04: '$OUTPUT' instead of '$first'"
[ "$("$REAL_DATE" -d "$first" +%u)" = 3 ] && ok "it is a Wednesday" || bad "$first is not a Wednesday"
[ "$("$REAL_DATE" -d "$first" +%s)" = "$("$REAL_DATE" -d 2030-01-16T12:00:00+01:00 +%s)" ] \
  && ok "noon in Paris, in winter time" || bad "$first is not 2030-01-16T12:00 in Paris"

printf '\n\033[1mThe reference has been reached\033[0m\n'
at 2030-01-16
[ "$STATUS" -ne 0 ] && ok "fails instead of falling back to the wall clock" || bad "exit 0 after the reference: '$OUTPUT'"
case "$OUTPUT" in *CI_REFERENCE*) ok "and names what to move" ;; *) bad "no hint in: $OUTPUT" ;; esac

printf '\n\033[1mThe repository variable overrides it\033[0m\n'
at 2026-10-06 2031-03-02T10:00:00+01:00
[ "$STATUS" -eq 0 ] && [ "$("$REAL_DATE" -d "$OUTPUT" +%s)" = "$("$REAL_DATE" -d 2031-03-02T10:00:00+01:00 +%s)" ] \
  && ok "a future date wins" || bad "exit $STATUS — $OUTPUT"
at 2026-10-06 2026-01-01
[ "$STATUS" -ne 0 ] && ok "a past date is refused, as for any E2E_NOW" || bad "a past override was accepted: $OUTPUT"
at 2026-10-06 not-a-date
[ "$STATUS" -ne 0 ] && ok "a typo is refused" || bad "a typo was accepted: $OUTPUT"

if [ "$failures" -gt 0 ]; then
  printf '\n\033[31m%d failure(s)\033[0m\n' "$failures"
  exit 1
fi
printf '\n\033[32mAll good\033[0m\n'
