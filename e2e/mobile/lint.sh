#!/usr/bin/env bash
#
# Checks the Maestro journeys without a device and without a stack (MAG-98).
#
# Usage:  task e2e:mobile:lint     (also part of `task lint:all`)
#
# `task e2e:web:lint` exists because ESLint catches the mistakes that make a
# Playwright suite lie. The equivalent mistakes here are different, and so are
# the four checks below:
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
#   4. every composable that opens a window *and* carries a tag declares one
#      `uiTagRoot()` per window. A `Dialog` or a `ModalBottomSheet` is a separate
#      semantics owner, so the activity's flag does not reach it and its tags have
#      no resource id. Check 2 cannot see this — the ids are declared and used,
#      they are simply unreachable — and it is what broke this harness's first CI
#      run.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
FLOW_DIR="$REPO_ROOT/e2e/mobile"
TAGS_FILE="$REPO_ROOT/mobile/app/src/main/java/com/maggie/app/ui/UiTags.kt"
# The whole app, not just ui/: a screen is free to open a dialog from anywhere.
SRC_DIR="$REPO_ROOT/mobile/app/src/main/java/com/maggie/app"
APP_ID="com.maggie.app.e2e"

failed=0
pass() { printf '  \033[32m✓\033[0m %s\n' "$1"; }
fail() { printf '  \033[31m✗\033[0m %s\n' "$1" >&2; failed=$((failed + 1)); }

mapfile -t flows < <(find "$FLOW_DIR/flows" "$FLOW_DIR/subflows" -name '*.yaml' | sort)
[ "${#flows[@]}" -gt 0 ] || { echo "No flow found under $FLOW_DIR." >&2; exit 1; }

# ---------------------------------------------------------------------------
printf '\n\033[1m1. Syntax\033[0m\n'
# ---------------------------------------------------------------------------
export MAESTRO_CLI_NO_ANALYTICS=1
export MAESTRO_CLI_ANALYSIS_NOTIFICATION_DISABLED=true
# Captured before `eval`: `eval "$(false)"` has status 0, so a failed download
# would surface as `MAESTRO: unbound variable` rather than as maestro.sh's own
# message. Same reason as in run.sh.
maestro_env="$("$REPO_ROOT/e2e/mobile/maestro.sh")"
eval "$maestro_env"

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

# ---------------------------------------------------------------------------
printf '\n\033[1m4. Every tagged window has its own tag root\033[0m\n'
# ---------------------------------------------------------------------------
# Comments — both `//` and `/* */`, KDoc included — are stripped before every
# grep below. Without that the check passes on a file whose only mention of
# `uiTagRoot()` is the comment explaining why it is there, which is what the
# first version of this check did.
#
# Written as one left-to-right scan rather than two `sub()` calls, because the
# order matters and getting it wrong swallows a whole file in silence: a `//`
# stripped first out of `/* see http://b/123 */` takes the closing `*/` with it,
# the rest of the file counts as comment, and the file is then skipped — which
# looks exactly like a file with no window in it. Code after a `*/` is kept, for
# the same reason.
code_of() {
  awk '
    {
      line = $0
      out = ""
      while (line != "") {
        if (inblock) {
          i = index(line, "*/")
          if (i == 0) { line = ""; break }
          line = substr(line, i + 2)
          inblock = 0
          continue
        }
        b = index(line, "/*")
        l = index(line, "//")
        if (l > 0 && (b == 0 || l < b)) { out = out substr(line, 1, l - 1); line = ""; break }
        if (b > 0) {
          out = out substr(line, 1, b - 1)
          line = substr(line, b + 2)
          inblock = 1
          continue
        }
        out = out line
        line = ""
      }
      print out
    }
    # Valid Kotlin never ends inside a block comment, so this means the scan took
    # a `/*` out of a string literal and treated the rest of the file as comment.
    # Said out loud rather than swallowed: a file stripped to nothing counts zero
    # windows, which is indistinguishable from a file that opens none.
    END {
      if (inblock) {
        print "  ! " FILENAME ": unterminated block comment — a /* inside a string literal? check 4 may have skipped this file" > "/dev/stderr"
      }
    }
  ' "$1"
}

# Counted, not merely present: `ChatSheet.kt` opens two windows and needs two
# roots, and a file-wide "is `uiTagRoot()` in here somewhere" passes with one of
# them missing — which is exactly the failure that broke this harness's first CI
# run, so the guard has to be able to fail on it.
#
# A file with one tagged window and one untagged one is over-demanded by this.
# That is deliberate: the extra root is free, and the alternative is parsing
# Kotlin blocks in awk.
# Every primitive that opens a platform window — each is a semantics owner of its
# own, for the same reason a Dialog is. Anchored on a non-identifier character so
# that a project composable *calling* one (`StorePickerDialog(`, `MealCreateDialog(`)
# is not counted as a second window, and `fun` lines are dropped so a declaration
# is not counted as a call. Without both, a screen with four dialog helpers would
# demand four roots the day a journey tags it, and the cheap way out would be
# decorative `uiTagRoot()` calls that devalue this check.
WINDOW_RE='(^|[^A-Za-z0-9_.])(ModalBottomSheet|AlertDialog|BasicAlertDialog|Dialog|Popup|DropdownMenu|ExposedDropdownMenu)\('

mapfile -t sources < <(find "$SRC_DIR" -name '*.kt' | sort)
for file in "${sources[@]}"; do
  code="$(code_of "$file")"
  windows="$(printf '%s\n' "$code" | grep -vE '(^|[[:space:]])fun[[:space:]]' | grep -cE "$WINDOW_RE" || true)"
  [ "$windows" -gt 0 ] || continue
  # Only files that actually carry a tag: a dialog no flow addresses needs
  # nothing, and demanding it everywhere would be noise nobody reads. So the gate
  # is precisely "opens a window *and* spells UiTags." — the roots in
  # `MainActivity.kt` and `ContextListSheet.kt` are not guarded by it, because
  # neither file declares a tag of its own. Tag something in one of them and it
  # starts being guarded.
  printf '%s\n' "$code" | grep -q 'UiTags\.' || continue

  roots="$(printf '%s\n' "$code" | grep -c 'uiTagRoot()' || true)"
  if [ "$roots" -ge "$windows" ]; then
    pass "${file#"$REPO_ROOT"/} ($windows window(s), $roots tag root(s))"
  else
    fail "${file#"$REPO_ROOT"/} opens $windows window(s) and carries a testTag but calls uiTagRoot() $roots time(s) — a window without one gives its tags no resource id, so Maestro cannot see them"
  fi
done

printf '\n'
if [ "$failed" -gt 0 ]; then
  printf '\033[31m%d problem(s) in the mobile journeys.\033[0m\n' "$failed" >&2
  exit 1
fi
printf '\033[32mThe mobile journeys are sound.\033[0m\n'
