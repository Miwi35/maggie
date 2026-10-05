#!/usr/bin/env bash
# shellcheck disable=SC2015 # `cond && ok || bad`: ok and bad cannot fail
#
# Tests of scripts/agent-guard/judge.sh against a fake gh (MAG-262): a pull
# request labelled `batch` groups up to three ready PRs of the merge train. It is
# judged PR by PR, accepted only if each passes alone and the batch is exactly
# their sum, whatever its total size. A PR without the label is judged as ever.
#
# Usage: infra/scripts/tests/agent-guard-batch.test.sh

set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JUDGE="$HERE/../../../scripts/agent-guard/judge.sh"
failures=0

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

ok()  { printf '  \033[32m✓\033[0m %s\n' "$1"; }
bad() { printf '  \033[31m✗\033[0m %s\n' "$1"; failures=$((failures + 1)); }

# section <path> <line>... — one file of a diff, every line added.
section() {
  local path="$1"
  shift
  printf 'diff --git a/%s b/%s\n--- /dev/null\n+++ b/%s\n@@ -0,0 +1,%d @@\n' "$path" "$path" "$path" "$#"
  printf '+%s\n' "$@"
}

# bulk <path> <tag> <n> — a file of <n> added lines, none shared with another tag.
bulk() {
  local lines=() i
  for ((i = 0; i < $3; i++)); do lines+=("$2 line $i"); done
  section "$1" "${lines[@]}"
}

# modify <path> <removed> <added> — one file where a line is replaced.
modify() {
  printf 'diff --git a/%s b/%s\nindex 1111111..2222222 100644\n--- a/%s\n+++ b/%s\n@@ -10,3 +10,3 @@ context\n keep\n-%s\n+%s\n keep\n' \
    "$1" "$1" "$1" "$1" "$2" "$3"
}

fresh_world() {
  rm -rf "$work/gh"
  mkdir -p "$work/gh"
  : > "$work/gh/calls"
}

# put <pr> <state> <labels,comma> <body> — the PR's metadata; its diff on stdin.
put() {
  local labels
  labels="$(jq -cn --arg l "$3" '$l | split(",") | map(select(. != "") | {name: .})')"
  jq -n --arg state "$2" --argjson labels "$labels" --arg body "$4" --arg branch "${BRANCH:-cyrus/mag-1-x}" \
    '{state: $state, labels: $labels, body: $body, headRefName: $branch}' > "$work/gh/pr-$1.json"
  cat > "$work/gh/pr-$1.diff"
}

# body <number>... — a batch description listing those PRs.
body() {
  printf 'Batch of the merge train.\n\n'
  for n in "$@"; do printf -- '- #%s Some change (Fixes MAG-%s)\n' "$n" "$((n + 100))"; done
}

# put_batch <pr> <number>... — the batch labelled `batch` listing those PRs; its
# diff on stdin.
put_batch() {
  local n="$1"
  shift
  BRANCH="${BRANCH:-train/batch-1}" put "$n" OPEN "batch" "$(body "$@")"
}

# judge <pr> — findings in $OUTPUT, exit status in $STATUS.
judge() {
  OUTPUT="$(FAKE_GH_DIR="$work/gh" GH="$HERE/fake-gh-pr.sh" "$JUDGE" "$1" 2>&1)"
  STATUS=$?
}

accepted() {
  [ "$STATUS" -eq 0 ] && ok "$1" || bad "$1 — exit $STATUS: $OUTPUT"
}
# refused <name> <code> [<text>] — handed to a human with this finding.
refused() {
  if [ "$STATUS" -eq 10 ] && grep -q "^$2: " <<<"$OUTPUT" && { [ -z "${3-}" ] || grep -qF -- "$3" <<<"$OUTPUT"; }; then
    ok "$1"
  else
    bad "$1 — expected $2 ${3-}, got ($STATUS): $OUTPUT"
  fi
}

printf '\n\033[1mThree acceptable PRs, 1500 lines in all: the batch passes\033[0m\n'
fresh_world
bulk api/src/A.php a 500 | put 150 OPEN "" "A"
bulk agent/app/b.py b 500 | put 151 OPEN "" "B"
bulk admin/src/c.ts c 500 | put 152 OPEN "" "C"
{ bulk api/src/A.php a 500; bulk agent/app/b.py b 500; bulk admin/src/c.ts c 500; } | put_batch 160 150 151 152
judge 160
accepted "accepted although the total is over the 800 lines of one PR"
grep -q '^info: batch of 3' <<<"$OUTPUT" && ok "says how many PRs it judged" || bad "silent about the batch — $OUTPUT"
# The same lines, one PR, no label: the limit applies to the whole.
{ bulk api/src/A.php a 500; bulk agent/app/b.py b 500; bulk admin/src/c.ts c 500; } | put 161 OPEN "" "not a batch"
judge 161
refused "the same diff without the label is oversize" oversize

