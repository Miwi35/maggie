#!/usr/bin/env bash
# shellcheck disable=SC2034 # E2E_JQ_DEFS is read by the scripts that source this file
#
# Shared by impacted.sh, verdict.sh and mobile-journeys.sh: where the map is, how
# it is read, and the glob syntax it is written in.

E2E_ROOT="${E2E_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)}"
E2E_MAP="${E2E_IMPACT_MAP:-$E2E_ROOT/e2e/impact-map.yml}"

# The map as JSON on stdout. yq is on every GitHub runner; PyYAML is the fallback
# for a machine without it. Fails (status 1, a message on stderr) on a file that
# is missing or not YAML.
e2e_map_json() {
  local map="${1:-$E2E_MAP}"
  [ -f "$map" ] || { echo "impact map not found: $map" >&2; return 1; }
  if command -v yq >/dev/null 2>&1; then
    yq -o=json '.' "$map"
  elif python3 -c 'import yaml' >/dev/null 2>&1; then
    python3 -c 'import json, sys, yaml; json.dump(yaml.safe_load(open(sys.argv[1])), sys.stdout, default=str)' "$map"
  else
    echo "reading $map needs yq or python3 with PyYAML" >&2
    return 1
  fi
}

# jq definitions:
#   glob2re  — a map glob as an anchored regex: `**` crosses directories, `*` and
#              `?` do not, `{a,b}` is either.
#   matches($res) — the input path matches one of these regexes.
#   journeys — every journey of the map, as {id, platform, critical, areas, paths,
#              quarantine}.
#   globs_of($j) — what a journey depends on: its own file, its paths, its areas.
E2E_JQ_DEFS='
def glob2re:
  gsub("(?<c>[.+()|^$\\[\\]\\\\])"; "\\\(.c)")
  | gsub("\\*\\*/"; "\u0001")
  | gsub("\\*\\*"; "\u0002")
  | gsub("\\*"; "[^/]*")
  | gsub("\\?"; "[^/]")
  | gsub("\u0001"; "(?:.*/)?")
  | gsub("\u0002"; ".*")
  | gsub("\\{(?<alts>[^}]*)\\}"; "(?:" + (.alts | gsub(","; "|")) + ")")
  | "^" + . + "$";
def matches($res): . as $path | any($res[]; . as $re | $path | test($re));
def journeys:
  [ (.web // {} | to_entries[] | {platform: "web"} + {id: .key} + (.value // {})),
    (.mobile // {} | to_entries[] | {platform: "mobile"} + {id: .key} + (.value // {})) ]
  | map(.critical = (.critical == true) | .areas = (.areas // []) | .paths = (.paths // []));
def globs_of($map): [.id] + .paths + [.areas[] as $a | ($map.areas[$a] // [])[]];
'

# The journeys on disk, one repo-relative path per line.
e2e_journeys_on_disk() {
  (cd "$E2E_ROOT" && {
    find e2e/web/tests -maxdepth 1 -name '*.spec.ts' 2>/dev/null
    find e2e/mobile/flows -maxdepth 1 -name '*.yaml' 2>/dev/null
  } | sort)
}

# The name Maestro reports a flow under: its `name:` key, its file name otherwise.
e2e_flow_name() {
  local file="$1" name
  name="$(sed -n '1,/^---/ s/^name:[[:space:]]*//p' "$E2E_ROOT/$file" 2>/dev/null | head -1 | tr -d '\r"'"'")"
  [ -n "$name" ] || name="$(basename "$file" .yaml)"
  printf '%s\n' "$name"
}
