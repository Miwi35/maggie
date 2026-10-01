#!/usr/bin/env bash
#
# Installs the pinned Maestro CLI and prints the path to its binary (MAG-98).
#
#   eval "$(e2e/mobile/maestro.sh)"   # exports MAESTRO
#   MAESTRO=$(e2e/mobile/maestro.sh --path)
#
# Pinned by version *and* by the sha256 the release publishes, for the same
# reason the Mercure image is pinned by digest: `curl | bash` from
# get.maestro.mobile.dev installs whatever shipped this morning, and a CLI that
# changed how it matches a Compose node would turn every flow red on a branch
# that touched nothing. Bumping it is a one-line commit with the new checksum,
# which is a reviewable change rather than a surprise.
#
# Installed under .e2e-cache/ (gitignored) next to the agent's uv cache: the
# archive is ~300 MB, CI caches that directory by this script's hash, and a
# second run on the same machine is a no-op.

set -euo pipefail

VERSION="${MAESTRO_VERSION:-cli-2.10.0}"
SHA256="${MAESTRO_SHA256:-29b675e10cc12080e445e9bfb2e2b4e4dfb9c0f2e30d5884120d258b5e1cd991}"
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
TARGET="$REPO_ROOT/.e2e-cache/maestro/$VERSION"
BINARY="$TARGET/maestro/bin/maestro"
# Global rather than local to install_maestro: the EXIT trap below fires after
# the function has returned, when a local would already be out of scope.
tmp=""
trap '[ -n "$tmp" ] && rm -rf "$tmp"' EXIT

install_maestro() {
  [ -x "$BINARY" ] && return 0

  command -v unzip >/dev/null || {
    echo "unzip is required to install Maestro — apt install unzip" >&2
    exit 1
  }

  local url="https://github.com/mobile-dev-inc/Maestro/releases/download/$VERSION/maestro.zip"
  # Unpacked into $TARGET only after the checksum matched, so a failed download
  # never leaves a tree the next run would mistake for a finished install.
  tmp="$(mktemp -d)"

  echo "Downloading Maestro $VERSION…" >&2
  curl -fsSL -o "$tmp/maestro.zip" "$url"

  local actual
  actual="$(sha256sum "$tmp/maestro.zip" | cut -d' ' -f1)"
  if [ "$actual" != "$SHA256" ]; then
    echo "Maestro $VERSION checksum mismatch: expected $SHA256, got $actual." >&2
    echo "Either the release was replaced or the download is corrupt — do not run it." >&2
    exit 1
  fi

  rm -rf "$TARGET"
  mkdir -p "$TARGET"
  unzip -q "$tmp/maestro.zip" -d "$TARGET"
  [ -x "$BINARY" ] || {
    echo "Maestro $VERSION unpacked without $BINARY — the archive layout changed." >&2
    exit 1
  }
}

# Maestro is a JVM program and its launcher needs a JDK. CI sets one up; on the
# owner's machine `java` is often only inside Android Studio's bundled runtime,
# which is already where Gradle takes its own from.
resolve_java_home() {
  [ -n "${JAVA_HOME:-}" ] && return 0
  command -v java >/dev/null && return 0

  local gradle_jdk
  gradle_jdk="$(sed -n 's/^org\.gradle\.java\.home=//p' "$REPO_ROOT/mobile/gradle.properties" | tail -1)"
  if [ -n "$gradle_jdk" ] && [ -x "$gradle_jdk/bin/java" ]; then
    export JAVA_HOME="$gradle_jdk"
    return 0
  fi

  echo "No JDK found: install one, or set JAVA_HOME. Maestro runs on the JVM." >&2
  exit 1
}

install_maestro
resolve_java_home

if [ "${1:-}" = "--path" ]; then
  printf '%s\n' "$BINARY"
else
  # `eval`-able, so a caller gets JAVA_HOME too when it had to be resolved.
  printf 'export MAESTRO=%q\n' "$BINARY"
  if [ -n "${JAVA_HOME:-}" ]; then
    printf 'export JAVA_HOME=%q\n' "$JAVA_HOME"
  fi
fi
