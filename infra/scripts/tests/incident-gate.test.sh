#!/usr/bin/env bash
# shellcheck disable=SC2015 # `cond && ok || bad`: ok and bad cannot fail
#
# Tests of infra/scripts/incident-gate.sh and rerun-incident-gates.sh against a
# fake curl and a fake gh (MAG-184).
#
# During a freeze, an unrelated PR must not merge, the fix must, and the PRs
# held must start again on their own once it lifts. When Linear cannot say, the
# gate holds (fail closed).
#
# Usage: infra/scripts/tests/incident-gate.test.sh

set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
GATE="$HERE/../incident-gate.sh"
RERUN="$HERE/../rerun-incident-gates.sh"
failures=0

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
mkdir -p "$work/bin"
ln -s "$HERE/fake-curl.sh" "$work/bin/curl"
# A gh that lists the open PRs from $FAKE_CURL_DIR/prs and logs the jobs it re-runs.
cat > "$work/bin/gh" <<'FAKE'
#!/usr/bin/env bash
case "$1 $2" in
  "pr list") cat "$FAKE_CURL_DIR/prs" ;;
  "api -X") echo "$4" >> "$FAKE_CURL_DIR/reruns"; [ ! -f "$FAKE_CURL_DIR/rerun-fails" ] ;;
  *) echo "unexpected gh $*" >&2; exit 1 ;;
esac
FAKE
chmod +x "$work/bin/gh"

ok()  { printf '  \033[32m✓\033[0m %s\n' "$1"; }
bad() { printf '  \033[31m✗\033[0m %s\n' "$1"; failures=$((failures + 1)); }

# frozen_by <emergency keys> <incident keys> — what Linear answers, keys comma-separated
frozen_by() {
  rm -rf "$work/api"
  mkdir -p "$work/api"
  : > "$work/api/requests"
  : > "$work/api/reruns"
  jq -nc --arg e "$1" --arg i "$2" '
    def nodes(s): {nodes: [s | split(",")[] | select(. != "") | {identifier: .}]};
    {data: {emergency: nodes($e), incidents: nodes($i)}}' > "$work/api/lookup-response"
}

run_gate() {
  OUTPUT="$(PATH="$work/bin:$PATH" FAKE_CURL_DIR="$work/api" LINEAR_API_KEY="${KEY-lin_api_secret}" \
    PR_TITLE="$1" PR_BRANCH="$2" "$GATE" 2>&1)"
  STATUS=$?
}

printf '\n\033[1mNo freeze: every PR passes\033[0m\n'
frozen_by "" ""
run_gate 'Add a recipe filter (MAG-150) (#70)' 'cyrus/mag-150-recipe-filter'
[ "$STATUS" -eq 0 ] && ok "passes" || bad "exit $STATUS — $OUTPUT"
jq -e '.variables.state == "Emergency" and .variables.key == "MAG"' "$work/api/requests" >/dev/null \
  && ok "asks for team MAG and the « Emergency » state" || bad "query: $(cat "$work/api/requests")"
jq -r .query "$work/api/requests" | grep -q 'labels: {some: {name: {eq: "incident"}}}' && ok "and for open incident tickets" || bad "no incident filter"

printf '\n\033[1mA ticket in « Emergency »: only its PR passes\033[0m\n'
frozen_by "MAG-184" ""
run_gate 'Add a recipe filter (MAG-150)' 'cyrus/mag-150-recipe-filter'
[ "$STATUS" -ne 0 ] && ok "an unrelated PR is held" || bad "passed: $OUTPUT"
echo "$OUTPUT" | grep -q '::error::Production is frozen by MAG-184' && ok "says why and by what" || bad "unclear: $OUTPUT"
run_gate 'Fix the smoke suite' 'cyrus/mag-184-fix-the-smoke-suite'
[ "$STATUS" -eq 0 ] && ok "the fix passes by its branch" || bad "exit $STATUS — $OUTPUT"
run_gate 'Fix the smoke suite (MAG-184)' 'meven35/fix'
[ "$STATUS" -eq 0 ] && ok "the fix passes by its title" || bad "exit $STATUS — $OUTPUT"
run_gate 'Fix MAG-1840' 'cyrus/mag-18-other'
[ "$STATUS" -ne 0 ] && ok "MAG-18 or MAG-1840 is not MAG-184" || bad "passed: $OUTPUT"

printf '\n\033[1mAn open incident freezes the same way\033[0m\n'
frozen_by "" "MAG-200"
run_gate 'Add a recipe filter (MAG-150)' 'cyrus/mag-150-recipe-filter'
[ "$STATUS" -ne 0 ] && ok "an unrelated PR is held" || bad "passed: $OUTPUT"
frozen_by "MAG-184" "MAG-200"
run_gate 'Repair production (MAG-200)' 'meven35/mag-200-repair'
[ "$STATUS" -eq 0 ] && ok "the PR of any frozen ticket passes" || bad "exit $STATUS — $OUTPUT"

