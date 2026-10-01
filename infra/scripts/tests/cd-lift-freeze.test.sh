#!/usr/bin/env bash
# shellcheck disable=SC2015 # `cond && ok || bad`: ok and bad cannot fail
#
# Test of the condition of the `lift-freeze` job in .github/workflows/cd.yml (MAG-199).
#
# The job takes the shipped tickets out of « Emergency » after a green release
# (MAG-184). Its `if:` had no status function, so GitHub added an implicit
# success() over every ancestor job: a build skipped because its image did not
# change skipped the job too, and the ticket stayed in « Emergency » while
# `Incident gate` held every PR. This reads the job's `needs` and `if:` from the
# workflow and replays GitHub's rule on release scenarios: a green deploy and
# smoke lift the freeze whatever the builds did, a red release never does.
#
# Usage: infra/scripts/tests/cd-lift-freeze.test.sh

set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CD="${CD:-$HERE/../../../.github/workflows/cd.yml}"
failures=0

ok()  { printf '  \033[32m✓\033[0m %s\n' "$1"; }
bad() { printf '  \033[31m✗\033[0m %s\n' "$1"; failures=$((failures + 1)); }

# job <TAB> needs (space-separated) <TAB> if (one line), for every job.
jobs_table() {
  awk '
    function flush() { if (job != "") print job "\t" needs "\t" cond; job = ""; needs = ""; cond = ""; infold = 0 }
    /^jobs:/ { injobs = 1; next }
    !injobs { next }
    /^  [A-Za-z0-9_-]+:[ ]*$/ { flush(); job = $1; sub(/:$/, "", job); next }
    job == "" { next }
    /^    needs:/ {
      infold = 0; v = $0; sub(/^    needs:[ ]*/, "", v); gsub(/[\[\],]/, " ", v); needs = v; next
    }
    /^    if:/ {
      v = $0; sub(/^    if:[ ]*/, "", v)
      if (v ~ /^[>|][-+]?$/) { infold = 1; cond = ""; next }
      infold = 0; cond = v; next
    }
    infold && /^      / { v = $0; sub(/^ +/, "", v); cond = cond (cond == "" ? "" : " ") v; next }
    /^    [A-Za-z0-9_-]+:/ { infold = 0 }
    END { flush() }
  ' "$CD"
}

TABLE="$(jobs_table)"
field() { printf '%s\n' "$TABLE" | awk -F'\t' -v j="$1" -v n="$2" '$1 == j { print $n }'; }

# Every ancestor of a job, through `needs`.
ancestors() {
  local seen=" " queue="$1" cur n
  while [ -n "$queue" ]; do
    cur="${queue%% *}"; queue="${queue#"$cur"}"; queue="${queue# }"
    for n in $(field "$cur" 2); do
      case "$seen" in *" $n "*) ;; *) seen="$seen$n "; queue="$queue $n" ;; esac
    done
  done
  echo "$seen"
}

declare -A R   # job -> result of the scenario being replayed

