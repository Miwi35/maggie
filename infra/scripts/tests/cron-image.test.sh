#!/usr/bin/env bash
# shellcheck disable=SC2015 # `cond && ok || bad`: ok and bad cannot fail
#
# Test of the cron image's scheduler (MAG-147).
#
# busybox crond ran the jobs as `app` with `>> /proc/1/fd/1`, a descriptor
# owned by root: the shell refused the redirection and the command never
# started, so Google Calendar stayed unsynchronized for months without a line
# in the logs. This starts the image as the user the pod runs it as and checks
# that a job runs as that non-root user, that a failing job leaves a readable
# trace on the container's output, and that the shipped crontab is valid and
# free of that redirection.
#
# Usage: IMAGE=<php image> infra/scripts/tests/cron-image.test.sh
#   IMAGE  Image to test. Default: built from .docker/php/Dockerfile (target e2e).
#   RUN_SECONDS  How long the scheduler runs. Default 6.

set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../../.." && pwd)"
RUN_SECONDS="${RUN_SECONDS:-6}"
failures=0

ok()  { printf '  \033[32m✓\033[0m %s\n' "$1"; }
bad() { printf '  \033[31m✗\033[0m %s\n' "$1"; failures=$((failures + 1)); }

if [ -z "${IMAGE:-}" ]; then
  IMAGE="maggie-php-cron-test:local"
  echo "Building $IMAGE"
  docker build -q -f "$ROOT/.docker/php/Dockerfile" --target e2e -t "$IMAGE" "$ROOT" >/dev/null || { echo "build failed"; exit 1; }
fi

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

echo
echo "1. The shipped crontab"
crontab_file="$ROOT/.docker/php/crontab"
if grep -v '^#' "$crontab_file" | grep -q '/proc/1/'; then
  bad "the crontab redirects to /proc/1/fd/*, which a non-root user cannot open"
else
  ok "no redirection to /proc/1/fd/*"
fi
for job in maggie:google-calendar:sync 'maggie:google-calendar:sync --tasks' maggie:google-calendar:renew-watch maggie:google-calendar:check-sync 'app:finance:sync --write' app:finance:check-consents; do
  grep -v '^#' "$crontab_file" | grep -qF "console $job" && ok "schedules $job" || bad "does not schedule $job"
done
if docker run --rm --entrypoint supercronic "$IMAGE" -test /etc/maggie/crontab >"$work/test.out" 2>&1; then
  ok "supercronic accepts the crontab baked in the image"
else
  bad "supercronic rejects /etc/maggie/crontab: $(tr '\n' ' ' <"$work/test.out" | tail -c 300)"
fi

echo
echo "2. Jobs run as the pod's non-root user and report on the container's output"
cat >"$work/crontab" <<'CRON'
* * * * * * * cd /var/www/api && echo "uid=$(id -u)" && php -r 'echo "php-ran-", 6 * 7, "\n";'
* * * * * * * echo "job-stderr-line" >&2; exit 3
CRON
# The user the prod image and the k8s manifest run it as; a seconds field
# (seven fields, seconds first) makes the jobs fire within the run window.
timeout "$RUN_SECONDS" docker run --rm --name "cron-image-test-$$" --user app \
  -v "$work/crontab:/tmp/test.crontab:ro" "$IMAGE" supercronic /tmp/test.crontab >"$work/run.out" 2>&1
docker rm -f "cron-image-test-$$" >/dev/null 2>&1 || true

grep -qE 'msg="uid=[1-9][0-9]*" channel=stdout' "$work/run.out" && ok "the job ran with a non-root uid" || bad "no job output with a non-root uid: $(tail -c 400 "$work/run.out")"
grep -q 'msg="uid=0"' "$work/run.out" && bad "a job ran as root"
grep -q 'msg=php-ran-42 channel=stdout' "$work/run.out" && ok "php started from /var/www/api" || bad "php did not run"
grep -q 'msg=job-stderr-line channel=stderr' "$work/run.out" && ok "a job's stderr reaches the container's output" || bad "a job's stderr is lost"
grep -qE 'level=error.*exit status 3' "$work/run.out" && ok "a failing job is logged with its exit status" || bad "the failure of a job is not logged: $(tail -c 400 "$work/run.out")"
grep -q "Permission denied" "$work/run.out" && bad "a job hit 'Permission denied'"

echo
if [ "$failures" -eq 0 ]; then
  printf '\033[1mcron image: all checks passed\033[0m\n'
else
  printf '\033[1mcron image: %d check(s) failed\033[0m\n' "$failures"
fi
[ "$failures" -eq 0 ]
