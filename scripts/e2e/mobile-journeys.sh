#!/usr/bin/env bash
#
# One lot of Maestro journeys, judged with the quarantine applied — the script
# CI hands the emulator (`E2E Mobile journeys` in ci.yml).
#
# Usage:  E2E_MOBILE_FLOWS='flows/01-login-chat.yaml flows/05-deep-links.yaml' \
#           scripts/e2e/mobile-journeys.sh
#
# The flows come from `impacted.sh select` (the `mobile_lots` output). Two runs
# of `task e2e:mobile` on the stack and the device the job prepared:
#
#   1. the journeys that block — their verdict is the script's exit status;
#   2. the journeys in quarantine (e2e/impact-map.yml), under a time limit
#      (E2E_QUARANTINE_TIMEOUT seconds, 600 by default): they play, their result
#      is in the job summary, and nothing they do — fail, or hang the emulator as
#      02-voice-overlay once did for 17 minutes — fails the lot.
#
# Each run reseeds and installs the APK again (`task e2e:mobile`), so the second
# starts from the world the first started from. The reports end up side by side
# in e2e/mobile/report/{journeys,quarantine}/, which the job uploads.
#
# E2E_VERDICT_DIR (optional) receives one verdict JSON per run, named after
# E2E_VERDICT_LABEL.

set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="${E2E_ROOT:-$(cd "$HERE/../.." && pwd)}"
# shellcheck source=scripts/e2e/lib.sh
. "$HERE/lib.sh"

read -ra flows <<<"${E2E_MOBILE_FLOWS:?E2E_MOBILE_FLOWS names the flows of this lot}"
[ "${#flows[@]}" -gt 0 ] || { echo "E2E_MOBILE_FLOWS is empty" >&2; exit 2; }
QUARANTINE_TIMEOUT="${E2E_QUARANTINE_TIMEOUT:-600}"
LABEL="${E2E_VERDICT_LABEL:-mobile}"
REPORT="$ROOT/e2e/mobile/report"
COLLECTED="$ROOT/e2e/mobile/report.collected"
# Overridable for the tests, which have no stack and no device.
RUN_JOURNEYS="${E2E_MOBILE_RUN:-task e2e:mobile --}"

quarantined="$(e2e_map_json 2>/dev/null | jq -c "$E2E_JQ_DEFS"'[journeys[] | select(.quarantine != null) | .id]' 2>/dev/null || echo '[]')"
blocking=()
lenient=()
for flow in "${flows[@]}"; do
  if jq -e --arg id "e2e/mobile/$flow" 'index($id)' >/dev/null <<<"$quarantined"; then
    lenient+=("$flow")
  else
    blocking+=("$flow")
  fi
done

rm -rf "$COLLECTED"
mkdir -p "$COLLECTED"

# run <kind> <timeout seconds or 0> <flows…> — one run, its report kept, its verdict printed.
run() {
  local kind="$1" limit="$2" status=0 verdict_out=''
  shift 2
  echo "::group::$kind: $*"
  # shellcheck disable=SC2086 # RUN_JOURNEYS is a command line
  if [ "$limit" -gt 0 ]; then
    (cd "$ROOT" && timeout --kill-after=30 "$limit" $RUN_JOURNEYS "$@") || status=$?
  else
    (cd "$ROOT" && $RUN_JOURNEYS "$@") || status=$?
  fi
  echo "::endgroup::"
  [ "$status" -ne 124 ] || echo "::warning::$kind: still running after ${limit}s, stopped."
  rm -rf "${COLLECTED:?}/$kind"
  if [ -d "$REPORT" ]; then mv "$REPORT" "$COLLECTED/$kind"; else mkdir -p "$COLLECTED/$kind"; fi
  if [ -n "${E2E_VERDICT_DIR:-}" ]; then
    verdict_out="$E2E_VERDICT_DIR/$(tr -c 'A-Za-z0-9_-' '-' <<<"$LABEL" | sed 's/-*$//')-$kind.json"
  fi
  E2E_VERDICT_LABEL="$LABEL ($kind)" E2E_VERDICT_OUT="$verdict_out" \
    "$HERE/verdict.sh" mobile "$COLLECTED/$kind/junit.xml" "$status" "$@"
}

final=0
if [ "${#blocking[@]}" -gt 0 ]; then
  run journeys 0 "${blocking[@]}" || final=1
fi
if [ "${#lenient[@]}" -gt 0 ]; then
  # Never blocking: verdict.sh already says what failed, in the summary.
  run quarantine "$QUARANTINE_TIMEOUT" "${lenient[@]}" || true
fi

rm -rf "$REPORT"
mv "$COLLECTED" "$REPORT"
exit "$final"
