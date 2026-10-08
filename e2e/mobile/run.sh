#!/usr/bin/env bash
#
# Runs the Maestro journeys against this worktree's e2e stack (MAG-98).
#
# Usage:  task e2e:mobile
#         task e2e:mobile -- --include-tags voice
#
# Needs a stack (`task e2e:up`, `task e2e:seed`) and one connected device or
# emulator. It does four things the flows cannot do for themselves:
#
#   1. bridges the stack's ephemeral host port onto a fixed port on the device,
#      with `adb reverse`, so the APK never has to know which port Docker chose;
#   2. puts the device on Europe/Paris, the time zone the seed anchors on, and on
#      the stack's time: `E2E_NOW` when set (MAG-234), the host's clock otherwise;
#   3. installs the `e2e` flavor — the APK in `E2E_MOBILE_APK` (CI builds it once for
#      every shard), or one built by `build-apk.sh`;
#   4. runs the flows — all of them, `E2E_MOBILE_SHARD=<i>/<n>` for one shard of
#      `shards.txt`, or the files named as arguments — and writes a JUnit report CI
#      uploads.
#
# `E2E_NOW` (ISO-8601, see e2e/clock.sh) is the instant the whole stack runs at.
# Here it sets the emulator's clock, and the dates handed to the flows follow it:
# the flows then pass or fail the same way at 23:50 on a Sunday as at noon.

set -euo pipefail

BASE_URL="${E2E_BASE_URL:?E2E_BASE_URL is required — run through 'task e2e:mobile'}"
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
FLOW_DIR="$REPO_ROOT/e2e/mobile"
REPORT_DIR="$FLOW_DIR/report"

# The port the APK is built against, on the *device's* loopback. Fixed on
# purpose: `adb reverse` then absorbs the stack's ephemeral port, so the same
# APK works against any stack and CI can compile it while the containers come
# up. Anything free and unlikely to clash with a dev server does.
DEVICE_PORT="${E2E_MOBILE_PORT:-8099}"
# `localhost`, not `127.0.0.1`: the app's network security config permits
# cleartext for that host name only, and a plain-HTTP call to an IP literal it
# does not cover is refused by the platform with a message about TLS that reads
# like a server problem.
APP_BASE_URL="http://localhost:$DEVICE_PORT"
LOGIN_TOKEN="${E2E_LOGIN_TOKEN:-e2e-login-token}"
SEED_EMAIL="${E2E_SEED_EMAIL:-e2e@maggie.local}"
SEED_TIMEZONE="${E2E_MOBILE_TIMEZONE:-Europe/Paris}"

step() { printf '\n\033[1m%s\033[0m\n' "$1"; }
note() { printf '  %s\n' "$1"; }
warn() { printf '  \033[33m!\033[0m %s\n' "$1" >&2; }
die() { printf '\033[31m%s\033[0m\n' "$1" >&2; exit 1; }

# ---------------------------------------------------------------------------
step "1. The toolchain: adb, one device, Maestro"
# ---------------------------------------------------------------------------
SDK_ROOT="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
if [ -x "$SDK_ROOT/platform-tools/adb" ]; then
  ADB="$SDK_ROOT/platform-tools/adb"
elif command -v adb >/dev/null; then
  ADB="$(command -v adb)"
else
  die "adb not found. Set ANDROID_HOME to your SDK, or put platform-tools on PATH."
fi
# Gradle resolves the SDK through ANDROID_HOME or mobile/local.properties, and a
# worktree has no local.properties of its own.
export ANDROID_HOME="$SDK_ROOT"

"$ADB" start-server >/dev/null 2>&1 || true

# One device, named explicitly from here on: `adb reverse` and `maestro test`
# both pick "the only one" otherwise, and would disagree the day a phone is
# plugged in next to an emulator.
if [ -n "${ANDROID_SERIAL:-}" ]; then
  SERIAL="$ANDROID_SERIAL"
else
  mapfile -t serials < <("$ADB" devices | awk '$2 == "device" { print $1 }')
  case "${#serials[@]}" in
    0) die "No device. Start an emulator (Android Studio, or \`emulator -avd <name>\`) or plug a phone in with USB debugging on." ;;
    1) SERIAL="${serials[0]}" ;;
    *) die "More than one device: ${serials[*]}. Pick one with ANDROID_SERIAL=<serial>." ;;
  esac
