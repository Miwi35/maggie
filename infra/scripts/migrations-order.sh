#!/usr/bin/env bash
set -euo pipefail

# =============================================================================
# The migrations a change adds come after every migration of main (merge queue)
# Usage: migrations-order.sh <base-sha> <head-sha>
#
# Two PRs that each add a Doctrine migration can land back to back. Doctrine runs
# a migration it has not seen even when its version is older than the last one
# executed, so production applies them in merge order — while every fresh
# database (the e2e stack, a new environment, a restore) applies them in version
# order. Two orders, two schemas, as soon as the migrations touch the same table.
#
# So a change may only add migrations whose version is newer than the newest one
# of its base. Run on the merge group against the group's parent (the group of
# the PR ahead, or main): a PR cut before another migration landed or was queued
# is sent back to bump its version (rename the class and the file). Run on a PR
# too, as an early hint.
#
# Exit 0: in order, or no migration added. Exit 1: the offending files, and why.
# Needs both commits in the local repository.
# =============================================================================

BASE="${1:?usage: migrations-order.sh <base-sha> <head-sha>}"
HEAD="${2:?usage: migrations-order.sh <base-sha> <head-sha>}"
DIR="${MIGRATIONS_DIR:-api/migrations}"

version_of() { sed -n 's|.*/Version\([0-9]\{1,\}\)\.php$|\1|p' <<<"$1"; }

latest=""
while read -r file; do
  v="$(version_of "$file")"
  if [ -n "$v" ] && { [ -z "$latest" ] || [ "$v" -gt "$latest" ]; }; then latest="$v"; fi
done < <(git ls-tree -r --name-only "$BASE" -- "$DIR/")

added="$(git diff --name-only --diff-filter=A "$BASE" "$HEAD" -- "$DIR/")"
if [ -z "$added" ]; then
  echo "No migration added."
  exit 0
fi

late=0
while read -r file; do
  v="$(version_of "$file")"
  [ -n "$v" ] || continue
  if [ -n "$latest" ] && [ "$v" -le "$latest" ]; then
    echo "::error file=$file::$file (Version$v) is not newer than the newest migration of main (Version$latest): bump its version, the class name with the file name, or production and a fresh database apply the migrations in different orders."
    late=$((late + 1))
  else
    echo "In order: $file"
  fi
done <<<"$added"

[ "$late" -eq 0 ]
