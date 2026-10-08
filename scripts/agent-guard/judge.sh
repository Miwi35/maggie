#!/usr/bin/env bash
#
# Judge a pull request for the guard rails (MAG-128, MAG-262).
#
#   scripts/agent-guard/judge.sh <pr>
#
# Prints the findings of check.sh, one `<code>: <why>` per line, and exits 0 (the
# PR may merge itself) or 10 (a human must merge it). Needs GH_REPO and gh.
#
# A pull request labelled `batch` is a batch of the merge train: the dispatcher
# groups up to AGENT_MAX_BATCH_PRS (default 3) ready PRs into one, so a single CI
# and a single deployment carry them. Its body lists them, one list item or table
# row each, the first `#<number>` of the line naming the PR:
#
#   - #150 Show a saved recipe at once (Fixes MAG-117)
#   - #151 Accept a recipe record sent back as the admin sends it (Fixes MAG-255)
#
# Judged on its total, a batch would trip the size limit on its own. It is judged
# PR by PR instead, each with the rules it would meet alone, and it is accepted
# only if every one passes and nothing else is in it:
#   - each listed PR runs through check.sh (size, sensitive paths, permissions,
#     migrations, disabled tests, skipped hooks) and must not be flagged
#     `needs-human`, which is how a PR a human has to look at is marked;
#   - the lines the batch adds and removes, file by file, are exactly the sum of
#     the lines of the listed PRs: nothing slipped in on the way, nothing dropped.
#     (A line one PR adds and another removes cancels in the batch, which then
#     differs from the sum: the doubt goes to a human.)
# The label alone is not enough: the branch must be `train/batch-<n>`.
# The findings name the PR they come from: `oversize: #151: 912 lines…`.

set -euo pipefail

PR="${1:?usage: judge.sh <pr>}"
GH="${GH:-gh}"
BATCH_MAX="${AGENT_MAX_BATCH_PRS:-3}"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

# diff_of <pr> <file> — GitHub renders no diff above 20 000 lines.
diff_of() { $GH pr diff "$1" > "$2" 2>/dev/null; }

# check_diff <diff-file> — check.sh on a diff, its exit status kept.
check_diff() {
  local status=0
  "$HERE/check.sh" < "$1" || status=$?
  return "$status"
}

# normalize — one line per line added or removed, and per mode change, rename or
# binary file, keyed by file. Hunk headers (line numbers) and index lines are left
# out: they depend on the base the diff was made against.
normalize() {
  awk '
    /^diff --git / { file = $4; sub(/^b\//, "", file); hunk = 0; next }
    hunk == 0 && /^(old mode|new mode|new file mode|deleted file mode|rename from|rename to|copy from|copy to) / { print file "\t#" $0; next }
    /^@@ / { hunk = 1; next }
    /^(Binary files|GIT binary patch)/ { print file "\t#" $0; next }
    hunk == 1 && /^[-+]/ { print file "\t" $0 }
  ' "$@" | LC_ALL=C sort
}

meta="$($GH pr view "$PR" --json labels,body,headRefName)"
if ! jq -e '.labels | map(.name) | index("batch")' <<<"$meta" > /dev/null; then
  if diff_of "$PR" "$work/diff"; then
    status=0
    check_diff "$work/diff" || status=$?
    exit "$status"
  fi
  echo "oversize: the diff is too large for GitHub to render, split the ticket"
  exit 10
fi

findings="$work/findings"
: > "$findings"
total=0

# The label alone must not unlock the batch rules: only the dispatcher's branch does.
case "$(jq -r '.headRefName // ""' <<<"$meta")" in
  train/batch-*) ;;
  *) echo "batch-branch: the label batch is only for a branch train/batch-<n>" >> "$findings" ;;
esac

listed="$(jq -r '.body // ""' <<<"$meta" \
  | sed -nE 's/^[[:space:]]*([-*|]|[0-9]+\.)[^#]*#([0-9]+).*/\2/p' | awk -v self="$PR" '$0 != self && !seen[$0]++')"
count="$(grep -c . <<<"$listed" || true)"

[ "$count" -gt 0 ] || echo "batch-empty: the description lists no pull request (one list item per PR, starting with its #number)" >> "$findings"
[ "$count" -le "$BATCH_MAX" ] || echo "batch-size: $count pull requests listed, a batch holds at most $BATCH_MAX" >> "$findings"

readable=true
: > "$work/sum"
for n in $listed; do
  if ! pr_meta="$($GH pr view "$n" --json labels,state)"; then
    echo "batch-pr: #$n cannot be read" >> "$findings"
    readable=false
    continue
  fi
  [ "$(jq -r .state <<<"$pr_meta")" = OPEN ] || echo "batch-pr: #$n is not open" >> "$findings"
  if jq -e '.labels | map(.name) | index("needs-human")' <<<"$pr_meta" > /dev/null; then
    echo "batch-pr: #$n is flagged needs-human: a human has to look at it first" >> "$findings"
  fi

  if ! diff_of "$n" "$work/diff-$n"; then
    echo "oversize: #$n: the diff is too large for GitHub to render, split the ticket" >> "$findings"
    readable=false
    continue
  fi
  status=0
  check_diff "$work/diff-$n" > "$work/out-$n" || status=$?
  [ "$status" -eq 0 ] || [ "$status" -eq 10 ] || exit "$status"
  sed -nE "s/^([a-z-]+): ([^[:space:]].*)$/\1: #$n: \2/p" "$work/out-$n" | grep -v '^info: ' >> "$findings" || true
  counted="$(sed -n 's/^info: \([0-9]*\) counted lines.*/\1/p' "$work/out-$n")"
  total=$((total + ${counted:-0}))
  normalize "$work/diff-$n" >> "$work/sum"
done

# Only a batch whose every PR could be read is compared with its sum.
if [ "$readable" = true ] && [ "$count" -gt 0 ]; then
  if diff_of "$PR" "$work/diff-batch"; then
    normalize "$work/diff-batch" > "$work/batch"
    # A binary diff shows no content: its bytes cannot be compared with the PRs'.
    if grep -qP '\t#(Binary files|GIT binary patch)' "$work/batch"; then
      echo "batch-content: the batch carries a binary file, whose content cannot be compared with its pull requests: merge that PR alone" >> "$findings"
    fi
    LC_ALL=C sort -o "$work/sum" "$work/sum"
    extra="$(LC_ALL=C comm -23 "$work/batch" "$work/sum")"
    missing="$(LC_ALL=C comm -13 "$work/batch" "$work/sum")"
    if [ -n "$extra$missing" ]; then
      files="$(printf '%s\n%s\n' "$extra" "$missing" | cut -f1 | sed '/^$/d' | sort -u | head -5 | paste -sd, | sed 's/,/, /g')"
      echo "batch-content: the batch is not the sum of its pull requests: $(grep -c . <<<"$extra" || true) line(s) in no listed PR, $(grep -c . <<<"$missing" || true) line(s) of the listed PRs missing (files: $files)" >> "$findings"
    fi
  else
    echo "batch-content: the batch diff is too large for GitHub to render, it cannot be compared with its pull requests" >> "$findings"
  fi
fi

cat "$findings"
limit="${AGENT_MAX_DIFF_LINES:-0}"
if [ "$limit" -gt 0 ]; then each="limit $limit each"; else each="no size limit"; fi
echo "info: batch of $count pull request(s), $total counted lines in all, $each"
[ ! -s "$findings" ] || exit 10
