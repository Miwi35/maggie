#!/usr/bin/env bash
#
# The mobile app's JVM unit tests on this worktree — the one Gradle build an
# agent runs locally (`task wt:test:mobile -- --tests 'com.maggie.app.…'`).
#
# On 8 Oct 2026 systemd-oomd killed Cyrus eight times in a day: several sessions
# ran Gradle at once, and each left a Gradle daemon and a Kotlin daemon behind
# (2 to 4 GB each), on top of the e2e stacks. So, here:
#
#   1. One build at a time on the machine, every worktree included: a flock on
#      a file under $XDG_RUNTIME_DIR. A second caller waits, up to
#      WT_GRADLE_LOCK_WAIT_MINUTES, then exits 75 — use CI.
#   2. The load guard runs once the lock is held, so it measures a machine no
#      other Gradle is using.
#   3. Fast, but bounded (wt/limits.env): the Gradle daemon is kept for the
#      next run, with a 1.5 GB heap and a 30-minute idle timeout; the Kotlin
#      daemon gets 1 GB; the test JVM keeps the 2 GB app/build.gradle.kts gives
#      it. On the command line, because a ~/.gradle/gradle.properties wins over
#      mobile/gradle.properties — and since a daemon is reused only by a build
#      asking for the same options, every run of this command shares one.
#   4. No cold build. The build cache (org.gradle.caching) is per user, so a
#      worktree loads what another one already compiled. And a fresh worktree —
#      no mobile/app/build, no mobile/.gradle — is first seeded from the warmest
#      checkout (seed_from_warm_checkout below): Kotlin's incremental caches and
#      Gradle's task history come with it, so only what differs is rebuilt.
#   5. Only the unit test task. Arguments are options (`--tests`, `--info`…),
#      never another task: APK, lint and Maestro belong to CI.
#   6. Its own cgroup, capped: Gradle and its daemons run in a transient systemd
#      scope (5 GB high, 6 GB max, 4 cores), so the build is what runs out of
#      memory, never Cyrus and its sessions with it.
#
# Thresholds live in wt/limits.env; exported variables win over it.

set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
root="$(cd "$here/.." && pwd)"

# The prod variant's tests by default. WT_MOBILE_VARIANT=e2e runs the e2e flavor's
# instead (`src/testE2e/`: the seams the Maestro journeys use), which no prod run
# compiles. Nothing else: still one unit test task, never an APK.
case "${WT_MOBILE_VARIANT:-prod}" in
    prod) task_name=':app:testProdDebugUnitTest' ;;
    e2e) task_name=':app:testE2eDebugUnitTest' ;;
    *) echo "WT_MOBILE_VARIANT='${WT_MOBILE_VARIANT}': expected prod or e2e." >&2; exit 64 ;;
esac
lock="${WT_GRADLE_LOCK:-${XDG_RUNTIME_DIR:-/tmp}/maggie-gradle.lock}"
wait_minutes="${WT_GRADLE_LOCK_WAIT_MINUTES:-30}"

# Rule 5: refuse a bare word, which Gradle would take for a task to run.
expect_value=0
for arg in "$@"; do
    if [ "$expect_value" = 1 ]; then
        expect_value=0
        continue
    fi
    case "$arg" in
        --tests) expect_value=1 ;;
        -*) ;;
        *)
            cat >&2 <<EOF
wt:test:mobile runs $task_name and nothing else; « $arg » looks like a Gradle task.
APK builds, lint and Maestro run on CI. Narrow the tests with:
  task wt:test:mobile -- --tests 'com.maggie.app.ui.screens.search.SearchResultTextTest'
EOF
            exit 64
            ;;
    esac
done

if [ "$#" = 0 ]; then
    echo "wt:test:mobile: every unit test of the app. Prefer -- --tests '<class or package>'." >&2
fi

# Rule 1. The lock is held by this script's descriptor 9, closed for Gradle
# below: the daemon Gradle starts outlives the build, and must not keep the lock.
exec 9>>"$lock"
if ! flock -n 9; then
    echo "Gradle occupé par un autre build, attente… (verrou $lock, ${wait_minutes} min au plus)" >&2
    if ! flock -w "$((wait_minutes * 60))" 9; then
        cat >&2 <<EOF

Gradle toujours occupé après ${wait_minutes} min : abandon.
Another build holds $lock (see: fuser -v $lock). Push the branch and let CI run
the mobile tests; exit 75 means the machine is busy, not that the change is wrong.
EOF
        exit 75
    fi
    echo "Verrou Gradle obtenu." >&2
fi

# Rule 2. A unit test run is the Gradle daemon, the Kotlin daemon and the test JVM.
WT_MIN_AVAILABLE_MB="${WT_MOBILE_MIN_AVAILABLE_MB:-5120}" "$here/guard.sh" 9>&-