printf '\n\033[1mThe order and the form of the list do not matter\033[0m\n'
fresh_world
bulk api/src/A.php a 5 | put 150 OPEN "" "A"
bulk agent/app/b.py b 5 | put 151 OPEN "" "B"
{ bulk api/src/A.php a 5; bulk agent/app/b.py b 5; } | put_batch 160 150 151
printf 'Batch\n\n| PR | Tickets |\n|---|---|\n| #151 | MAG-251 |\n| #150 | MAG-250 |\n\n1. Not a PR: see MAG-9\n' \
  | { jq -Rs '{state: "OPEN", labels: [{name: "batch"}], body: ., headRefName: "train/batch-1"}' > "$work/gh/pr-160.json"; }
judge 160
accepted "a table lists the PRs too"

printf '\n\033[1mA PR refused alone refuses the batch\033[0m\n'
fresh_world
bulk api/src/A.php a 50 | put 150 OPEN "" "A"
bulk api/src/Big.php big 801 | put 151 OPEN "" "B"
bulk api/src/C.php c 50 | put 152 OPEN "" "C"
{ bulk api/src/A.php a 50; bulk api/src/Big.php big 801; bulk api/src/C.php c 50; } | put_batch 160 150 151 152
judge 160
refused "an oversize PR" oversize "#151"
! grep -q '#150\|#152' <<<"$OUTPUT" && ok "names only the one that fails" || bad "blames the others — $OUTPUT"

fresh_world
bulk api/src/A.php a 5 | put 150 OPEN "" "A"
section infra/k8s/php-deployment.yaml 'replicas: 3' | put 151 OPEN "" "B"
{ bulk api/src/A.php a 5; section infra/k8s/php-deployment.yaml 'replicas: 3'; } | put_batch 160 150 151
judge 160
refused "an infra PR" infra-path "#151"

fresh_world
bulk api/src/A.php a 5 | put 150 OPEN "" "A"
section api/config/packages/security.yaml 'security:' | put 151 OPEN "" "B"
{ bulk api/src/A.php a 5; section api/config/packages/security.yaml 'security:'; } | put_batch 160 150 151
judge 160
refused "an auth PR" permissions "#151"

fresh_world
bulk api/src/A.php a 5 | put 150 OPEN "" "A"
section scripts/agent-guard/check.sh 'exit 0' | put 151 OPEN "" "B"
{ bulk api/src/A.php a 5; section scripts/agent-guard/check.sh 'exit 0'; } | put_batch 160 150 151
judge 160
refused "a PR editing the guard" sensitive-path "#151"

fresh_world
bulk api/src/A.php a 5 | put 150 OPEN "needs-human" "A"
bulk api/src/B.php b 5 | put 151 OPEN "" "B"
{ bulk api/src/A.php a 5; bulk api/src/B.php b 5; } | put_batch 160 150 151
judge 160
refused "a PR a human has to look at first" batch-pr "#150 is flagged needs-human"

fresh_world
bulk api/src/A.php a 5 | put 150 CLOSED "" "A"
{ bulk api/src/A.php a 5; } | put_batch 160 150
judge 160
refused "a PR that is no longer open" batch-pr "#150 is not open"

printf '\n\033[1mThe batch must be the sum of its PRs\033[0m\n'
fresh_world
bulk api/src/A.php a 5 | put 150 OPEN "" "A"
bulk api/src/B.php b 5 | put 151 OPEN "" "B"
{ bulk api/src/A.php a 5; bulk api/src/B.php b 5; section api/src/Extra.php 'echo 1;'; } | put_batch 160 150 151
judge 160
refused "a file added on the way" batch-content "api/src/Extra.php"

fresh_world
bulk api/src/A.php a 5 | put 150 OPEN "" "A"
bulk api/src/B.php b 5 | put 151 OPEN "" "B"
{ bulk api/src/A.php a 5; bulk api/src/B.php b 5; section api/src/A.php 'echo 1;'; } | put_batch 160 150 151
judge 160
refused "a line added to a file of a PR" batch-content "api/src/A.php"

fresh_world
modify api/src/A.php 'return 1;' 'return 2;' | put 150 OPEN "" "A"
modify api/src/B.php 'return 1;' 'return 2;' | put 151 OPEN "" "B"
{ modify api/src/A.php 'return 1;' 'return 3;'; modify api/src/B.php 'return 1;' 'return 2;'; } | put_batch 160 150 151
judge 160
refused "a line changed on the way" batch-content "api/src/A.php"