printf '\n\033[1mFails closed\033[0m\n'
frozen_by "" ""
KEY='' run_gate 'Anything' 'cyrus/mag-1-x'
[ "$STATUS" -ne 0 ] && ok "no API key: holds" || bad "passed without a key"
echo "$OUTPUT" | grep -q 'LINEAR_API_KEY is not set' && ok "says which secret is missing" || bad "unclear: $OUTPUT"
printf '%s' '{"errors":[{"message":"Invalid scope: read required"}]}' > "$work/api/lookup-response"
run_gate 'Anything' 'cyrus/mag-1-x'
[ "$STATUS" -ne 0 ] && ok "Linear refuses: holds" || bad "passed on a Linear error"
echo "$OUTPUT" | grep -q 'Invalid scope' && ok "shows Linear's error" || bad "error hidden: $OUTPUT"
printf '%s' '{"data":{}}' > "$work/api/lookup-response"
run_gate 'Anything' 'cyrus/mag-1-x'
[ "$STATUS" -ne 0 ] && ok "an answer without the lists: holds" || bad "passed on an empty answer"
printf '%s' 'not json' > "$work/api/lookup-response"
run_gate 'Anything' 'cyrus/mag-1-x'
[ "$STATUS" -ne 0 ] && ok "garbage: holds" || bad "passed on garbage"

# ----------------------------------------------------------------------------

run_rerun() {
  OUTPUT="$(PATH="$work/bin:$PATH" FAKE_CURL_DIR="$work/api" LINEAR_API_KEY="${KEY-lin_api_secret}" GH_REPO=o/r "$RERUN" 2>&1)"
  STATUS=$?
}
PRS='[
 {"number":70,"statusCheckRollup":[{"name":"Incident gate","conclusion":"FAILURE","detailsUrl":"https://github.com/o/r/actions/runs/1/job/111"},{"name":"API Tests (PHPUnit)","conclusion":"SUCCESS","detailsUrl":"https://github.com/o/r/actions/runs/1/job/112"}]},
 {"number":71,"statusCheckRollup":[{"name":"Incident gate","conclusion":"SUCCESS","detailsUrl":"https://github.com/o/r/actions/runs/2/job/221"},{"name":"API Tests (PHPUnit)","conclusion":"FAILURE","detailsUrl":"https://github.com/o/r/actions/runs/2/job/222"}]},
 {"number":72,"statusCheckRollup":[{"name":"Incident gate","conclusion":"FAILURE","detailsUrl":"https://github.com/o/r/actions/runs/3/job/331"}]},
 {"number":73,"statusCheckRollup":[]},
 {"number":74,"isCrossRepository":true,"statusCheckRollup":[{"name":"Incident gate","conclusion":"FAILURE","detailsUrl":"https://github.com/o/r/actions/runs/4/job/441"}]}
]'

printf '\n\033[1mThe freeze lifts: every red gate re-runs, nothing else\033[0m\n'
frozen_by "" ""
printf '%s' "$PRS" > "$work/api/prs"
run_rerun
[ "$STATUS" -eq 0 ] && ok "exits 0" || bad "exit $STATUS — $OUTPUT"
[ "$(paste -sd' ' "$work/api/reruns")" = "repos/o/r/actions/jobs/111/rerun repos/o/r/actions/jobs/331/rerun" ] \
  && ok "re-runs the red gates of #70 and #72 only, not a fork's #74" || bad "re-ran: $(cat "$work/api/reruns")"

printf '%s' 'not json' > "$work/api/prs"
: > "$work/api/reruns"
run_rerun
[ "$STATUS" -eq 0 ] && ok "an unreadable PR list: exits 0" || bad "exit $STATUS — $OUTPUT"
echo "$OUTPUT" | grep -q '::warning::' && ok "and warns" || bad "silent: $OUTPUT"

printf '\n\033[1mStill frozen, or Linear silent: nothing re-runs\033[0m\n'
frozen_by "MAG-184" ""
printf '%s' "$PRS" > "$work/api/prs"
run_rerun
[ "$STATUS" -eq 0 ] && ok "exits 0" || bad "exit $STATUS — $OUTPUT"
[ ! -s "$work/api/reruns" ] && ok "re-runs nothing while frozen" || bad "re-ran: $(cat "$work/api/reruns")"
echo "$OUTPUT" | grep -q 'still frozen by MAG-184' && ok "says so" || bad "silent: $OUTPUT"

frozen_by "" ""
printf '%s' "$PRS" > "$work/api/prs"
printf '%s' '{"errors":[{"message":"down"}]}' > "$work/api/lookup-response"
run_rerun
[ "$STATUS" -eq 0 ] && ok "Linear error: exits 0" || bad "exit $STATUS — $OUTPUT"
[ ! -s "$work/api/reruns" ] && ok "re-runs nothing it cannot justify" || bad "re-ran: $(cat "$work/api/reruns")"
echo "$OUTPUT" | grep -q '::warning::Linear cannot say' && ok "warns" || bad "silent: $OUTPUT"

printf '\n\033[1mA re-run GitHub refuses only warns\033[0m\n'
frozen_by "" ""
printf '%s' "$PRS" > "$work/api/prs"
: > "$work/api/rerun-fails"
run_rerun
[ "$STATUS" -eq 0 ] && ok "exits 0" || bad "exit $STATUS — $OUTPUT"
[ "$(grep -c 'could not re-run' <<<"$OUTPUT")" -eq 2 ] && ok "warns for each and tries them all" || bad "output: $OUTPUT"

printf '\n'
[ "$failures" -eq 0 ] && echo "All incident-gate tests passed." || echo "$failures failure(s)."
exit "$failures"
