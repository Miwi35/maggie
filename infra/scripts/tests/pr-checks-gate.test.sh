#!/usr/bin/env bash
# shellcheck disable=SC2015 # `cond && ok || bad`: ok and bad cannot fail
#
# Tests of infra/scripts/pr-checks-gate.sh (MAG-267).
#
# The aggregator must be red whenever a check was skipped for a reason other than
# the path filter: a run cancelled by a newer one, a job whose needs
# failed. A skipped required check counts as green on GitHub, which merged five
# pull requests that no test had run on.
#
# Usage: infra/scripts/tests/pr-checks-gate.test.sh

set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SCRIPT="$HERE/../pr-checks-gate.sh"
failures=0

ok()  { printf '  \033[32m✓\033[0m %s\n' "$1"; }
bad() { printf '  \033[31m✗\033[0m %s\n' "$1"; failures=$((failures + 1)); }

FILTERED='api-lint api-test agent-lint agent-test ciqual-lint ciqual-test admin-lint admin-test mobile-unit cron-image'
ALWAYS='e2e-stack e2e-mobile infra-scripts incident-gate'

# needs <result-of-changes> <result-of-filtered-jobs> <filter-value> <result-of-always-jobs>
needs() {
  jq -n --arg changes "$1" --arg filtered "$2" --arg flag "$3" --arg always "$4" \
    --arg filteredJobs "$FILTERED" --arg alwaysJobs "$ALWAYS" '
    {changes: {result: $changes, outputs: {
       api: $flag, agent: $flag, ciqual: $flag, admin: $flag, mobile_unit: $flag,
       cron: $flag, e2e: $flag, mobile: $flag}}}
    + ($filteredJobs | split(" ") | map({(.): {result: $filtered}}) | add)
    + ($alwaysJobs | split(" ") | map({(.): {result: $always}}) | add)'
}

# with <needs-json> <jq filter> — one job's result changed
with() { jq -c "$2" <<<"$1"; }

# run <needs-json>
run() {
  OUTPUT="$(NEEDS="$1" "$SCRIPT" 2>&1)"
  STATUS=$?
}

green()  { [ "$STATUS" -eq 0 ] && ok "$1: green" || bad "$1: exit $STATUS — $OUTPUT"; }
red()    { [ "$STATUS" -ne 0 ] && ok "$1: red" || bad "$1: exit 0 although it must be red"; }

ALL_GREEN="$(needs success success true success)"
DOCS_ONLY="$(needs success skipped false success)"

printf '\n\033[1mA ready pull request whose CI ran and is green\033[0m\n'
run "$ALL_GREEN"
green "every job succeeded"

printf '\n\033[1mA pull request that only touches documentation\033[0m\n'
run "$DOCS_ONLY"
green "Detect changes skipped the filtered jobs, the always-on ones passed"

printf '\n\033[1mA draft, or a merge group: the same verdict as a ready pull request\033[0m\n'
run "$ALL_GREEN"
green "a draft whose CI ran and is green (it runs its CI since 9 Oct.)"
run "$(needs skipped skipped false skipped)"
red "a run whose jobs were all skipped"

printf '\n\033[1mA run cancelled, or skipped by something else than Detect changes\033[0m\n'
run "$(needs cancelled cancelled true cancelled)"
red "a cancelled run (a newer push cancelled it)"
run "$(needs skipped skipped false skipped)"
red "a ready run whose Detect changes was skipped"
run "$(needs success skipped true success)"
red "filtered jobs skipped while Detect changes asked for them"
run "$(with "$ALL_GREEN" '.["api-test"].result = "skipped"')"
red "one wanted job skipped"
run "$(with "$DOCS_ONLY" '.["e2e-stack"].result = "skipped"')"
red "the e2e aggregator skipped, even though no e2e was wanted"
run "$(with "$DOCS_ONLY" '.["incident-gate"].result = "skipped"')"
red "the incident gate skipped"

printf '\n\033[1mA job that failed or was cancelled\033[0m\n'
run "$(with "$ALL_GREEN" '.["admin-test"].result = "failure"')"
red "a failed job"
run "$(with "$ALL_GREEN" '.["e2e-mobile"].result = "cancelled"')"
red "a cancelled job"
run "$(with "$DOCS_ONLY" '.["cron-image"].result = "failure"')"
red "a failed job that its filter had switched off"
run "$(with "$ALL_GREEN" '.changes.result = "failure"')"
red "Detect changes failed"

printf '\n\033[1mA job the script does not know about\033[0m\n'
run "$(with "$ALL_GREEN" 'del(.["infra-scripts"])')"
red "a required job absent from needs (renamed in ci.yml)"
run "$(with "$DOCS_ONLY" 'del(.changes.outputs.api)')"
red "a skipped job whose filter is missing from Detect changes' outputs"

printf '\n\033[1mThe workflow and the script agree on the jobs\033[0m\n'
CI_YML="$HERE/../../../.github/workflows/ci.yml"
in_workflow="$(awk '/^  pr-checks:/{f=1} f && /^    needs:/{print; exit}' "$CI_YML" \
  | sed 's/.*\[\(.*\)\].*/\1/' | tr -d ' ' | tr ',' '\n' | grep -v '^changes$' | sort)"
in_script="$(sed -n '/^REQUIRED=(/,/^)/p' "$SCRIPT" | grep -E '^ +[a-z0-9-]+:' | sed 's/^ *//; s/:.*//' | sort)"
[ -n "$in_script" ] && [ "$in_workflow" = "$in_script" ] \
  && ok "the needs of PR checks passed are the script's required jobs" \
  || bad "ci.yml needs: $(echo "$in_workflow" | tr '\n' ' ') — script: $(echo "$in_script" | tr '\n' ' ')"

if [ "$failures" -gt 0 ]; then
  printf '\n\033[31m%d failure(s)\033[0m\n' "$failures"
  exit 1
fi
printf '\n\033[32mAll good\033[0m\n'