fresh_world
bulk api/src/A.php a 5 | put 150 OPEN "" "A"
bulk api/src/B.php b 5 | put 151 OPEN "" "B"
{ bulk api/src/A.php a 5; } | put_batch 160 150 151
judge 160
refused "a PR missing from the batch" batch-content "api/src/B.php"

fresh_world
bulk api/src/A.php a 5 | put 150 OPEN "" "A"
{ bulk api/src/A.php a 5; printf 'diff --git a/run.sh b/run.sh\nold mode 100644\nnew mode 100755\n'; } | put_batch 160 150
judge 160
refused "a mode change slipped in" batch-content "run.sh"

printf '\n\033[1mA binary file cannot be compared: refused\033[0m\n'
fresh_world
bulk api/src/A.php a 5 | put 150 OPEN "" "A"
{ bulk api/src/A.php a 5; printf 'diff --git a/logo.png b/logo.png\nBinary files /dev/null and b/logo.png differ\n'; } | put_batch 160 150
judge 160
refused "a binary file in the batch" batch-content "binary file"

printf '\n\033[1mThe label alone is not a batch\033[0m\n'
fresh_world
bulk api/src/A.php a 5 | put 150 OPEN "" "A"
bulk api/src/A.php a 5 | BRANCH=cyrus/mag-9-sneaky put_batch 160 150
judge 160
refused "label batch on a cyrus/ branch" batch-branch "train/batch-"

printf '\n\033[1mTwo PRs on the same file, different lines: still the sum\033[0m\n'
fresh_world
modify docs/x.md 'old one' 'new one' | put 150 OPEN "" "A"
modify docs/x.md 'old two' 'new two' | put 151 OPEN "" "B"
printf 'diff --git a/docs/x.md b/docs/x.md\n--- a/docs/x.md\n+++ b/docs/x.md\n@@ -5,1 +5,1 @@\n-old one\n+new one\n@@ -30,1 +30,1 @@\n-old two\n+new two\n' | put_batch 160 150 151
judge 160
accepted "one section of the batch holds both PRs' hunks, with other line numbers"

printf '\n\033[1mThe size and the readability of the batch\033[0m\n'
fresh_world
for n in 150 151 152 153; do bulk "api/src/P$n.php" "p$n" 3 | put $n OPEN "" "P"; done
{ for n in 150 151 152 153; do bulk "api/src/P$n.php" "p$n" 3; done; } | put_batch 160 150 151 152 153
judge 160
refused "four PRs are too many" batch-size "4 pull requests"
AGENT_MAX_BATCH_PRS=4 judge 160
accepted "the limit can be changed"

fresh_world
bulk api/src/A.php a 5 | put 150 OPEN "" "A"
bulk api/src/A.php a 5 | put_batch 160
judge 160
refused "a batch listing nothing" batch-empty

fresh_world
bulk api/src/A.php a 5 | put_batch 160 150
judge 160
refused "a listed PR that does not exist" batch-pr "#150 cannot be read"

fresh_world
bulk api/src/A.php a 5 | put 150 OPEN "" "A"
bulk api/src/A.php a 5 | put_batch 160 150
touch "$work/gh/no-diff-160"
judge 160
refused "a batch GitHub cannot render" batch-content "too large"

fresh_world
bulk api/src/A.php a 5 | put 150 OPEN "" "A"
bulk api/src/A.php a 5 | put_batch 160 150
touch "$work/gh/no-diff-150"
judge 160
refused "a listed PR GitHub cannot render" oversize "#150"

fresh_world
bulk api/src/A.php a 5 | put_batch 160 160
judge 160
refused "a batch listing only itself" batch-empty

printf '\n\033[1mA PR without the label is judged as before\033[0m\n'
fresh_world
bulk api/src/A.php a 800 | put 150 OPEN "" "- #99 mentioned"
judge 150
accepted "800 lines are fine, and a list in the body means nothing"
bulk api/src/A.php a 801 | put 150 OPEN "" "A"
judge 150
refused "801 lines are too many" oversize
section infra/k8s/php-deployment.yaml 'replicas: 3' | put 150 OPEN "" "A"
judge 150
refused "infra still needs a human" infra-path
echo x | put 150 OPEN "" "A"
touch "$work/gh/no-diff-150"
judge 150
refused "a diff GitHub cannot render" oversize
[ "$(sort -u "$work/gh/calls" | paste -sd'|')" = "pr diff 150|pr view 150 --json labels,body,headRefName" ] && ok "reads nothing but the PR itself" || bad "read other PRs — $(cat "$work/gh/calls")"

printf '\n'
if [ "$failures" -gt 0 ]; then
  printf '\033[31m%d failing case(s)\033[0m\n' "$failures"
  exit 1
fi
printf '\033[32mAll cases pass\033[0m\n'