fi
export ANDROID_SERIAL="$SERIAL"
note "device $SERIAL"

# A device that answers `adb devices` can still be mid-boot, and an install into
# a package manager that is not up yet fails as "Could not access the Package
# Manager" — which reads like a broken APK.
"$ADB" -s "$SERIAL" wait-for-device
for _ in $(seq 1 60); do
  [ "$("$ADB" -s "$SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ] && break
  sleep 2
done

# Exported before the first invocation, which is the one that would otherwise
# print the opt-in notice: nothing about a private repository's test run goes to
# mobile.dev.
export MAESTRO_CLI_NO_ANALYTICS=1
export MAESTRO_CLI_ANALYSIS_NOTIFICATION_DISABLED=true
# Captured before `eval`, not piped into it: `eval "$(false)"` has status 0, so a
# failed download would surface two lines later as `MAESTRO: unbound variable`
# instead of as maestro.sh's own message about what went wrong.
maestro_env="$("$REPO_ROOT/e2e/mobile/maestro.sh")"
eval "$maestro_env"
note "maestro $("$MAESTRO" -v 2>/dev/null | tail -1)"

# ---------------------------------------------------------------------------
step "2. The stack, bridged onto the device"
# ---------------------------------------------------------------------------
HOST_PORT="${BASE_URL##*:}"
case "$HOST_PORT" in
  ''|*[!0-9]*) die "E2E_BASE_URL has no port: '$BASE_URL'. Expected something like http://127.0.0.1:32768." ;;
esac

# Checked from here rather than left to the first flow: a stack that is down
# fails as a login button that never leads anywhere, 40 seconds later, on an
# emulator.
if [ "$(curl -sS -o /dev/null -w '%{http_code}' -X POST \
      -H 'Content-Type: application/json' -H "X-E2E-Token: $LOGIN_TOKEN" \
      -d "{\"email\":\"$SEED_EMAIL\"}" "$BASE_URL/api/auth/e2e/login")" != "200" ]; then
  die "The test login does not answer 200 on $BASE_URL. Is the stack up (\`task e2e:up\`) and seeded (\`task e2e:seed\`)?"
fi
note "the stack answers on $BASE_URL"

# The time zone *before* the bridge, and that order matters. The seed anchors on
# midnight in Paris (the test user's zone) while a CI emulator boots on UTC;
# between 22:00 and midnight UTC the two are on different days, « Déjeuner avec
# Alex » sits on the device's tomorrow, and the dashboard assertion fails for an
# hour a day for no other reason.
#
# `-timezone Europe/Paris` in the emulator options is the real fix and CI passes
# it; this is the fallback for an emulator somebody else started. It needs root,
# and `adb root` *restarts adbd* — which lives where the reverse rules live, so
# establishing the bridge first would silently lose it and every flow would fail
# on a connection refused. A warning rather than a failure: a physical phone will
# not give root, and the owner's is on Paris time already.
current_tz="$("$ADB" -s "$SERIAL" shell getprop persist.sys.timezone 2>/dev/null | tr -d '\r')"
if [ "$current_tz" != "$SEED_TIMEZONE" ]; then
  "$ADB" -s "$SERIAL" root >/dev/null 2>&1 || true
  "$ADB" -s "$SERIAL" wait-for-device
  "$ADB" -s "$SERIAL" shell setprop persist.sys.timezone "$SEED_TIMEZONE" >/dev/null 2>&1 || true
  current_tz="$("$ADB" -s "$SERIAL" shell getprop persist.sys.timezone 2>/dev/null | tr -d '\r')"
fi
if [ "$current_tz" = "$SEED_TIMEZONE" ]; then
  note "device time zone $current_tz"
else
  warn "the device is on '$current_tz', the seed anchors on $SEED_TIMEZONE: a run between 22:00 and 00:00 UTC will read the dashboard a day off."
fi

# The device's clock, for the reason above: the bridge comes after anything that
# needs root. An emulator's clock is its own — a snapshot or a long-lived CI image
# keeps the time it was saved at — and `LocalDate.now()` in the app reads it. A run
# on a clock a day behind the stack's looks for « today » on the wrong week
# (MAG-234: calendar_day_2026-10-05 against a Week view of 28 Sep – 4 Oct), so the
# device is always put on the stack's time, and what it reads is printed.
NOW_EPOCH="$("$REPO_ROOT/e2e/clock.sh" epoch)" || exit $?
CLOCK_PINNED=0
if [ -n "$NOW_EPOCH" ]; then
  CLOCK_PINNED=1
else
  NOW_EPOCH="$(date +%s)"
fi

device_epoch() { "$ADB" -s "$SERIAL" shell date +%s 2>/dev/null | tr -d '\r'; }

set_device_time() {
  "$ADB" -s "$SERIAL" shell "cmd alarm set-time $(($1 * 1000))" >/dev/null 2>&1 || true
  # `cmd alarm` answers 0 even when it refused, so the reading decides.
  if [ "$(device_skew "$1")" -gt 5 ]; then
    "$ADB" -s "$SERIAL" shell "date -u -s @$1" >/dev/null 2>&1 || true
  fi
}

# The device's own clock drifts from the stack's by this many seconds, absolute.
device_skew() {
  local reading
  reading="$(device_epoch)"
  case "$reading" in
    ''|*[!0-9]*) echo 999999 ;;
    *) local skew=$((reading - $1)); echo "${skew#-}" ;;
  esac
}

