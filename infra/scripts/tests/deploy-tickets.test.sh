#!/usr/bin/env bash
# shellcheck disable=SC2015 # `cond && ok || bad`: ok and bad cannot fail
#
# Tests of infra/scripts/deploy-tickets.sh on a throwaway git history and a fake
# gh (MAG-184).
#
# A ticket missed here is a broken deploy nobody is asked to fix; a ticket too
# many freezes production on an innocent one. What matters: every commit of the
# deployed range counts, the subject wins, the PR branch is the fallback.
#
# Usage: infra/scripts/tests/deploy-tickets.test.sh

set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SCRIPT="$HERE/../deploy-tickets.sh"
failures=0

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

ok()  { printf '  \033[32m✓\033[0m %s\n' "$1"; }
bad() { printf '  \033[31m✗\033[0m %s\n' "$1"; failures=$((failures + 1)); }

# A gh that answers `api repos/<repo>/commits/<sha>/pulls` with the branch named
# in $work/branches/<sha>, or with no PR at all.
mkdir -p "$work/bin" "$work/branches"
cat > "$work/bin/gh" <<'FAKE'
#!/usr/bin/env bash
sha=$(printf '%s' "$2" | sed -E 's|.*/commits/([0-9a-f]+)/pulls|\1|')
echo "$sha" >> "$FAKE_GH_DIR/calls"
if [ -f "$FAKE_GH_DIR/branches/$sha" ]; then cat "$FAKE_GH_DIR/branches/$sha"; fi
FAKE
chmod +x "$work/bin/gh"

git init -q "$work/repo"
commit() { git -C "$work/repo" -c user.name=t -c user.email=t@t commit -q --allow-empty -m "$1"; git -C "$work/repo" rev-parse HEAD; }

c0=$(commit 'Initial commit')
c1=$(commit 'Fix the mobile chat subscription (MAG-138) (#37)')
c2=$(commit 'Remove the daily agent report (#56)')
c3=$(commit 'Index and publish the agenda (MAG-176) (#54)')
c4=$(commit 'Bump a dependency')
c5=$(commit 'Freeze production on MAG-184 and MAG-12 (#60)')
echo 'meven35/mag-150-remove-the-daily-report' > "$work/branches/$c2"

run_tickets() {
  : > "$work/calls"
  OUTPUT="$(cd "$work/repo" && PATH="$work/bin:$PATH" FAKE_GH_DIR="$work" GH_REPO=o/r "$SCRIPT" "$@" 2>&1)"
  STATUS=$?
  KEYS="$(printf '%s' "$OUTPUT" | paste -sd' ')"
}

printf '\n\033[1mEvery commit since the last deploy counts\033[0m\n'
run_tickets "$c4" "$c0"
[ "$STATUS" -eq 0 ] && ok "exits 0" || bad "exit $STATUS — $OUTPUT"
[ "$KEYS" = "MAG-176 MAG-150 MAG-138" ] && ok "finds MAG-176, MAG-150 and MAG-138" || bad "found: $KEYS"
grep -qx "$c2" "$work/calls" && ok "asks GitHub for the PR of a commit without a key" || bad "gh calls: $(cat "$work/calls")"
! grep -qx "$c1" "$work/calls" && ok "never asks when the subject has a key" || bad "gh calls: $(cat "$work/calls")"
! grep -qx "$c0" "$work/calls" && ok "leaves the last deploy out" || bad "gh calls: $(cat "$work/calls")"

printf '\n\033[1mA subject naming two tickets gives both\033[0m\n'
run_tickets "$c5" "$c4"
[ "$KEYS" = "MAG-184 MAG-12" ] && ok "MAG-184 and MAG-12" || bad "found: $KEYS"

printf '\n\033[1mNo usable last deploy: only the deployed commit\033[0m\n'
run_tickets "$c3"
[ "$KEYS" = "MAG-176" ] && ok "no last deploy: MAG-176" || bad "found: $KEYS"
run_tickets "$c3" "$c3"
[ "$KEYS" = "MAG-176" ] && ok "same commit as the last deploy: MAG-176" || bad "found: $KEYS"
run_tickets "$c1" "$c3"
[ "$KEYS" = "MAG-138" ] && ok "last deploy not an ancestor: MAG-138" || bad "found: $KEYS"
run_tickets "$c3" "0000000000000000000000000000000000000000"
[ "$KEYS" = "MAG-176" ] && ok "unknown last deploy: MAG-176" || bad "found: $KEYS"

printf '\n\033[1mNo key anywhere: nothing, and no failure\033[0m\n'
run_tickets "$c4" "$c3"
[ "$STATUS" -eq 0 ] && ok "exits 0" || bad "exit $STATUS — $OUTPUT"
[ -z "$OUTPUT" ] && ok "prints nothing" || bad "printed: $OUTPUT"

printf '\n'
[ "$failures" -eq 0 ] && echo "All deploy-tickets tests passed." || echo "$failures failure(s)."
exit "$failures"