# Rule 4. Copying build outputs from other code is safe because Gradle decides
# from inputs, not from the presence of outputs: a task whose inputs (sources,
# classpath, flags) differ from the recorded ones runs again, and Kotlin's
# incremental compile recompiles what changed. What is copied: the task history
# (mobile/.gradle) and the intermediates of mobile/app/build and mobile/build —
# not the APKs, reports or test results, which only a run should produce.
# WT_MOBILE_SEED=0 skips it.
seed_from_warm_checkout() {
    [ "${WT_MOBILE_SEED:-1}" != 0 ] || return 0
    [ ! -e "$root/mobile/app/build" ] && [ ! -e "$root/mobile/.gradle" ] || return 0
    local best="" best_time=0 path marker t
    # The main checkout and every worktree; the one whose prodDebug classes were
    # compiled last wins, else whichever built anything last.
    while read -r path; do
        [ "$path" != "$root" ] && [ -d "$path/mobile/app/build" ] || continue
        marker="$path/mobile/app/build/tmp/kotlin-classes/prodDebug"
        if [ -d "$marker" ]; then
            t=$(( $(stat -c %Y "$marker") + 10000000000 ))
        else
            t=$(stat -c %Y "$path/mobile/app/build")
        fi
        if [ "$t" -gt "$best_time" ]; then best="$path"; best_time="$t"; fi
    done < <(git -C "$root" worktree list --porcelain | sed -n 's/^worktree //p')
    if [ -z "$best" ]; then
        echo "wt:test:mobile: no warm checkout to seed from, the first build is cold." >&2
        return 0
    fi
    echo "wt:test:mobile: seeding this worktree's Gradle state from $best" >&2
    local excludes=(--exclude=/outputs/ --exclude=/reports/ --exclude=/test-results/)
    # A failed copy must not leave a half-seeded worktree that is never seeded
    # again: drop it all and build cold.
    if ! command -v rsync >/dev/null \
        || ! { mkdir -p "$root/mobile/app" \
            && rsync -a "${excludes[@]}" "$best/mobile/app/build/" "$root/mobile/app/build/" \
            && { [ ! -d "$best/mobile/build" ] || rsync -a "${excludes[@]}" "$best/mobile/build/" "$root/mobile/build/"; } \
            && { [ ! -d "$best/mobile/.gradle" ] || rsync -a "$best/mobile/.gradle/" "$root/mobile/.gradle/"; }; }; then
        echo "wt:test:mobile: seeding failed (rsync missing or copy error), the first build is cold." >&2
        rm -rf "$root/mobile/app/build" "$root/mobile/build" "$root/mobile/.gradle"
    fi
}
# Under the lock: no other wt build writes the source while it is copied. The
# owner's Android Studio or `mobile:install` may: Gradle then redoes what does
# not match, slower but still correct.
seed_from_warm_checkout

# Gradle resolves the SDK through ANDROID_HOME or mobile/local.properties, and a
# worktree has no local.properties of its own (same as e2e/mobile/build-apk.sh).
sdk_root="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
[ ! -d "$sdk_root" ] || export ANDROID_HOME="$sdk_root"

cd "$root/mobile"
# Rule 6. A cgroup of its own for the out-of-memory killer (9 Oct 2026: systemd-oomd
# killed Cyrus a 17th time, its Gradle daemon was in cyrus.service). Run from a
# Cyrus session, Gradle — and the daemons it forks, which outlive the build —
# would sit in the session's cgroup, and systemd-oomd kills a whole cgroup: Cyrus
# and every session with it. In a transient scope of their own, the build is
# what it kills. The scope is also capped (owner, 9 Oct.: « cap les ressources de
# Gradle »): throttled past 5 GB, killed past 6 GB, four cores at most; the
# daemons stay in it, so they stay capped from one build to the next. Not where `systemd-run --user` cannot reach a user manager (CI,
# containers): the command then runs as before.
scope=()
if [ -z "${WT_GRADLE_NO_SCOPE:-}" ] && command -v systemd-run >/dev/null 2>&1 \
    && systemd-run --user --scope --quiet -- true >/dev/null 2>&1; then
  scope=(systemd-run --user --scope --quiet --collect
         "--unit=wt-gradle-$(date +%s)-$$"
         -p "MemoryHigh=${WT_GRADLE_MEMORY_HIGH:-5G}"
         -p "MemoryMax=${WT_GRADLE_MEMORY_MAX:-6G}"
         -p "CPUQuota=${WT_GRADLE_CPU_QUOTA:-400%}" --)
fi
# Rule 3. Every build of this command asks for the same JVM options, so they all
# reuse one warm daemon pair.
${scope[@]+"${scope[@]}"} ./gradlew --console=plain \
    "-Dorg.gradle.jvmargs=${WT_GRADLE_JVMARGS:--Xmx1536m -XX:MaxMetaspaceSize=512m -XX:ActiveProcessorCount=4}" \
    "-Pkotlin.daemon.jvmargs=${WT_KOTLIN_DAEMON_JVMARGS:--Xmx1g -XX:ActiveProcessorCount=4}" \
    "-Dorg.gradle.daemon.idletimeout=${WT_GRADLE_IDLE_TIMEOUT_MS:-10800000}" \
    "--max-workers=${WT_GRADLE_MAX_WORKERS:-4}" \
    "$task_name" "$@" 9>&-
