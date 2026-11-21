#!/usr/bin/env bash
set -euo pipefail

# =============================================================================
# Is the lag of this head behind main « compatible »? (MAG-922)
# Usage: HEAD_SHA=<head of the PR> [MAIN_REF=origin/main] [PR=<number> \
#        GITHUB_REPOSITORY=<owner/repo>] behind-compat.sh [--github-output]
#        (from a checkout: it reads e2e/impact-map.yml and scripts/e2e/impacted.sh
#        of that checkout — the CI's `fresh` job and Cyrus's train run it from main)
#
# A ready PR behind main used to be rebased and its CI replayed in full, even
# when what landed on main has nothing to do with it. The verdict:
#   up_to_date  not behind main
#   compatible  behind, and nothing in common: the CI of the head runs as is (it
#               tests the merge commit with the main of the moment), the train
#               may merge without rebasing
#   replay      rebase and replay
#   unknown     doubt (commit not found, API silent, selection failing): the CI
#               runs, the train treats it as `replay`
#
# base = merge-base(HEAD_SHA, MAIN_REF); PR side = base..HEAD_SHA, main side =
# base..MAIN_REF. Compatible when NO rule sends it to `replay` (all are
# evaluated, each reason is listed):
#   conflict     PR given and GitHub says mergeable=false (null: 3 tries, then unknown)
#   common_file  a path changed on both sides
#   deleted      a file deleted or renamed on either side
#   ci_changed   main touched .github/**, infra/**, scripts/** or e2e/impact-map.yml
#   wide         on either side the zone selection (impacted.sh, no line map) is
#                full web/mobile or has a transversal / unmapped / registry=all file
#   journeys     both sides select a same journey, the core (`critical`) excluded
#
# Safety net: a compatible merge puts on main a tree no run tested, so the CI of
# main does not skip (tested-tree.sh) and the nightly plays every journey.
#
# stdout: one JSON line {verdict, head, main, base, behind_by, reasons:[{rule,detail}]}.
# Exit 0 for every verdict, 2 only when an input is missing.
# --github-output: also verdict, behind (true only for `replay`), base and main in
# $GITHUB_OUTPUT, and a table of the reasons in the job summary.
# =============================================================================

if [ -z "${HEAD_SHA:-}" ]; then
  echo "HEAD_SHA is not set" >&2
  exit 2
fi
export LC_ALL=C
MAIN_REF="${MAIN_REF:-origin/main}"
PR="${PR:-}"
GH="${GH:-gh}"
GITHUB_OUTPUT_FLAG=false
for arg in "$@"; do
  case "$arg" in
    --github-output) GITHUB_OUTPUT_FLAG=true ;;
    *) echo "unknown option $arg" >&2; exit 2 ;;
  esac
done

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
REASONS="$TMP/reasons.jsonl"
: > "$REASONS"

head_id="$HEAD_SHA"
main_id="$MAIN_REF"
base=''
behind_by=0
unknown=''

# reason <rule> <detail> — one entry; detail at most 10 items of a list on stdin.
reason() { jq -nc --arg rule "$1" --arg detail "$2" '{rule: $rule, detail: $detail}' >> "$REASONS"; }
reason_list() {
  local rule="$1" prefix="$2" items total
  items="$(cat)"
  [ -n "$items" ] || return 0
  total="$(grep -c . <<<"$items")"
  items="$(head -10 <<<"$items" | awk 'NR > 1 {printf ", "} {printf "%s", $0}')"
  [ "$total" -le 10 ] || items="$items, … +$((total - 10)) more"
  reason "$rule" "${prefix}${items}"
}

finish() {
  local verdict="$1" behind=false
  [ "$verdict" != replay ] || behind=true
  jq -nc --arg verdict "$verdict" --arg head "$head_id" --arg main "$main_id" --arg base "$base" \
    --argjson behind_by "$behind_by" --slurpfile reasons "$REASONS" \
    '{verdict: $verdict, head: $head, main: $main, base: $base, behind_by: $behind_by, reasons: $reasons}'
  if [ "$GITHUB_OUTPUT_FLAG" = true ]; then
    {
      echo "verdict=$verdict"
      echo "behind=$behind"
      echo "base=$base"
      echo "main=$main_id"
    } >> "${GITHUB_OUTPUT:-/dev/null}"
    {
      echo "### Head vs main: $verdict ($behind_by commit(s) behind)"
      if [ -s "$REASONS" ]; then
        echo
        echo "| Rule | Detail |"
        echo "|---|---|"
        jq -r '"| \(.rule) | \(.detail | gsub("\\|"; "\\|")) |"' "$REASONS"
      fi
    } >> "${GITHUB_STEP_SUMMARY:-/dev/null}"
    case "$verdict" in
      up_to_date) echo "::notice::up to date with main: the CI runs" ;;
      compatible) echo "::notice::$behind_by commit(s) behind main: compatible, the CI runs" ;;
      replay) echo "::notice::$behind_by commit(s) behind main: replay, Cyrus's train rebases it" ;;
      *) echo "::notice::head vs main: unknown, the CI runs; Cyrus's train rebases it" ;;
    esac
  fi
  exit 0
}
doubt() {
  reason unknown "$1"
  finish unknown
}

# The commits must be here; one fetch otherwise, and doubt when it is not enough.
if ! git cat-file -e "${HEAD_SHA}^{commit}" 2>/dev/null || ! git rev-parse -q --verify "${MAIN_REF}^{commit}" >/dev/null 2>&1; then
  git fetch --no-tags -q --filter=blob:none origin "$HEAD_SHA" main >/dev/null 2>&1 || true
