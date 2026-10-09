#!/usr/bin/env bash
# shellcheck disable=SC2015 # `cond && ok || bad`: ok and bad cannot fail
#
# Tests of infra/scripts/migrations-order.sh (merge queue, 9 Oct.), on a
# throwaway git repository: a change may only add migrations newer than every
# migration of its base, or production (merge order) and a fresh database
# (version order) apply them in different orders.
#
# Usage: infra/scripts/tests/migrations-order.test.sh

set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SCRIPT="$HERE/../migrations-order.sh"
failures=0

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

ok()  { printf '  \033[32m✓\033[0m %s\n' "$1"; }
bad() { printf '  \033[31m✗\033[0m %s\n' "$1"; failures=$((failures + 1)); }

g() { git -C "$work/repo" -c user.name=t -c user.email=t@t -c commit.gpgsign=false "$@"; }

# fresh_repo — main with two migrations; prints nothing, sets BASE.
fresh_repo() {
  rm -rf "$work/repo"
  mkdir -p "$work/repo/api/migrations"
  g init -q
  touch "$work/repo/api/migrations/Version20261008013000.php" "$work/repo/api/migrations/Version20261008210000.php"
  g add -A && g commit -qm base
  BASE="$(g rev-parse HEAD)"
}

# change <file>... — a commit on top of BASE adding these files; sets HEAD_SHA.
change() {
  for f in "$@"; do mkdir -p "$work/repo/$(dirname "$f")"; echo x > "$work/repo/$f"; done
  g add -A && g commit -qm change
  HEAD_SHA="$(g rev-parse HEAD)"
}

judge() {
  OUTPUT="$(cd "$work/repo" && "$SCRIPT" "$BASE" "$HEAD_SHA" 2>&1)"
  STATUS=$?
}

printf '\n\033[1mNo migration added\033[0m\n'
fresh_repo
change api/src/Foo.php
judge
[ "$STATUS" -eq 0 ] && ok "passes" || bad "exit $STATUS — $OUTPUT"

printf '\n\033[1mA migration newer than every one of main\033[0m\n'
fresh_repo
change api/migrations/Version20261009120000.php
judge
[ "$STATUS" -eq 0 ] && ok "passes" || bad "exit $STATUS — $OUTPUT"

printf '\n\033[1mA migration older than the newest of main (cut before another landed)\033[0m\n'
fresh_repo
change api/migrations/Version20261008150000.php
judge
[ "$STATUS" -ne 0 ] && ok "fails" || bad "passed — $OUTPUT"
grep -qF 'Version20261008150000' <<<"$OUTPUT" && ok "names the file" || bad "silent about the file — $OUTPUT"
grep -qF 'Version20261008210000' <<<"$OUTPUT" && ok "names main's newest migration" || bad "silent about main's newest — $OUTPUT"

printf '\n\033[1mTwo added, one late\033[0m\n'
fresh_repo
change api/migrations/Version20261009120000.php api/migrations/Version20261001000000.php
judge
[ "$STATUS" -ne 0 ] && ok "fails" || bad "passed — $OUTPUT"
grep -qF 'Version20261009120000' <<<"$OUTPUT" && ok "lists the one in order too" || bad "$OUTPUT"

printf '\n\033[1mThe first migration of an empty directory\033[0m\n'
rm -rf "$work/repo"; mkdir -p "$work/repo"; g init -q
touch "$work/repo/README"; g add -A; g commit -qm base; BASE="$(g rev-parse HEAD)"
change api/migrations/Version20261009120000.php
judge
[ "$STATUS" -eq 0 ] && ok "passes" || bad "exit $STATUS — $OUTPUT"

printf '\n\033[1mAn edited migration is not an added one\033[0m\n'
fresh_repo
echo y > "$work/repo/api/migrations/Version20261008013000.php"
g add -A && g commit -qm edit
HEAD_SHA="$(g rev-parse HEAD)"
judge
[ "$STATUS" -eq 0 ] && ok "passes (the destructive-migration rail judges edits)" || bad "exit $STATUS — $OUTPUT"

printf '\n'
if [ "$failures" -gt 0 ]; then
  printf '\033[31m%d failing case(s)\033[0m\n' "$failures"
  exit 1
fi
printf '\033[32mAll cases pass\033[0m\n'