clock_keeper=''
previous_auto_time=''
previous_hide=''

restore_device() {
  [ -z "$clock_keeper" ] || kill "$clock_keeper" 2>/dev/null || true
  if [ -n "$previous_auto_time" ] && [ "$previous_auto_time" != "null" ]; then
    "$ADB" -s "$SERIAL" shell settings put global auto_time "$previous_auto_time" >/dev/null 2>&1 || true
  fi
  # A pinned run leaves the device in the future: put its clock back on the host's.
  if [ "$CLOCK_PINNED" = 1 ]; then
    set_device_time "$(date +%s)"
  fi
  "$ADB" -s "$SERIAL" reverse --remove "tcp:$DEVICE_PORT" >/dev/null 2>&1 || true
  if [ -z "$previous_hide" ] || [ "$previous_hide" = "null" ]; then
    "$ADB" -s "$SERIAL" shell settings delete global hide_error_dialogs >/dev/null 2>&1 || true
  else
    "$ADB" -s "$SERIAL" shell settings put global hide_error_dialogs "$previous_hide" >/dev/null 2>&1 || true
  fi
}
# The bridge is removed too: a stale reverse pointing at a torn-down stack is how
# the next run fails on a connection refused that names nothing.
trap restore_device EXIT

"$ADB" -s "$SERIAL" root >/dev/null 2>&1 || true
"$ADB" -s "$SERIAL" wait-for-device
previous_auto_time="$("$ADB" -s "$SERIAL" shell settings get global auto_time 2>/dev/null | tr -d '\r' || true)"
# Network time would undo the next line within minutes.
"$ADB" -s "$SERIAL" shell settings put global auto_time 0 >/dev/null 2>&1 || true

pin_device_clock() {
  # Pinned: the exact instant. Not pinned: the host's clock, now.
  local target=$NOW_EPOCH
  [ "$CLOCK_PINNED" = 1 ] || target="$(date +%s)"
  if [ "$CLOCK_PINNED" = 1 ] || [ "$(device_skew "$target")" -gt 30 ]; then
    set_device_time "$target"
  fi
  if [ "$(device_skew "$target")" -gt 90 ]; then
    if [ "$CLOCK_PINNED" = 1 ]; then
      die "Could not set the device's clock to $E2E_NOW (it reads $(device_epoch), wanted $target). A pinned clock needs an emulator with root — a google_apis or google_atd image, not google_apis_playstore."
    fi
    warn "the device's clock is $(device_skew "$target")s away from the host's and could not be set: the calendar flows may look at the wrong day."
  fi
}
pin_device_clock

"$ADB" -s "$SERIAL" reverse --remove "tcp:$DEVICE_PORT" >/dev/null 2>&1 || true
"$ADB" -s "$SERIAL" reverse "tcp:$DEVICE_PORT" "tcp:$HOST_PORT" >/dev/null
note "device $APP_BASE_URL → host $BASE_URL"

