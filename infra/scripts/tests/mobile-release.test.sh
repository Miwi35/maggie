#!/usr/bin/env bash
# shellcheck disable=SC2015 # `cond && ok || bad`: ok and bad cannot fail
#
# Tests of infra/scripts/mobile-release.sh on a throwaway git history (MAG-254).
#
# What matters: a deploy that leaves mobile/ alone publishes nothing, a mobile
# change is published once and not again by the next unrelated merge, and a
# publication that failed (no tag) is carried by the next one.
#
# Usage: infra/scripts/tests/mobile-release.test.sh

set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SCRIPT="$HERE/../mobile-release.sh"
failures=0

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

ok()  { printf '  \033[32m✓\033[0m %s\n' "$1"; }
bad() { printf '  \033[31m✗\033[0m %s\n' "$1"; failures=$((failures + 1)); }

repo="$work/repo"
git init -q "$repo"
g() { git -C "$repo" -c user.name=t -c user.email=t@t "$@"; }
commit() {
  mkdir -p "$repo/$(dirname "$2")"
  echo "$1" >> "$repo/$2"
  g add -A
  g commit -q -m "$1"
  g rev-parse HEAD
}
run() { OUTPUT="$(cd "$repo" && "$SCRIPT" "$@" 2>&1)"; STATUS=$?; }

c0=$(commit 'Initial commit' README.md)
c1=$(commit 'Add the agenda widget (MAG-10) (#1)' mobile/app/Widget.kt)
c2=$(commit 'Index the recipes (#2)' api/Recipe.php)
c3=$(commit 'Fix the chat topic on the phone (MAG-109) (#3)' mobile/app/Chat.kt)
c4=$(commit 'Rename a Taskfile target (#4)' Taskfile.yml)

printf '\n\033[1mNever published: the last commit alone decides\033[0m\n'
run range "$c2"
[ "$STATUS" -eq 0 ] && ok "exits 0" || bad "exit $STATUS — $OUTPUT"
printf '%s' "$OUTPUT" | grep -qx 'mobile=false' && ok "an API-only commit publishes nothing" || bad "got: $OUTPUT"
run range "$c1"
printf '%s' "$OUTPUT" | grep -qx 'mobile=true' && ok "a commit under mobile/ is published" || bad "got: $OUTPUT"
printf '%s' "$OUTPUT" | grep -qx 'mobile_base=' && ok "with no base" || bad "got: $OUTPUT"
run range "$c0"
printf '%s' "$OUTPUT" | grep -qx 'mobile=false' && ok "the root commit does not crash" || bad "got: $OUTPUT"

printf '\n\033[1mAfter a publication: only what came since counts\033[0m\n'
g tag mobile-12 "$c1"
run range "$c2"
printf '%s' "$OUTPUT" | grep -qx 'mobile=false' && ok "an unrelated merge after the tag publishes nothing" || bad "got: $OUTPUT"
printf '%s' "$OUTPUT" | grep -qx "mobile_base=$c1" && ok "the base is the tagged commit" || bad "got: $OUTPUT"
run range "$c4"
printf '%s' "$OUTPUT" | grep -qx 'mobile=true' && ok "a mobile commit between the tag and now is still carried by a later merge" || bad "got: $OUTPUT"

printf '\n\033[1mThe newest tag is the base, by number\033[0m\n'
g tag mobile-9 "$c0"
g tag mobile-100 "$c3"
run range "$c4"
printf '%s' "$OUTPUT" | grep -qx 'mobile=false' && ok "mobile-100 wins over mobile-12 and mobile-9" || bad "got: $OUTPUT"
printf '%s' "$OUTPUT" | grep -qx "mobile_base=$c3" && ok "base is the commit of mobile-100" || bad "got: $OUTPUT"

printf '\n\033[1mRelease notes\033[0m\n'
run notes "$c4" "$c0"
[ "$OUTPUT" = $'- Fix the chat topic on the phone (MAG-109) (#3)\n- Add the agenda widget (MAG-10) (#1)' ] \
  && ok "the titles of the commits that touched mobile/, newest first" || bad "got: $OUTPUT"
run notes "$c3"
[ "$OUTPUT" = '- Fix the chat topic on the phone (MAG-109) (#3)' ] && ok "no base: the last commit" || bad "got: $OUTPUT"
run notes "$c4"
[ "$OUTPUT" = "- ${c4:0:7}" ] && ok "a range with nothing under mobile/ still says which commit" || bad "got: $OUTPUT"
run notes "$c4" "$c0"
printf '%s' "$OUTPUT" | grep -q 'recipes' && bad "an API commit leaked into the notes" || ok "commits outside mobile/ stay out"

printf '\n'
[ "$failures" -eq 0 ] && { echo "mobile-release: all good"; exit 0; } || { echo "mobile-release: $failures failure(s)"; exit 1; }
