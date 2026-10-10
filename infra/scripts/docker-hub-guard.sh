#!/usr/bin/env bash
set -euo pipefail

# =============================================================================
# No image from Docker Hub in CI
# Usage: infra/scripts/docker-hub-guard.sh [repository root]
#
# CI pulled its third-party images anonymously from Docker Hub. GitHub runners
# share their IPs, so Docker Hub's anonymous pull limit (`toomanyrequests`) took
# every e2e job, API test and image build down for hours, twice (9–10 Oct.).
# Official images now come from Amazon's public copy (public.ecr.aws/docker/
# library/…), the others from the project's mirror on GHCR (ghcr.io/miwi35/
# mirror/…, refreshed by .github/workflows/mirror-images.yml).
#
# Red when one of these pulls from Docker Hub — no registry host (`postgres:17`,
# `wiremock/wiremock:3.9.1`) or `docker.io/…` — or from a registry not allowed
# below:
#   - every Dockerfile: `FROM` and `COPY --from=<image>` (not a stage name);
#   - the compose files CI, the e2e stack and the worktree checks start
#     (COMPOSE_FILES below): `image:`;
#   - every workflow: `image:` (services, container) and every
#     `docker/setup-buildx-action`, whose BuildKit image is `moby/buildkit` from
#     Docker Hub unless `driver-opts: image=…` says otherwise.
#
# `${VAR:-default}` resolves to its default, a Dockerfile `${ARG}` to the ARG's
# default; an image left empty (`${VAR:?…}`, chosen by the caller) is skipped.
# The project's own local images (`maggie…`, no registry) are allowed.
#
# Exceptions: one line per image in .github/docker-hub-allowlist.txt —
# `<image as resolved> <reason>`; a line without a reason is an error.
# Tested by infra/scripts/tests/docker-hub-guard.test.sh.
# =============================================================================

ROOT="${1:-$(git rev-parse --show-toplevel 2>/dev/null || pwd)}"
cd "$ROOT"

ALLOWED_HOSTS='public.ecr.aws ghcr.io mcr.microsoft.com'
COMPOSE_FILES=(docker-compose.yml docker-compose.e2e.yml wt/docker-compose.wt.yml)
ALLOWLIST=.github/docker-hub-allowlist.txt

dockerfiles=()
while IFS= read -r f; do
  [[ -f "$f" ]] && dockerfiles+=("$f")
done < <(git ls-files --cached --others --exclude-standard -- '*Dockerfile' '*Dockerfile.*' '*.Dockerfile' 2>/dev/null | sort -u)

composes=()
for f in "${COMPOSE_FILES[@]}"; do
  [[ -f "$f" ]] && composes+=("$f")
done

workflows=()
for f in .github/workflows/*.yml .github/workflows/*.yaml; do
  [[ -f "$f" ]] && workflows+=("$f")
done

allowlist=/dev/null
[[ -f "$ALLOWLIST" ]] && allowlist="$ALLOWLIST"

# One awk program for the three kinds of file: `kind` is set before each group.
# Each reference prints `ok` or `bad <file>:<line>: <message>`.
awk -v allowed_hosts="$ALLOWED_HOSTS" -v allowlist_file="$ALLOWLIST" -v q="'" '
function trim(s) { sub(/^[ \t]+/, "", s); sub(/[ \t\r]+$/, "", s); return s }
function unquote(s) { gsub("^[\"" q "]|[\"" q "]$", "", s); return s }
function indent(s) { match(s, /^[ \t]*/); return RLENGTH }
function buildx_missing() {
  printf "%s:%d: docker/setup-buildx-action without driver-opts image=… pulls moby/buildkit from Docker Hub — add `driver-opts: image=ghcr.io/miwi35/mirror/buildkit:buildx-stable-1`\n", buildx_file, buildx_line
  bad++
  buildx_open = 0
}

# ${VAR:-d} / ${VAR-d} -> d, ${VAR:?m} -> "", ${VAR} / $VAR -> the ARG default or "".
function resolve(ref,    m, name, inner, op, val, guard) {
  guard = 0
  while (match(ref, /\$\{[A-Za-z_][A-Za-z0-9_]*(:?[-?+=][^${}]*)?\}/) && guard++ < 20) {
    m = substr(ref, RSTART + 2, RLENGTH - 3)
    name = m; sub(/[:?+=-].*$/, "", name)
    inner = substr(m, length(name) + 1)
    val = (name in args) ? args[name] : ""
    if (inner != "") {
      op = inner; sub(/^:/, "", op); op = substr(op, 1, 1)
      inner = substr(inner, index(inner, op) + 1)
      if (op == "-" || op == "=") val = ((name in args) && args[name] != "") ? args[name] : inner
      else if (op == "?") val = ""
      else if (op == "+") val = ""
    }
    ref = substr(ref, 1, RSTART - 1) val substr(ref, RSTART + RLENGTH)
  }
  while (match(ref, /\$[A-Za-z_][A-Za-z0-9_]*/)) {
    name = substr(ref, RSTART + 1, RLENGTH - 1)
    val = (name in args) ? args[name] : ""
    ref = substr(ref, 1, RSTART - 1) val substr(ref, RSTART + RLENGTH)
  }
  return ref
}