# « Pixel Launcher isn't responding » (MAG-215). On a tablet or a foldable the
# launcher is also the taskbar, always running, and on a software-rendered CI
# emulator it misses its ANR deadline now and then; the system dialog that
# follows is a window above the app, so Maestro reads only that dialog and every
# `assertVisible` on the app fails while the screenshot shows the app intact. The
# phone's launcher is idle, which is why it only fails there once in a while.
# Hiding error dialogs is the platform switch for exactly this; an app that really
# crashes still fails its flow, and the logcat in the report says why.
#
# Put back on the way out: a physical phone keeps its settings between runs, and
# the owner's must go on telling him when an app of his own crashes.
previous_hide="$("$ADB" -s "$SERIAL" shell settings get global hide_error_dialogs 2>/dev/null | tr -d '\r' || true)"
"$ADB" -s "$SERIAL" shell settings put global hide_error_dialogs 1 >/dev/null 2>&1 \
  || warn "could not hide the system's error dialogs: an ANR dialog above the app will fail a flow."
# It is not enough on its own (MAG-236): the system server can still show the
# launcher's ANR window with it set, and a run on an unpinned clock proved the clock
# is not what triggers it. `subflows/dismiss-system-anr.yaml` is the real guard; this
# line says in the log whether the setting was at least in force.
note "hide_error_dialogs = $("$ADB" -s "$SERIAL" shell settings get global hide_error_dialogs 2>/dev/null | tr -d '\r')"


# ---------------------------------------------------------------------------
step "3. Install the e2e flavor"
# ---------------------------------------------------------------------------
# CI builds the APK once, in its own job, and hands the file to every shard in
# `E2E_MOBILE_APK` (MAG-233): nothing is compiled here then. Locally it is built
# by `build-apk.sh`, which is also what that CI job runs.
APK="${E2E_MOBILE_APK:-}"
if [ -z "$APK" ]; then
  "$REPO_ROOT/e2e/mobile/build-apk.sh"
  APK="$FLOW_DIR/apk/maggie-e2e.apk"
fi
[ -f "$APK" ] || die "No APK at $APK (E2E_MOBILE_APK)."
# -r: replace an install from a previous run; -t: the e2e flavor is a debug build.
"$ADB" -s "$SERIAL" install -r -t "$APK" >/dev/null
note "installed ${APK#"$REPO_ROOT"/}"

# ---------------------------------------------------------------------------
step "4. The journeys"
# ---------------------------------------------------------------------------
rm -rf "$REPORT_DIR"
mkdir -p "$REPORT_DIR"

# By default the workspace directory, not a file list: Maestro reads
# `config.yaml` from it, which is what keeps `subflows/` out of the run.
#
# A `.yaml` argument replaces it rather than being added to it. Maestro takes
# `<flowFiles>...` as a repeatable positional, so passing both would run the
# whole workspace *and* that file again — `task e2e:mobile -- flows/01-…yaml`
# would be a slower full run, not the single flow it reads as. Paths are resolved
# against this directory, because `run.sh` never sets the caller's cwd.
#
# One Maestro option takes a `.yaml` value — `--config` — so the value after it is
# passed through rather than mistaken for a flow. It would otherwise resolve to
# this directory's own `config.yaml`, which *exists*, so the existence check below
# would not catch it: the file would silently become the only target and `--config`
# would be left without a value. Nothing here needs it (Maestro reads the
# workspace's config on its own), but reading as a flow would be worse than
# refusing.
targets=()
maestro_args=()
previous=''
for arg in "$@"; do
  case "$arg" in
    *.yaml|*.yml)
      if [ "$previous" = '--config' ]; then
        maestro_args+=("$arg")
      else
        resolved="$(cd "$FLOW_DIR" && realpath -m "$arg")"
        [ -f "$resolved" ] || die "No flow at $resolved. Paths are relative to e2e/mobile/ — try flows/01-login-chat.yaml."
        targets+=("$resolved")
      fi
      ;;
    *) maestro_args+=("$arg") ;;
  esac
  previous="$arg"
done

