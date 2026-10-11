#!/usr/bin/env bash
#
# Builds the `e2e` flavor once and leaves the APK at e2e/mobile/apk/maggie-e2e.apk (MAG-233).
#
# Usage:  e2e/mobile/build-apk.sh [extra gradle tasks…]
#
# Used by `run.sh` when no APK was handed to it, and by the CI job that builds the
# APK a single time for every shard. Extra arguments are gradle tasks run in the
# same invocation (CI adds `:app:testE2eDebugUnitTest`): the -P properties below
# land in BuildConfig, so a second gradle run with other properties would compile
# everything again.
#
# The APK is built against a fixed port on the device's loopback, which `adb
# reverse` bridges onto the stack (see run.sh): the same file works against any
# stack, on any emulator.
#
# E2E_COVERAGE=1 (the nightly) builds it JaCoCo-instrumented and leaves the classes
# the counts are read against in e2e/mobile/apk/classes (scripts/e2e/coverage/README.md).

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
APK_DIR="$REPO_ROOT/e2e/mobile/apk"

DEVICE_PORT="${E2E_MOBILE_PORT:-8099}"
# `localhost`, not `127.0.0.1`: the app's network security config permits
# cleartext for that host name only.
APP_BASE_URL="http://localhost:$DEVICE_PORT"
LOGIN_TOKEN="${E2E_LOGIN_TOKEN:-e2e-login-token}"
SEED_EMAIL="${E2E_SEED_EMAIL:-e2e@maggie.local}"

# Gradle resolves the SDK through ANDROID_HOME or mobile/local.properties, and a
# worktree has no local.properties of its own.
SDK_ROOT="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
[ ! -d "$SDK_ROOT" ] || export ANDROID_HOME="$SDK_ROOT"

gradle_args=(
  "-PE2E_API_BASE_URL=$APP_BASE_URL"
  "-PE2E_LOGIN_TOKEN=$LOGIN_TOKEN"
  "-PE2E_LOGIN_EMAIL=$SEED_EMAIL"
)
# The nightly's coverage (scripts/e2e/coverage/README.md): JaCoCo-instrumented classes,
# its runtime in the APK. Every other build is the plain one.
COVERAGE="${E2E_COVERAGE:-}"
[ "$COVERAGE" != 1 ] || gradle_args+=("-Pe2eCoverage=true")
# `mobile/gradle.properties` pins `org.gradle.java.home` to the JDK bundled with
# the owner's Android Studio. That path does not exist on a CI runner, so the
# build dies before it starts — override it there, and only there, with whatever
# JDK was set up. the `mobile-unit` job of `ci.yml` does the same thing on its one command line.
gradle_jdk="$(sed -n 's/^org\.gradle\.java\.home=//p' "$REPO_ROOT/mobile/gradle.properties" | tail -1)"
if [ -n "${JAVA_HOME:-}" ] && { [ -z "$gradle_jdk" ] || [ ! -x "$gradle_jdk/bin/java" ]; }; then
  gradle_args+=("-Dorg.gradle.java.home=$JAVA_HOME")
fi

(cd "$REPO_ROOT/mobile" && ./gradlew --console=plain :app:assembleE2eDebug "$@" "${gradle_args[@]}")

# Looked up rather than spelled: the file name follows the flavor and the build
# type, and a rename must not break this silently.
built="$(find "$REPO_ROOT/mobile/app/build/outputs/apk/e2e/debug" -name '*.apk' | head -1)"
[ -n "$built" ] || { echo "No APK under mobile/app/build/outputs/apk/e2e/debug after assembleE2eDebug." >&2; exit 1; }

mkdir -p "$APK_DIR"
cp "$built" "$APK_DIR/maggie-e2e.apk"
echo "APK: ${APK_DIR#"$REPO_ROOT"/}/maggie-e2e.apk"

# The classes JaCoCo reads the counts against, as this very build compiled them —
# before instrumentation: it matches them by checksum. Next to the APK, so whatever
# carries the APK to the emulator's job carries them too (scripts/e2e/coverage/mobile.sh).
rm -rf "$APK_DIR/classes"
if [ "$COVERAGE" = 1 ]; then
  mkdir -p "$APK_DIR/classes"
  build_dir="$REPO_ROOT/mobile/app/build"
  copied=0
  for classes in "$build_dir/tmp/kotlin-classes/e2eDebug" "$build_dir"/intermediates/javac/e2eDebug/*/classes; do
    [ -d "$classes" ] || continue
    cp -R "$classes/." "$APK_DIR/classes/"
    copied=1
  done
  [ "$copied" = 1 ] || { echo "No e2eDebug classes under mobile/app/build after assembleE2eDebug: the coverage could not be read." >&2; exit 1; }
  echo "Classes for the coverage: ${APK_DIR#"$REPO_ROOT"/}/classes"
fi