fi
git cat-file -e "${HEAD_SHA}^{commit}" 2>/dev/null || doubt "head commit $HEAD_SHA not found"
head_id="$(git rev-parse "${HEAD_SHA}^{commit}")"
main_id="$(git rev-parse -q --verify "${MAIN_REF}^{commit}" 2>/dev/null)" || doubt "main ref $MAIN_REF not found"
base="$(git merge-base "$head_id" "$main_id" 2>/dev/null)" || doubt "no merge base between $HEAD_SHA and $MAIN_REF"
behind_by="$(git rev-list --count "$head_id..$main_id" 2>/dev/null)" || doubt "cannot count the commits behind $MAIN_REF"
[ "$behind_by" -gt 0 ] || finish up_to_date

pr_status="$TMP/pr.status"
main_status="$TMP/main.status"
git diff --name-status --no-renames "$base" "$head_id" > "$pr_status" || doubt "cannot diff the PR side"
git diff --name-status --no-renames "$base" "$main_id" > "$main_status" || doubt "cannot diff the main side"
cut -f2- "$pr_status" | sort -u > "$TMP/pr.files"
cut -f2- "$main_status" | sort -u > "$TMP/main.files"

# 1. conflict
if [ -n "$PR" ] && [ -n "${GITHUB_REPOSITORY:-}" ]; then
  mergeable=null
  for try in 1 2 3; do
    mergeable="$($GH api "repos/$GITHUB_REPOSITORY/pulls/$PR" --jq .mergeable 2>/dev/null)" || mergeable=error
    [ "$mergeable" = null ] || break
    [ "$try" -eq 3 ] || sleep 3
  done
  case "$mergeable" in
    false) reason conflict "GitHub says the PR conflicts with main" ;;
    true) ;;
    *) unknown="GitHub does not say whether the PR is mergeable ($mergeable)" ;;
  esac
fi

# 2. common_file
comm -12 "$TMP/pr.files" "$TMP/main.files" | reason_list common_file "changed on both sides: "

# 3. deleted
awk -F'\t' '$1 == "D" {print "PR: " $2}' "$pr_status" | reason_list deleted ""
awk -F'\t' '$1 == "D" {print "main: " $2}' "$main_status" | reason_list deleted ""

# 4. ci_changed
grep -E '^(\.github/|infra/|scripts/|e2e/impact-map\.yml$)' "$TMP/main.files" | reason_list ci_changed "main changed what the CI checks: " || true

# select_side <side-name> <ref> <files> <out> — the zone selection of one side.
select_side() {
  local tag="$1" ref="$2" files="$3" out="$4" registry_files="" diff_args=()
  registry_files="$( (. scripts/e2e/lib.sh; e2e_map_json | jq -r '.registries.files[]?') 2>/dev/null)" || registry_files=""
  local hits
  hits="$(grep -Fx -f <(printf '%s\n' "$registry_files") "$files" 2>/dev/null || true)"
  if [ -n "$hits" ]; then
    # shellcheck disable=SC2086 # one path per line, none with a space
    git diff -U0 --no-color --no-ext-diff --no-renames "$base" "$ref" -- $hits > "$TMP/$tag.diff" || return 1
    diff_args=(--diff "$TMP/$tag.diff")
  fi
  scripts/e2e/impacted.sh select "${diff_args[@]}" < "$files" > "$out" 2>/dev/null
}

if select_side pr "$head_id" "$TMP/pr.files" "$TMP/pr.sel" && select_side main "$main_id" "$TMP/main.files" "$TMP/main.sel"; then
  # 5. wide
  for side in pr main; do
    jq -r --arg side "$side" '
      ((.files // [])[] | select(.why as $w | ["transversal", "transversal_web", "transversal_mobile", "unmapped"] | index($w))
        | "\($side): \(.file) (\(.why))"),
      ((.files // [])[] | select(.why == "registry" and (.web == "all" or .mobile == "all")) | "\($side): \(.file) (registry)"),
      (if (.full.web or .full.mobile) then "\($side): the whole suite plays (full web=\(.full.web), mobile=\(.full.mobile))" else empty end)
    ' "$TMP/$side.sel" | awk '!seen[$0]++' | reason_list wide ""
  done

  # 6. journeys
  critical="$( (. scripts/e2e/lib.sh; e2e_map_json | jq -c "$E2E_JQ_DEFS"'[journeys[] | select(.critical) | .id]') 2>/dev/null)" \
    || doubt "the impact map cannot be read"
  jq -r --argjson critical "$critical" '[(.web // [])[], (.mobile // [])[]] | map(select(. as $j | $critical | index($j) | not)) | unique[]' "$TMP/pr.sel" > "$TMP/pr.journeys"
  jq -r --argjson critical "$critical" '[(.web // [])[], (.mobile // [])[]] | map(select(. as $j | $critical | index($j) | not)) | unique[]' "$TMP/main.sel" > "$TMP/main.journeys"
  comm -12 "$TMP/pr.journeys" "$TMP/main.journeys" | reason_list journeys "both sides play: "
else
  unknown="${unknown:+$unknown; }the e2e selection failed"
fi

if [ -s "$REASONS" ]; then
  finish replay
fi
[ -z "$unknown" ] || doubt "$unknown"
finish compatible