# `E2E_MOBILE_SHARD=2/3`: only the flows `shards.txt` gives shard 2, in its order.
# CI runs one shard per emulator (MAG-233). The shard count is checked against the
# file, so a matrix that drifts from it fails here instead of skipping flows.
if [ -n "${E2E_MOBILE_SHARD:-}" ]; then
  [ "${#targets[@]}" -eq 0 ] || die "E2E_MOBILE_SHARD and a flow argument are both set: pick one."
  shard_index="${E2E_MOBILE_SHARD%%/*}"
  shard_count="${E2E_MOBILE_SHARD##*/}"
  case "$shard_index$shard_count" in
    ''|*[!0-9]*) die "E2E_MOBILE_SHARD='$E2E_MOBILE_SHARD': expected <index>/<count>, e.g. 2/3." ;;
  esac
  shard_lines="$(grep -E '^[0-9]+:' "$FLOW_DIR/shards.txt" || true)"
  [ "$(printf '%s\n' "$shard_lines" | grep -c .)" -eq "$shard_count" ] \
    || die "E2E_MOBILE_SHARD says $shard_count shards, e2e/mobile/shards.txt defines $(printf '%s\n' "$shard_lines" | grep -c .)."
  shard_flows="$(printf '%s\n' "$shard_lines" | sed -n "s/^${shard_index}:[[:space:]]*//p")"
  [ -n "$shard_flows" ] || die "e2e/mobile/shards.txt has no shard $shard_index."
  for name in $shard_flows; do
    [ -f "$FLOW_DIR/flows/$name.yaml" ] || die "shards.txt names flows/$name.yaml, which does not exist."
    targets+=("$FLOW_DIR/flows/$name.yaml")
  done
  note "shard $E2E_MOBILE_SHARD: $shard_flows"
fi
[ "${#targets[@]}" -gt 0 ] || targets=("$FLOW_DIR")

# Variables handed to the flows as `-e`. First the date the calendar journey looks
# for, computed in the seed's time zone and not the host's: a runner on UTC between
# 22:00 and midnight would otherwise name a day the seed has not reached. A flow
# cannot compute a date itself, and a date typed into it would rot by tomorrow.
#
# « Today » is the day the seed anchored on, read from its manifest, and only when
# the manifest is not there the day of the stack's clock (MAG-234): a run that
# started before midnight and reached this line after must not call the seed's
# day « yesterday ».
#
# `TRAIN_START` / `TRAIN_END` — the two days « Train de nuit pour Vienne » crossed
# — are gone with `03-calendar-multi-day` (MAG-242). The screen test that replaced
# it writes both of its weeks down instead of deriving them from the seed, which is
# how it covers the Sunday-straddling case on every run rather than one day in
# three.
seed_day() { TZ=UTC date -d "$1 $2" +%F; }
# A failure inside $(…) never trips `set -e`: BSD date would hand the flows empty dates.
seed_day 2000-01-01 '+1 day' >/dev/null 2>&1 || { echo "run.sh needs GNU date (date -d)" >&2; exit 1; }
anchor_day="$(sed -n 's/.*"anchor": *"\([0-9]\{4\}-[0-9]\{2\}-[0-9]\{2\}\).*/\1/p' "$REPO_ROOT/api/var/e2e/seed-manifest.json" 2>/dev/null | head -1)"
clock_day="$(TZ="$SEED_TIMEZONE" date -d "@$NOW_EPOCH" +%F)"
TODAY="${anchor_day:-$clock_day}"

step "The clock"
note "E2E_NOW        ${E2E_NOW:-<not set: the real clock>}"
note "stack day      $clock_day ($SEED_TIMEZONE)"
note "TODAY          $TODAY ($([ -n "$anchor_day" ] && echo "the seed's anchor" || echo "no seed manifest: the stack's clock"))"
note "device         $("$ADB" -s "$SERIAL" shell date 2>/dev/null | tr -d '\r')"
note "device zone    $("$ADB" -s "$SERIAL" shell getprop persist.sys.timezone 2>/dev/null | tr -d '\r')"
note "host           $(date)"
[ -z "$anchor_day" ] || [ "$anchor_day" = "$clock_day" ] \
  || warn "the seed anchored on $anchor_day but the stack's clock says $clock_day: reseed (\`task e2e:seed\`) before blaming a flow."
{
  echo "E2E_NOW=${E2E_NOW:-}"
  echo "TODAY=$TODAY"
  echo "device_date=$("$ADB" -s "$SERIAL" shell date 2>/dev/null | tr -d '\r')"
  echo "device_timezone=$("$ADB" -s "$SERIAL" shell getprop persist.sys.timezone 2>/dev/null | tr -d '\r')"
  echo "host_date=$(date)"
} >"$REPORT_DIR/clock.txt"