# Does a job run? Replays GitHub's rule: an `if:` with no status function is
# `success() && (<if>)`, and success() needs every ancestor to have succeeded
# (a skipped one does not count); one that has `always()` is left to its own text.
runs() {
  local job="$1" expr="$2" a
  local has_status=0
  [[ "$expr" =~ (always|failure|cancelled|success)\(\) ]] && has_status=1
  if [ "$has_status" = 0 ]; then
    for a in $(ancestors "$job"); do
      [ "${R[$a]:-skipped}" = success ] || return 1
    done
  fi
  [ -n "$expr" ] || return 0
  local js
  js="$expr"
  js="${js//always()/true}"
  js="${js//\!cancelled()/true}"
  js="$(printf '%s' "$js" | sed -E "s/needs\.([A-Za-z0-9_-]+)\.result[ ]*==[ ]*'([a-z]+)'/[[ \"\${R[\1]:-skipped}\" == \2 ]]/g; s/needs\.([A-Za-z0-9_-]+)\.result[ ]*!=[ ]*'([a-z]+)'/[[ \"\${R[\1]:-skipped}\" != \2 ]]/g")"
  # What remains must be `&&`, `||`, parentheses and the words above: anything
  # else is a construct this replay does not model.
  if printf '%s' "$js" | sed -E 's/\[\[ "\$\{R\[[A-Za-z0-9_-]+\]:-skipped\}" (==|!=) [a-z]+ \]\]//g; s/true//g; s/&&|\|\|//g; s/[() ]//g' | grep -q .; then
    echo "unsupported expression: $expr" >&2; exit 2
  fi
  eval "$js"
}

scenario() { # name, expected (runs|stays), then job=result pairs
  local name="$1" want="$2" kv; shift 2
  R=()
  for kv in "$@"; do R["${kv%%=*}"]="${kv#*=}"; done
  local got=stays
  runs lift-freeze "$(field lift-freeze 3)" && got=runs
  [ "$got" = "$want" ] && ok "$name: lift-freeze $got" || bad "$name: lift-freeze: $got, expected $want"
}

echo
echo "1. The job in the workflow"
[ -n "$(field lift-freeze 1)" ] && ok "lift-freeze is in cd.yml" || { bad "lift-freeze is not in cd.yml"; exit 1; }
printf '  needs: %s\n  if:    %s\n' "$(field lift-freeze 2)" "$(field lift-freeze 3)"
case "$(field lift-freeze 3)" in *"always()"*) ok "the condition has always(), so GitHub adds no implicit success() over the builds" ;; *) bad "no always(): a skipped build skips the job" ;; esac

echo
echo "2. A green release lifts the freeze, whatever the builds did"
GREEN=(gate=success changes=success deploy=success smoke=success)
scenario "every build ran"                 runs "${GREEN[@]}" build-php=success build-nginx=success build-agent=success build-ciqual=success
scenario "no build ran (nothing rebuilt)"  runs "${GREEN[@]}" build-php=skipped build-nginx=skipped build-agent=skipped build-ciqual=skipped
scenario "only the agent was rebuilt"      runs "${GREEN[@]}" build-php=skipped build-nginx=skipped build-agent=success build-ciqual=skipped
scenario "everything but ciqual rebuilt"  runs "${GREEN[@]}" build-php=success build-nginx=success build-agent=success build-ciqual=skipped

echo
echo "3. A red or stopped release never lifts it"
scenario "the deploy failed"               stays gate=success changes=success deploy=failure smoke=skipped build-php=success build-nginx=success build-agent=skipped build-ciqual=skipped
scenario "the smoke suite failed"          stays "${GREEN[@]/smoke=success/smoke=failure}" build-php=skipped build-nginx=skipped build-agent=success build-ciqual=skipped
scenario "a build failed, no deploy"       stays gate=success changes=success build-php=failure build-nginx=skipped build-agent=success build-ciqual=skipped deploy=skipped smoke=skipped
scenario "the smoke suite was cancelled"   stays "${GREEN[@]/smoke=success/smoke=cancelled}" build-php=skipped build-nginx=skipped build-agent=skipped build-ciqual=skipped
scenario "the gate stopped the run"        stays gate=skipped changes=skipped deploy=skipped smoke=skipped build-php=skipped build-nginx=skipped build-agent=skipped build-ciqual=skipped

echo
echo "4. The test would have caught MAG-199"
R=(); for kv in "${GREEN[@]}" build-php=skipped build-nginx=skipped build-agent=skipped build-ciqual=skipped; do R["${kv%%=*}"]="${kv#*=}"; done
# `lift-freeze` with the condition it had: needs: smoke, if: needs.smoke.result == 'success'
old_needs="smoke"
TABLE="$(printf '%s\n' "$TABLE" | awk -F'\t' -v n="$old_needs" 'BEGIN { OFS = "\t" } $1 == "lift-freeze" { $2 = n } { print }')"
runs lift-freeze "needs.smoke.result == 'success'" && bad "the old condition lifts the freeze after skipped builds: the replay does not model GitHub's implicit success()" \
  || ok "the old condition (no always()) leaves the freeze after skipped builds"

echo
if [ "$failures" -eq 0 ]; then echo "All checks passed."; else echo "$failures check(s) failed."; exit 1; fi
