#!/usr/bin/env bash
#
# Checks the Maestro journeys without a device and without a stack (MAG-98).
#
# Usage:  task e2e:mobile:lint     (also part of `task lint:all`)
#
# `task e2e:web:lint` exists because ESLint catches the mistakes that make a
# Playwright suite lie. The equivalent mistakes here are different, and so are
# the three checks below:
#
#   1. `maestro check-syntax` — a command Maestro does not know, a malformed
#      selector. Maestro fails on these at run time, ten minutes into a job that
#      had to boot an emulator first.
#   2. every `id:` a flow uses is declared in `ui/UiTags.kt`. A renamed tag is
#      the one failure this harness is most exposed to, and left to the
#      emulator it reads as "the button is not there" rather than as a rename.
#   3. every flow targets the `e2e` flavor's applicationId. A flow pointing at
#      `com.maggie.app` would drive the owner's production build, pass, and
#      prove nothing.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
FLOW_DIR="$REPO_ROOT/e2e/mobile"
TAGS_FILE="$REPO_ROOT/mobile/app/src/main/java/com/maggie/app/ui/UiTags.kt"
APP_ID="com.maggie.app.e2e"

failed=0
pass() { printf '  \033[32m✓\033[0m %s\n' "$1"; }
fail() { printf '  \033[31m✗\033[0m %s\n' "$1" >&2; failed=$((failed + 1)); }

mapfile -t flows < <(find "$FLOW_DIR/flows" "$FLOW_DIR/subflows" -name '*.yaml' | sort)
[ "${#flows[@]}" -gt 0 ] || { echo "No flow found under $FLOW_DIR." >&2; exit 1; }

# ---------------------------------------------------------------------------
printf '\n\033[1m1. Syntax\033[0m\n'
# ---------------------------------------------------------------------------
eval "$("$REPO_ROOT/e2e/mobile/maestro.sh")"
export MAESTRO_CLI_NO_ANALYTICS=1
export MAESTRO_CLI_ANALYSIS_NOTIFICATION_DISABLED=true

for flow in "${flows[@]}"; do
  if output="$("$MAESTRO" check-syntax "$flow" 2>&1)"; then
    pass "${flow#"$REPO_ROOT"/}"
  else
    fail "${flow#"$REPO_ROOT"/}: $output"
  fi
done

# ---------------------------------------------------------------------------
printf '\n\033[1m2. Every id is a declared testTag\033[0m\n'
# ---------------------------------------------------------------------------
mapfile -t declared < <(sed -n 's/.*const val [A-Z_]* = "\([a-z0-9_]*\)".*/\1/p' "$TAGS_FILE" | sort -u)
[ "${#declared[@]}" -gt 0 ] || { echo "No testTag read out of $TAGS_FILE — has it moved?" >&2; exit 1; }

mapfile -t used < <(sed -n 's/.*\bid:[[:space:]]*"\([^"]*\)".*/\1/p' "${flows[@]}" | sort -u)
for id in "${used[@]}"; do
  if printf '%s\n' "${declared[@]}" | grep -qxF "$id"; then
    pass "id \"$id\""
  else
    fail "id \"$id\" is not a declared testTag — add it to ${TAGS_FILE#"$REPO_ROOT"/}, or fix the flow that renamed it"
  fi
done

# The other direction is a warning, not a failure: a tag may be declared ahead
# of the flow that will use it, and MAG-98 ships fewer flows than tags.
for id in "${declared[@]}"; do
  printf '%s\n' "${used[@]}" | grep -qxF "$id" || printf '  \033[33m·\033[0m id "%s" is declared but no flow uses it yet\n' "$id"
done

# ---------------------------------------------------------------------------
printf '\n\033[1m3. Every flow targets the e2e flavor\033[0m\n'
# ---------------------------------------------------------------------------
for flow in "${flows[@]}"; do
  declared_app="$(sed -n 's/^appId:[[:space:]]*//p' "$flow" | head -1)"
  if [ "$declared_app" = "$APP_ID" ]; then
    pass "${flow#"$REPO_ROOT"/}"
  else
    fail "${flow#"$REPO_ROOT"/} targets '${declared_app:-nothing}', expected $APP_ID"
  fi
done

printf '\n'
if [ "$failed" -gt 0 ]; then
  printf '\033[31m%d problem(s) in the mobile journeys.\033[0m\n' "$failed" >&2
  exit 1
fi
printf '\033[32mThe mobile journeys are sound.\033[0m\n'