# The install above took minutes, and an emulator's clock runs: put it back on the
# stack's time, then hold it there. The servers stand still on a pinned clock; a
# device left to run would reach midnight in the middle of a suite that started
# ten minutes before it. Re-set whenever it has drifted a minute, not on every
# tick — a clock that jumps all the time is a worse test than one that drifts a bit.
pin_device_clock
if [ "$CLOCK_PINNED" = 1 ]; then
  (
    while sleep 5; do
      [ "$(device_skew "$NOW_EPOCH")" -gt 60 ] && set_device_time "$NOW_EPOCH"
    done
  ) &
  clock_keeper=$!
fi

flow_env=(
  -e "TODAY=$TODAY"
  # The flows' scripts run on the host, where Maestro runs, so they need the real
  # URL rather than the device's bridged one: `02-voice-overlay.yaml` reads the
  # agent's e2e counter, `scripts/grocery-api.js` signs in with the app's token.
  -e "E2E_BASE_URL=$BASE_URL"
  -e "E2E_LOGIN_TOKEN=$LOGIN_TOKEN"
)

# --debug-output is what puts the screenshot of each failed command, the view
# hierarchy and maestro.log under $REPORT_DIR: without it Maestro writes them to
# ~/.maestro/tests, outside the folder CI uploads, and a red run leaves nothing
# but a one-line assertion to read (MAG-215). --flatten-debug-output keeps them in
# one folder instead of a timestamped one per run.
#
# Not `exec`: that would replace this shell and the EXIT trap above would never
# remove the reverse bridge. The status is kept so that the device's own view of
# a failure — its last frame and its log — is collected before exiting with it.
# The keyboard of an ATD image (MAG-241). Google's Automated Test Device images
# ship no keyboard at all (logcat: « No default IME found »), and Maestro only
# turns its own on after the first `inputText` has already failed with « Maestro
# IME is not active »: the first journey of every lot went red, the others green
# (PR #125, reverted by #130). Maestro installs its driver when the run starts, so
# this watches for it and selects its keyboard before the first flow types — only
# on a device without a default keyboard: the full image keeps Gboard.
MAESTRO_IME='dev.mobile.maestro/.input.MaestroInputMethodService'
select_maestro_ime() {
  local deadline=$((SECONDS + 120)) current
  while [ "$SECONDS" -lt "$deadline" ]; do
    current="$("$ADB" -s "$SERIAL" shell settings get secure default_input_method 2>/dev/null | tr -d '\r')"
    case "$current" in
      ''|null) ;;
      *) return 0 ;;
    esac
    if "$ADB" -s "$SERIAL" shell ime list -a -s 2>/dev/null | tr -d '\r' | grep -qx "$MAESTRO_IME"; then
      "$ADB" -s "$SERIAL" shell ime enable "$MAESTRO_IME" >/dev/null 2>&1 || true
      "$ADB" -s "$SERIAL" shell ime set "$MAESTRO_IME" >/dev/null 2>&1 && return 0
    fi
    sleep 0.5
  done
}
select_maestro_ime &
ime_watcher=$!

status=0
"$MAESTRO" --device "$SERIAL" test "${targets[@]}" \
  --format junit \
  --output "$REPORT_DIR/junit.xml" \
  --test-output-dir "$REPORT_DIR" \
  --debug-output "$REPORT_DIR" \
  --flatten-debug-output \
  --no-ansi \
  "${flow_env[@]}" \
  ${maestro_args[@]+"${maestro_args[@]}"} || status=$?
kill "$ime_watcher" 2>/dev/null || true
wait "$ime_watcher" 2>/dev/null || true

if [ "$status" -ne 0 ]; then
  step "The device after the failure"
  "$ADB" -s "$SERIAL" exec-out screencap -p >"$REPORT_DIR/device-last-frame.png" 2>/dev/null || true
  "$ADB" -s "$SERIAL" logcat -d -t 5000 >"$REPORT_DIR/logcat.txt" 2>/dev/null || true
  # The tail above is taken minutes after an ANR: the lines that name the process and
  # the reason are long gone from it (MAG-236). The whole buffer, filtered, says
  # which app hung and when.
  "$ADB" -s "$SERIAL" logcat -d -b main -b system -b events 2>/dev/null \
    | grep -E 'ANR in|am_anr|Input dispatching timed out|isn.t responding|AppNotResponding' >"$REPORT_DIR/anr.txt" || true
  "$ADB" -s "$SERIAL" shell wm size 2>/dev/null | tr -d '\r' >"$REPORT_DIR/device-size.txt" || true
  note "last frame, logcat, ANR lines and screen size written to $REPORT_DIR"
fi

exit "$status"