function check(raw, where,    ref, first, host, n, h) {
  raw = unquote(trim(raw))
  if (raw == "" || raw ~ /\$\{\{/) return   # empty or a workflow expression
  ref = resolve(raw)
  if (ref == "" || ref ~ /^[:@-]/) return   # chosen by the caller
  if (ref == "scratch") return
  checked++
  if (ref in allowed) return
  first = ref; sub(/\/.*$/, "", first)
  if (index(ref, "/") && (first ~ /[.:]/ || first == "localhost")) host = first
  else host = ""
  if (host == "" && ref !~ /\// && ref ~ /^maggie/) return   # the project own local image
  if (host == "" || host == "docker.io" || host == "index.docker.io" || host == "registry-1.docker.io") {
    printf "%s: %s pulls from Docker Hub — official image: public.ecr.aws/docker/library/<name>; other: ghcr.io/miwi35/mirror/<name> (.github/mirror-images.txt)\n", where, ref
    bad++
    return
  }
  n = split(allowed_hosts, h, " ")
  for (i = 1; i <= n; i++) if (host == h[i]) return
  printf "%s: %s comes from %s, not an allowed registry (%s) — add it to %s with a reason if it must stay\n", where, ref, host, allowed_hosts, allowlist_file
  bad++
}

FNR == 1 {
  if (buildx_open) buildx_missing()
  delete args; delete stages
}

# The allow-list: `<image> <reason>`.
kind == "allowlist" {
  line = trim($0)
  if (line == "" || line ~ /^#/) next
  if (NF < 2) { printf "%s:%d: %s has no reason\n", FILENAME, FNR, $1; bad++; next }
  allowed[$1] = 1
  next
}

kind == "dockerfile" {
  line = trim($0)
  if (line ~ /^#/) next
  if (match(line, /^[Aa][Rr][Gg][ \t]+[A-Za-z_][A-Za-z0-9_]*=/)) {
    a = line; sub(/^[Aa][Rr][Gg][ \t]+/, "", a)
    k = a; sub(/=.*$/, "", k); v = a; sub(/^[^=]*=/, "", v); args[k] = unquote(v)
    next
  }
  if (line ~ /^[Ff][Rr][Oo][Mm][ \t]/) {
    n = split(line, t, /[ \t]+/)
    img = ""
    for (i = 2; i <= n; i++) if (t[i] !~ /^--/) { img = t[i]; break }
    for (j = i + 1; j < n; j++) if (tolower(t[j]) == "as") stages[tolower(t[j + 1])] = 1
    if (!(tolower(img) in stages)) check(img, FILENAME ":" FNR)
    next
  }
  if (match(line, /--from=[^ \t]+/)) {
    img = substr(line, RSTART + 7, RLENGTH - 7)
    if (img !~ /^[0-9]+$/ && !(tolower(img) in stages)) check(img, FILENAME ":" FNR)
  }
  next
}

kind == "compose" || kind == "workflow" {
  line = $0
  if (line ~ /^[ \t]*#/) next
  if (kind == "workflow") {
    # A setup-buildx step: its BuildKit image must be named before the next step.
    if (buildx_open && trim(line) != "" && (line ~ /^[ \t]*- / || indent(line) <= buildx_indent)) buildx_missing()
    if (line ~ /uses:[ \t]*docker\/setup-buildx-action/) {
      buildx_open = 1; buildx_file = FILENAME; buildx_line = FNR; buildx_indent = indent(line)
      next
    }
    if (buildx_open && match(line, /image=[^ \t,"]+/)) {
      check(substr(line, RSTART + 6, RLENGTH - 6), FILENAME ":" FNR)
      buildx_open = 0
      next
    }
    # `docker run|pull <image>` in a step: the first word that is neither an
    # option nor the value of an option, on the same line.
    if (match(line, /docker[ \t]+(run|pull)[ \t]/)) {
      n = split(substr(line, RSTART + RLENGTH), t, /[ \t]+/)
      for (i = 1; i <= n; i++) {
        if (t[i] == "" || t[i] ~ /^-.*=/) continue
        if (t[i] ~ /^(-[vweplu]|--(volume|workdir|env|env-file|publish|label|user|name|network|entrypoint|platform|mount|add-host|cpus|memory))$/) { i++; continue }
        if (t[i] ~ /^-/) continue
        if (t[i] != "\\" && t[i] !~ /^\$/) check(t[i], FILENAME ":" FNR)
        break
      }
    }
    if (match(line, /^[ \t]*container:[ \t]*[^ \t{]/)) {
      v = line; sub(/^[ \t]*container:/, "", v); sub(/[ \t]+#.*$/, "", v)
      check(v, FILENAME ":" FNR)
      next
    }
  }
  if (match(line, /^[ \t]*(-[ \t]+)?image:[ \t]*/)) {
    v = substr(line, RLENGTH + 1); sub(/[ \t]+#.*$/, "", v)
    if (trim(v) != "") check(v, FILENAME ":" FNR)
  }
  next
}

END {
  if (buildx_open) buildx_missing()
  printf "%d image reference(s) checked, %d finding(s)\n", checked, bad
  exit (bad > 0)
}
' kind=allowlist "$allowlist" \
  kind=dockerfile "${dockerfiles[@]}" \
  kind=compose "${composes[@]}" \
  kind=workflow "${workflows[@]}"
