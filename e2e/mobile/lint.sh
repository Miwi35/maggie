#!/usr/bin/env bash
#
# Checks the Maestro journeys without a device and without a stack (MAG-98).
#
# Usage:  task e2e:mobile:lint     (also part of `task lint:all`)
#
# `task e2e:web:lint` exists because ESLint catches the mistakes that make a
# Playwright suite lie. The equivalent mistakes here are different, and so are
# the checks below:
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
#   5. no bare `assertVisible` directly after an action. Maestro's own default
#      timeout is what then decides whether the run is green, which is how
#      `01-login-chat` failed in CI on a run where nothing was wrong. This is the
#      local equivalent of eslint-plugin-playwright's "an `expect` nobody
#      awaited".
#   4. every composable that opens a window *and* carries a tag declares one
#      `uiTagRoot()` per window. A `Dialog` or a `ModalBottomSheet` is a separate
#      semantics owner, so the activity's flag does not reach it and its tags have
#      no resource id. Check 2 cannot see this — the ids are declared and used,
#      they are simply unreachable — and it is what broke this harness's first CI
#      run.
#   6. every flow is in exactly one shard of `shards.txt`, in the order of
#      `config.yaml`'s `flowsOrder`: CI runs the shards, so a flow left out of
#      them never runs and nothing says so.
#   7. every `retry` starts with `evalScript: ${0}`. Maestro counts a wait from the
#      last interaction and a failed attempt is not one, so without it the retries
#      run on a budget of zero: four « attempts » that are one (MAG-346).

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
# A `_PREFIX` const is the head of a family of ids built at run time (a calendar
# day, an event on a day…): a flow may spell any id that starts with one.
mapfile -t declared < <(sed -n 's/.*const val [A-Z_]* = "\([a-z0-9_]*\)".*/\1/p' "$TAGS_FILE" | sort -u)
[ "${#declared[@]}" -gt 0 ] || { echo "No testTag read out of $TAGS_FILE — has it moved?" >&2; exit 1; }
mapfile -t prefixes < <(sed -n 's/.*const val [A-Z_]*_PREFIX = "\([a-z0-9_]*\)".*/\1/p' "$TAGS_FILE" | sort -u)

mapfile -t used < <(sed -n 's/.*\bid:[[:space:]]*"\([^"]*\)".*/\1/p' "${flows[@]}" | sort -u)
for id in "${used[@]}"; do
  if printf '%s\n' "${declared[@]}" | grep -qxF "$id"; then
    pass "id \"$id\""
    continue
  fi
  matched=0
  for prefix in "${prefixes[@]}"; do
    case "$id" in "$prefix"?*) matched=1 ;; esac
  done
  if [ "$matched" -eq 1 ]; then
    pass "id \"$id\" (a declared prefix)"
  else
    fail "id \"$id\" is not a declared testTag — add it to ${TAGS_FILE#"$REPO_ROOT"/}, or fix the flow that renamed it"
  fi
done

# The other direction is a warning, not a failure, and since MAG-242 it is a weak
# one: a tag may be declared ahead of the flow that will use it, and most of them
# are now addressed from a screen test on the JVM
# (`mobile/app/src/test/…/…ScreenTest.kt`) rather than from a flow. Those tests
# spell the constant — `UiTags.CALENDAR_NEXT`, `UiTags.calendarEvent(day)` — not the
# id, so there is nothing here to match them against. A tag nothing uses at all is
# found by Android Studio, not by this.
for id in "${declared[@]}"; do
  printf '%s\n' "${used[@]}" | grep -qxF "$id" && continue
  case "$id" in *_) printf '%s\n' "${used[@]}" | grep -q "^$id" && continue ;; esac
  printf '  \033[33m·\033[0m id "%s" is declared but no flow uses it (a screen test may)\n' "$id"
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
WINDOW_RE='(^|[^A-Za-z0-9_.])(ModalBottomSheet|AlertDialog|BasicAlertDialog|DatePickerDialog|Dialog|Popup|DropdownMenu|ExposedDropdownMenu)\('

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

