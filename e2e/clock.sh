#!/usr/bin/env bash
#
# The e2e clock (MAG-234): one instant for the whole stack.
#
# `E2E_NOW` fixes "now" for the API, the worker, the agent, the seed, the
# browser and the emulator. Unset, nothing changes: every service keeps the
# wall clock. This script is the only place that reads it, so every consumer
# resolves it the same way.
#
#   clock.sh resolve <spec>   an instant from a name or an ISO-8601 date
#   clock.sh faketime         E2E_NOW as a libfaketime time (UTC), or nothing
#   clock.sh epoch            E2E_NOW as seconds since the epoch, or nothing
#   clock.sh names            the named boundary instants
#
# Named instants resolve to the *next* occurrence after the real now, never a
# past one: Mercure rejects a token whose `exp` is in the past, and the
# simulated clock signs them, so an instant behind the real clock would take
# real-time down with it. A date in the past given as ISO-8601 is refused for
# the same reason.
set -euo pipefail

PARIS=Europe/Paris

die() {
  echo "clock.sh: $*" >&2
  exit 2
}

# The next `weekday` (1 = Monday … 7 = Sunday) at `time` (HH:MM) in Paris, today included.
next_weekday_paris() {
  local weekday=$1 time=$2 offset day candidate
  for offset in 0 1 2 3 4 5 6 7; do
    day=$(TZ=$PARIS date -d "today +${offset} days" +%F)
    [ "$(TZ=$PARIS date -d "$day" +%u)" = "$weekday" ] || continue
    candidate=$(TZ=$PARIS date -d "$day $time" --iso-8601=seconds)
    if [ "$(date -d "$candidate" +%s)" -gt "$(date +%s)" ]; then
      echo "$candidate"
      return
    fi
  done
  die "no occurrence of weekday $weekday at $time found"
}

# 02:30 on the last Sunday of October, in winter time (+01:00): the hour that
# happens twice, and the second time is the one that counts.
next_dst_fall_back() {
  local year day candidate
  for year in $(date +%Y) $(($(date +%Y) + 1)); do
    for day in 31 30 29 28 27 26 25; do
      [ "$(date -d "$year-10-$day" +%u)" = 7 ] || continue
      candidate="$year-10-${day}T02:30:00+01:00"
      if [ "$(date -d "$candidate" +%s)" -gt "$(date +%s)" ]; then
        echo "$candidate"
        return
      fi
      break
    done
  done
  die 'no DST fall-back found'
}

# 22:30 UTC on the next Saturday: Saturday in UTC, Sunday already in Paris.
next_saturday_utc() {
  local offset day candidate
  for offset in 0 1 2 3 4 5 6 7; do
    day=$(date -u -d "today +${offset} days" +%F)
    [ "$(date -u -d "$day" +%u)" = 6 ] || continue
    candidate="${day}T22:30:00+00:00"
    if [ "$(date -d "$candidate" +%s)" -gt "$(date +%s)" ]; then
      echo "$candidate"
      return
    fi
  done
  die 'no Saturday found'
}

resolve() {
  case "${1:-}" in
    sunday-2350-paris) next_weekday_paris 7 23:50 ;;
    monday-0050-paris) next_weekday_paris 1 00:50 ;;
    dst-fall-back-0230-paris) next_dst_fall_back ;;
    saturday-2230-utc) next_saturday_utc ;;
    '') die 'resolve needs a name or an ISO-8601 date' ;;
    *)
      local epoch
      epoch=$(date -d "$1" +%s 2>/dev/null) || die "not a date: $1"
      [ "$epoch" -gt "$(date +%s)" ] || die "$1 is in the past: Mercure refuses the tokens the simulated clock would sign"
      date -d "@$epoch" --iso-8601=seconds
      ;;
  esac
}

now_epoch() {
  [ -n "${E2E_NOW:-}" ] || return 0
  date -d "$E2E_NOW" +%s 2>/dev/null || die "E2E_NOW is not a date: $E2E_NOW"
}

case "${1:-}" in
  resolve) resolve "${2:-}" ;;
  names) printf '%s\n' sunday-2350-paris monday-0050-paris dst-fall-back-0230-paris saturday-2230-utc ;;
  epoch) now_epoch ;;
  # Starts at the instant and moves 10 µs forward per clock read, in every
  # process alike. Not frozen: `uniqid()` waits for the microsecond to change and
  # spins forever on a stopped clock (the seed hung on it). Not running at
  # real speed either: a 40-minute suite started at 23:50 would end after
  # midnight, which is what the instant is there to prevent. `sleep` and
  # timeouts are untouched, unlike a speed factor (`x0.01` multiplies them).
  faketime)
    epoch=$(now_epoch)
    [ -n "$epoch" ] || exit 0
    echo "@$(date -u -d "@$epoch" '+%Y-%m-%d %H:%M:%S') i0.00001"
    ;;
  *) die 'usage: clock.sh resolve <spec> | faketime | epoch | names' ;;
esac