# ---------------------------------------------------------------------------
printf '\n\033[1m5. Nothing asserted without waiting for it\033[0m\n'
# ---------------------------------------------------------------------------
# A command that changes the screen — launching, tapping, typing, running a
# subflow — is followed by something that *waits*. `assertVisible` does wait, on
# Maestro's default; the point is that the timeout is then invisible in the flow,
# and a cold emulator is exactly where it runs out. `extendedWaitUntil` says the
# number out loud.
#
# `assertNotVisible` is deliberately not flagged: an absence has nothing to wait
# for, and `02-voice-overlay.yaml` explains at length what its one use proves.
for flow in "${flows[@]}"; do
  offenders="$(awk '
    /^- (launchApp|tapOn|inputText|runFlow|stopApp|pressKey|swipe|scroll)/ { acted = 1; line = NR; next }
    /^- assertVisible/ { if (acted) print line ": " $0; acted = 0; next }
    /^- / { acted = 0 }
  ' "$flow")"
  if [ -z "$offenders" ]; then
    pass "${flow#"$REPO_ROOT"/}"
  else
    fail "${flow#"$REPO_ROOT"/}: assertVisible straight after an action, on Maestro's invisible default timeout — use extendedWaitUntil with a timeout. After line $offenders"
  fi
done

# ---------------------------------------------------------------------------
printf '\n\033[1m6. Every flow is in exactly one shard, in flowsOrder\033[0m\n'
# ---------------------------------------------------------------------------
# CI runs the journeys as the shards of `shards.txt` (MAG-233), so a flow missing
# there is a flow that never runs, and green. `config.yaml`'s `flowsOrder` stays
# the reference of the order, inside a shard as well.
mapfile -t on_disk < <(find "$FLOW_DIR/flows" -name '*.yaml' -exec basename {} .yaml \; | sort)
mapfile -t ordered < <(sed -n '/flowsOrder:/,$ s/^[[:space:]]*-[[:space:]]*//p' "$FLOW_DIR/config.yaml")
mapfile -t shard_lines < <(grep -E '^[0-9]+:' "$FLOW_DIR/shards.txt" || true)
[ "${#shard_lines[@]}" -gt 0 ] || { fail "shards.txt defines no shard"; shard_lines=(); }

if [ "$(printf '%s\n' "${ordered[@]}" | sort)" = "$(printf '%s\n' "${on_disk[@]}")" ]; then
  pass "config.yaml flowsOrder lists every flow once"
else
  fail "config.yaml flowsOrder does not list exactly the files of flows/"
fi

in_shards=()
for line in "${shard_lines[@]}"; do
  shard="${line%%:*}"
  read -ra names <<<"${line#*:}"
  [ "${#names[@]}" -gt 0 ] || fail "shard $shard is empty"
  last=-1
  for name in "${names[@]}"; do
    in_shards+=("$name")
    position=-1
    for i in "${!ordered[@]}"; do [ "${ordered[$i]}" = "$name" ] && position=$i; done
    if [ "$position" -lt 0 ]; then
      continue
    elif [ "$position" -le "$last" ]; then
      fail "shard $shard lists $name out of flowsOrder"
    fi
    last=$position
  done
done
if [ "$(printf '%s\n' "${in_shards[@]}" | sort)" = "$(printf '%s\n' "${on_disk[@]}")" ]; then
  pass "shards.txt covers ${#on_disk[@]} flows across ${#shard_lines[@]} shards, none twice"
else
  fail "shards.txt must name every file of flows/ exactly once (in shards: ${in_shards[*]})"
fi

# ---------------------------------------------------------------------------
printf '\n\033[1m7. Every retry resets the interaction clock\033[0m\n'
# ---------------------------------------------------------------------------
# `retry` re-runs its commands, but every wait inside is shortened by the time
# since the last interaction (a launch, a tap, a script — not an assertion, not a
# failed attempt). Attempts two and up therefore started with a budget of zero and
# failed on the spot: a cold start slower than the first attempt was never given
# the minutes `maxRetries` promised, and the flow read « login_sign_in is not
# visible » (MAG-346). `evalScript: ${0}` is the no-op that counts as an
# interaction.
for file in "${flows[@]}"; do
  offenders="$(awk '
    /^[[:space:]]*- retry:/ { in_retry = 1; seen_commands = 0; line = NR; next }
    in_retry && /^[[:space:]]*commands:/ { seen_commands = 1; next }
    in_retry && seen_commands && /^[[:space:]]*- / {
      if ($0 !~ /evalScript:[[:space:]]*\$\{0\}/) print line
      in_retry = 0
    }
  ' "$file")"
  if [ -z "$offenders" ]; then
    pass "${file#"$REPO_ROOT"/}"
  else
    fail "${file#"$REPO_ROOT"/}: retry at line $offenders does not start with 'evalScript: \${0}' — its attempts after the first would run on a budget of zero"
  fi
done

printf '\n'
if [ "$failed" -gt 0 ]; then
  printf '\033[31m%d problem(s) in the mobile journeys.\033[0m\n' "$failed" >&2
  exit 1
fi
printf '\033[32mThe mobile journeys are sound.\033[0m\n'
