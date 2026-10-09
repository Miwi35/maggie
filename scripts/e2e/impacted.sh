#!/usr/bin/env bash
#
# Which e2e journeys a change has to play (owner's decision, 8 Oct.).
#
# Usage:
#   impacted.sh select [--all] [--base <ref>] [--coverage-map <file> [--diff <file>]] [--github-output]
#       The changed files, one per line, on stdin — or `git diff --name-only
#       <ref>...HEAD` with --base, or every journey with --all (the night). Prints
#       the selection as JSON: the journeys the changed files touch
#       (e2e/impact-map.yml), plus the critical core of every platform that plays
#       at all, the lots they run in, which of them are in quarantine, and why.
#       --github-output also writes the job outputs `ci.yml` reads, and a table
#       to the job summary.
#
#       --coverage-map (or E2E_COVERAGE_MAP): the nightly's line → journeys map
#       (scripts/e2e/coverage/build-map.py, spec « Sélection e2e par
#       couverture »). The changed lines come from --diff (or E2E_COVERAGE_DIFF),
#       a `git diff -U0` of the change, or from `git diff -U0 <ref>...HEAD` with
#       --base. A file the map can judge (`coverage.sees` in the impact map)
#       whose every hunk touches a line some journey executed plays those
#       journeys (`why: coverage`); a hunk no journey executed adds the file's
#       zone rule; any other file keeps the zone rules alone. A hunk is its
#       old-side lines — what was modified or deleted — or, for a pure addition,
#       the lines on either side of it, looked up at the map's commit even when
#       main has moved since (lines drift a little: acceptable, the night plays
#       everything). No map, an unreadable one, one older than
#       E2E_COVERAGE_MAX_AGE_DAYS (3), or no diff: exactly the zone selection.
#   impacted.sh check
#       The map is sound: every journey on disk is in it and every journey in it
#       is on disk, every area it names exists, a critical journey is not in
#       quarantine, a quarantine says since when and why, each platform has a core.
#
# The rules are written at the top of e2e/impact-map.yml. A map that cannot be
# read selects every journey on disk, with a warning: `check` is the job that
# fails on it, not the selection.

set -euo pipefail

# shellcheck source=scripts/e2e/lib.sh
. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

SHARDS_FILE="${E2E_MOBILE_SHARDS:-$E2E_ROOT/e2e/mobile/shards.txt}"
# A Playwright lot takes about ten spec files before a fourth stack stops paying.
WEB_FILES_PER_LOT="${E2E_WEB_FILES_PER_LOT:-10}"
WEB_MAX_LOTS="${E2E_WEB_MAX_LOTS:-4}"

die() { echo "impacted.sh: $*" >&2; exit 2; }

# shards.txt as [{index, names: [flow names]}], in its order.
shards_json() {
  if [ -f "$SHARDS_FILE" ]; then
    grep -E '^[0-9]+:' "$SHARDS_FILE" | jq -Rn '[inputs | capture("^(?<index>[0-9]+):(?<rest>.*)$")
      | {index: (.index | tonumber), names: [.rest | splits("[[:space:]]+") | select(. != "")]}]'
  else
    echo '[]'
  fi
}

cmd_check() {
  local map disk problems
  map="$(e2e_map_json)" || { echo "::error::e2e/impact-map.yml cannot be read"; exit 1; }
  disk="$(e2e_journeys_on_disk | jq -Rn '[inputs]')"
  problems="$(jq -rn --argjson map "$map" --argjson disk "$disk" "$E2E_JQ_DEFS"'
    ($map | journeys) as $js
    | ($js | map(.id)) as $ids
    | [ ($disk[] | select(. as $d | $ids | index($d) | not) | "\(.) is not in the map: add it, with what it depends on"),
        ($ids[] | select(. as $i | $disk | index($i) | not) | "\(.) is in the map but not on disk"),
        ($js[] | . as $j | .areas[] | . as $a | select(($map.areas // {}) | has($a) | not) | "\($j.id) names the area \($a), which the map does not define"),
        ($js[] | select(.areas == [] and .paths == []) | "\(.id) depends on nothing: give it an area or paths"),
        ($js[] | select(.critical and .quarantine != null) | "\(.id) is critical and in quarantine: the core cannot be quarantined"),
        ($js[] | select(.quarantine != null)
          | select((.quarantine.since // "" | tostring | test("^[0-9]{4}-[0-9]{2}-[0-9]{2}$") | not) or ((.quarantine.reason // "") == ""))
          | "\(.id): a quarantine needs since: YYYY-MM-DD and a reason"),
        ("web", "mobile" | . as $p | select([$js[] | select(.platform == $p and .critical)] | length == 0)
          | "no critical \(.) journey: each platform keeps a core"),
        ((($map.areas // {}) | to_entries[]), {key: "transversal", value: ($map.transversal // [])},
          {key: "coverage.sees", value: ($map.coverage.sees // [])}
          | select(.value | type != "array") | "\(.key) is not a list of globs")
      ] | .[]')"
  if [ -n "$problems" ]; then
    while IFS= read -r line; do echo "::error file=e2e/impact-map.yml::$line"; done <<<"$problems"
    exit 1
  fi
  echo "e2e/impact-map.yml: $(jq length <<<"$disk") journeys, all mapped."
}

# The hunks of a unified diff (`git diff -U0`) on stdin, one JSON object per
# changed file: {file: its path now (the old one if deleted), old: its path
# before (null if added), hunks: [[first, last], …]} — the old-side lines a hunk
# modifies or deletes, or the two lines around a pure addition. Headers are only
# read before a file's first hunk: a deleted line starting with `-- ` is not one.
diff_hunks() {
  awk '
    function esc(s) { gsub(/\\/, "\\\\", s); gsub(/"/, "\\\"", s); gsub(/\t/, "\\t", s); gsub(/\r/, "\\r", s); return s }
    # git ends a header with a TAB when the path holds a space.
    function path(p) { sub(/\t$/, "", p); return (p == "/dev/null" ? "" : substr(p, 3)) }
    function flush() {
      if (seen) {
        file = (new != "" ? new : old)
        printf "{\"file\":\"%s\",\"old\":%s,\"hunks\":[%s]}\n", esc(file), (old == "" ? "null" : "\"" esc(old) "\""), hunks
      }
      seen = 0; old = ""; new = ""; hunks = ""
    }
    /^diff --git / { flush(); seen = 1; header = 1; next }
    header && /^--- / { old = path(substr($0, 5)); next }
    header && /^\+\+\+ / { new = path(substr($0, 5)); next }
    /^@@ / {
      header = 0
      n = split(substr($2, 2), o, ",")
      a = o[1] + 0; b = (n > 1 ? o[2] + 0 : 1)
      if (b > 0) { first = a; last = a + b - 1 } else { first = (a < 1 ? 1 : a); last = a + 1 }
      hunks = hunks (hunks == "" ? "" : ",") "[" first "," last "]"
    }
    END { flush() }
  '
}

# The line map to use, checked, in $COVERAGE_FILE — or none, and why, in
# $COVERAGE_REASON.
load_coverage_map() {
  local file="$1" generated now max
  COVERAGE_FILE=''
  COVERAGE_REASON=''
  if [ -z "$file" ]; then COVERAGE_REASON='no coverage map'; return; fi
  if [ ! -s "$file" ]; then COVERAGE_REASON="no coverage map at $file"; return; fi
  if ! jq -e '.version == 1 and (.journeys | type == "array" and length > 0) and (.files | type == "object")' "$file" >/dev/null 2>&1; then
    COVERAGE_REASON="the coverage map $file cannot be read"
    return
  fi
  generated="$(jq -r '.generatedAt | fromdateiso8601' "$file" 2>/dev/null)" || generated=''
  if [ -z "$generated" ]; then COVERAGE_REASON='the coverage map has no date'; return; fi
  now="${E2E_COVERAGE_NOW:-$(date +%s)}"
  max="${E2E_COVERAGE_MAX_AGE_DAYS:-3}"
  if [ $((now - generated)) -gt $((max * 86400)) ]; then
    COVERAGE_REASON="the coverage map of $(jq -r .generatedAt "$file") is older than $max days"
    return
  fi
  COVERAGE_FILE="$file"
}

cmd_select() {
  local all=false base='' github_output=false files map disk selection
  local coverage_map="${E2E_COVERAGE_MAP:-}" diff_file="${E2E_COVERAGE_DIFF:-}"
  while [ $# -gt 0 ]; do
    case "$1" in
      --all) all=true ;;
      --base) base="${2:?--base needs a ref}"; shift ;;
      --coverage-map) coverage_map="${2:?--coverage-map needs a file}"; shift ;;
      --diff) diff_file="${2:?--diff needs a file}"; shift ;;
      --github-output) github_output=true ;;
      *) die "unknown option $1" ;;
    esac
    shift
  done

  if [ "$all" = true ]; then
    files='[]'
  elif [ -n "$base" ]; then
    files="$(git -C "$E2E_ROOT" diff --name-only "$base...HEAD" | jq -Rn '[inputs | select(. != "")]')"
  else
    files="$(jq -Rn '[inputs | select(. != "")]')"
  fi

  disk="$(e2e_journeys_on_disk | jq -Rn '[inputs]')"
  if ! map="$(e2e_map_json 2>&1)" || ! jq -e 'type == "object"' >/dev/null 2>&1 <<<"$map"; then
    echo "::warning::e2e/impact-map.yml cannot be read: every journey plays. ${map//$'\n'/ }" >&2
    map='{}'
    all=true
  fi

  # The line map and the hunks are read by jq from files (--slurpfile): the map
  # is hundreds of KB, past what one argument may hold.
  SELECT_TMP="$(mktemp -d)"
  trap 'rm -rf "$SELECT_TMP"' EXIT
  echo null >"$SELECT_TMP/map.json"
  echo '[]' >"$SELECT_TMP/diff.json"
  load_coverage_map "$coverage_map"
  if [ "$all" = true ]; then
    COVERAGE_FILE=''
    COVERAGE_REASON='the whole suite plays'
  elif [ -n "$COVERAGE_FILE" ]; then
    if [ -n "$diff_file" ] && [ -f "$diff_file" ]; then
      diff_hunks <"$diff_file" | jq -s . >"$SELECT_TMP/diff.json" \
        || COVERAGE_REASON="the diff $diff_file cannot be read"
    elif [ -n "$base" ]; then
      { git -C "$E2E_ROOT" diff -U0 --no-color --no-ext-diff -M --src-prefix=a/ --dst-prefix=b/ "$base...HEAD" \
          | diff_hunks | jq -s . >"$SELECT_TMP/diff.json"; } \
        || COVERAGE_REASON="no diff against $base"
    else
      COVERAGE_REASON='no diff to read the changed lines from'
    fi
    if [ -n "$COVERAGE_REASON" ]; then
      COVERAGE_FILE=''
      echo '[]' >"$SELECT_TMP/diff.json"
    fi
  fi
  if [ -n "$COVERAGE_FILE" ]; then
    cp "$COVERAGE_FILE" "$SELECT_TMP/map.json"
  elif [ -n "$coverage_map" ]; then
    echo "::notice::e2e journeys selected by zones: $COVERAGE_REASON" >&2
  fi

  select_json() {
    jq -n \
    --argjson map "$map" --argjson files "$files" --argjson disk "$disk" \
    --argjson shards "$(shards_json)" --argjson all "$all" \
    --argjson per_lot "$WEB_FILES_PER_LOT" --argjson max_lots "$WEB_MAX_LOTS" \
    --slurpfile covs "$SELECT_TMP/map.json" --slurpfile diffs "$SELECT_TMP/diff.json" \
    --arg cov_reason "$COVERAGE_REASON" \
    "$E2E_JQ_DEFS"'
    def res($globs): ($globs // []) | map(glob2re);
    def plat($id): if ($id | startswith("e2e/mobile/")) then "mobile" else "web" end;
    # A hex bitmask of the line map, as the indices of its set bits.
    def hex2idx:
      ascii_downcase | explode | reverse | to_entries
      | map(.key as $k | (.value | if . >= 97 then . - 87 else . - 48 end) as $d
            | range(0; 4) as $b | select((($d / pow(2; $b)) | floor) % 2 == 1) | $k * 4 + $b);

    $covs[0] as $cov
    | ($diffs[0] | map({key: .file, value: .}) | from_entries) as $diff

    | ($map | journeys) as $mapped
    # A journey on disk the map forgot plays every time (and `check` fails).
    | ($mapped | map(.id)) as $mapped_ids
    | ($mapped + [$disk[] | select(. as $d | $mapped_ids | index($d) | not)
        | {id: ., platform: plat(.), critical: false, forgotten: true, areas: [], paths: []}]
       | map(select(.id as $i | $disk | index($i)))) as $js
    | ($js | map(. + {res: (globs_of($map) | map(glob2re))})) as $js
    # What claims a file for `unmapped`: the own file and paths of a journey, and
    # the areas a journey outside the core uses — an area only the core uses
    # claims nothing, the core plays anyway. A file of one platform only (`only`)
    # is claimed by the journeys of that platform alone: an Android screen that
    # only a web journey maps is still unknown to the Maestro flows.
    | def claims($js):
        ([$js[] | select(.critical | not) | .areas[]] | unique) as $areas
        | [$js[] | .id, .paths[]] + [$areas[] as $a | ($map.areas[$a] // [])[]] | map(glob2re);
    claims($js) as $claims_any
    | {web: claims([$js[] | select(.platform == "web")]), mobile: claims([$js[] | select(.platform == "mobile")])} as $claims
    | (res($map.only.web) + res($map.only.mobile)) as $only_any
    | res($map.ignored) as $ignored
    | res($map.transversal) as $all_re
    | res($map.transversal_web) as $web_re
    | res($map.transversal_mobile) as $mobile_re
    | res($map.unmapped.all) as $u_all
    | res($map.unmapped.web) as $u_web
    | res($map.unmapped.mobile) as $u_mobile
    | {web: res($map.only.mobile), mobile: res($map.only.web)} as $other_only

    | res($map.coverage.sees) as $sees
    | ($js | map(.id)) as $known
    # The zone rule of a file: the whole selection before the line map.
    | def zone($f): $f | if matches($all_re) then {file: $f, web: "all", mobile: "all", why: "transversal"}
          else
            ([$js[] | select(. as $j | ($f | matches($other_only[$j.platform]) | not) and ($f | matches($j.res))) | .id]) as $hit
            | (if matches($only_any) then {web: matches($claims.web), mobile: matches($claims.mobile)}
               else matches($claims_any) as $c | {web: $c, mobile: $c} end) as $claimed
            | (($claimed.web | not) and (matches($u_all) or matches($u_web))) as $unknown_web
            | (($claimed.mobile | not) and (matches($u_all) or matches($u_mobile))) as $unknown_mobile
            | {file: $f,
               web: (if matches($web_re) or $unknown_web then "all" else null end),
               mobile: (if matches($mobile_re) or $unknown_mobile then "all" else null end),
               journeys: $hit,
               why: (if matches($web_re) then "transversal_web" elif matches($mobile_re) then "transversal_mobile"
                     elif $unknown_web or $unknown_mobile then "unmapped"
                     elif ($hit | length) > 0 then "journeys" else "nothing" end)}
          end;
    # Each hunk of a file the line map can judge, with the journeys that executed
    # one of its lines at the map'"'"'s commit; null when the map cannot judge the file
    # (not in `coverage.sees`, added, renamed without a hunk, or never executed).
    def hunks($f):
      ($diff[$f] // null) as $d
      | if $cov == null or $d == null then null
        elif $d.old == null or ($d.hunks | length) == 0 or (($f | matches($sees)) | not)
          or (($cov.files[$d.old] // null) == null) then null
        else $cov.files[$d.old] as $ranges
          | [ $d.hunks[] as $h
              | {lines: $h,
                 journeys: ([$ranges[] | select(.[0] <= $h[1] and .[1] >= $h[0]) | .[2] | hex2idx[]] | unique
                            | map($cov.journeys[.] // empty | select(. as $i | $known | index($i))) | unique)} ]
        end;

    [ $files[] | select(matches($ignored) | not) | . as $f
      | hunks($f) as $h
      | if $h != null and all($h[]; (.journeys | length) > 0) then
          {file: $f, web: null, mobile: null, journeys: ([$h[].journeys[]] | unique), why: "coverage", lines: [$h[].lines]}
        else zone($f) as $z
          | if $h != null and any($h[]; (.journeys | length) > 0) then
              $z + {journeys: (($z.journeys // []) + [$h[].journeys[]] | unique),
                    lines: [$h[] | select((.journeys | length) > 0) | .lines],
                    line_journeys: ([$h[].journeys[]] | unique),
                    uncovered: [$h[] | select((.journeys | length) == 0) | .lines]}
            else $z end
        end ] as $per_file

    | def pick($p):
        if $all or any($per_file[]; .[$p] == "all") then [$js[] | select(.platform == $p) | .id]
        else
          ([$per_file[] | .journeys[]?] | unique) as $hit
          | [$js[] | select(.platform == $p and (.id as $i | $hit | index($i)))] as $impacted
          | if ($impacted | length) == 0 then []
            else [$js[] | select(.platform == $p and (.critical or .forgotten == true or (.id as $i | $hit | index($i)))) | .id]
            end
        end | sort;

    pick("web") as $web
    | pick("mobile") as $mobile
    | [$js[] | select(.quarantine != null) | .id] as $quarantine
    | ([($web | length) / $per_lot | ceil, $max_lots] | min) as $web_lots
    # Mobile lots follow shards.txt, the balance measured there; a lot left empty
    # by the selection is dropped. In a lot the journeys in quarantine come last,
    # so one that stops the run cannot hide the others.
    | ($mobile | map(ltrimstr("e2e/mobile/"))) as $flows
    | [ ($shards[] | [.names[] | "flows/\(.).yaml" | select(. as $f | $flows | index($f))]),
        [$flows[] | . as $f | select([$shards[].names[] | "flows/\(.).yaml"] | index($f) | not)]
      | select(length > 0) ] as $shard_lots
    # A lot of journeys in quarantine alone could never block: its flows join the
    # last lot that can, rather than hold an emulator and a stack of their own.
    | def blocking: any(.[]; ("e2e/mobile/" + .) as $id | $quarantine | index($id) | not);
    ([$shard_lots[] | select(blocking)]) as $real
    | ([$shard_lots[] | select(blocking | not) | .[]]) as $stray
    | (if ($real | length) > 0 and ($stray | length) > 0 then $real[:-1] + [$real[-1] + $stray] else $shard_lots end)
    | [.[] | sort_by(("e2e/mobile/" + .) as $id | $quarantine | index($id) != null)] as $mobile_lots
    | {
        full: {web: ($all or any($per_file[]; .web == "all")), mobile: ($all or any($per_file[]; .mobile == "all"))},
        web: $web,
        mobile: $mobile,
        quarantined: [($web + $mobile)[] | select(. as $i | $quarantine | index($i))],
        forgotten: [$js[] | select(.forgotten == true) | .id],
        web_lots: [range(1; $web_lots + 1)],
        web_specs: ($web | map(ltrimstr("e2e/web/")) | join(" ")),
        mobile_lots: [$mobile_lots | to_entries[] | {index: (.key + 1), flows: (.value | join(" "))}],
        coverage: (if $cov == null then {used: false, reason: $cov_reason}
                   else {used: true, commit: $cov.commit, generatedAt: $cov.generatedAt} end),
        files: $per_file
      }'
  }

  # The line map must never cost a pull request its selection: if it cannot be
  # applied (an entry jq cannot read, say), the zones decide, with a warning.
  if ! selection="$(select_json)"; then
    [ -n "$COVERAGE_FILE" ] || die "the selection failed"
    echo "::warning::the line map could not be applied: e2e journeys selected by zones" >&2
    echo null >"$SELECT_TMP/map.json"
    echo '[]' >"$SELECT_TMP/diff.json"
    COVERAGE_FILE=''
    COVERAGE_REASON='the line map could not be applied'
    selection="$(select_json)"
  fi

  if [ "$github_output" = true ]; then
    write_github_output "$selection"
  fi
  printf '%s\n' "$selection"
}

write_github_output() {
  local selection="$1"
  {
    echo "e2e=$(jq '.web | length > 0' <<<"$selection")"
    echo "mobile=$(jq '.mobile | length > 0' <<<"$selection")"
    echo "web_specs=$(jq -r '.web_specs' <<<"$selection")"
    echo "web_lots=$(jq -c '.web_lots' <<<"$selection")"
    echo "web_lot_count=$(jq '.web_lots | length' <<<"$selection")"
    echo "mobile_lots=$(jq -c '.mobile_lots' <<<"$selection")"
    echo "mobile_lot_count=$(jq '.mobile_lots | length' <<<"$selection")"
  } >>"${GITHUB_OUTPUT:?GITHUB_OUTPUT is not set}"

  [ -n "${GITHUB_STEP_SUMMARY:-}" ] || return 0
  jq -r '
    def scope($p): if .full[$p] then "all of them" elif (.[$p] | length) == 0 then "none" else "\(.[$p] | length)" end;
    def names($p): .[$p] | map(split("/") | last) | join(", ");
    "### E2E journeys of this change",
    "",
    "| | Journeys | Lots |",
    "|---|---|---|",
    "| Web (Playwright) | \(scope("web")) | \(.web_lots | length) |",
    "| Mobile (Maestro) | \(scope("mobile")) | \(.mobile_lots | length) |",
    "",
    (if .coverage.used then "Sélection par couverture (carte du \(.coverage.generatedAt[:10]), \(.coverage.commit[:7])) — e2e/impact-map.yml for the rest.\n"
     else "Carte par zones (\(.coverage.reason)).\n" end),
    ([.files[] | select(.lines != null)] as $cov
      | if ($cov | length) > 0 then
          "<details><summary>Lines that chose journeys (coverage)</summary>\n",
          ($cov[:30][] | "- `\(.file)` lines \(.lines | map(if .[0] == .[1] then "\(.[0])" else "\(.[0])–\(.[1])" end) | join(", "))"
             + " → \((.line_journeys // .journeys) | map(split("/") | last) | join(", "))"
             + (if (.uncovered // []) != [] then " · no journey ran lines \(.uncovered | map("\(.[0])–\(.[1])") | join(", ")): \(.why)" else "" end)),
          (if ($cov | length) > 30 then "- … and \($cov | length - 30) more" else empty end),
          "\n</details>\n"
        else empty end),
    (if (.web | length) > 0 and (.full.web | not) then "**Web:** \(names("web"))\n" else empty end),
    (if (.mobile | length) > 0 and (.full.mobile | not) then "**Mobile:** \(names("mobile"))\n" else empty end),
    (if (.quarantined | length) > 0 then "**In quarantine** (played, never blocking): \(.quarantined | map(split("/") | last) | join(", "))\n" else empty end),
    (if (.forgotten | length) > 0 then "**Not in e2e/impact-map.yml** (played every time until mapped): \(.forgotten | join(", "))\n" else empty end),
    ([.files[] | select(.why == "transversal" or .why == "transversal_web" or .why == "transversal_mobile" or .why == "unmapped")] as $wide
      | if ($wide | length) > 0 then
          "<details><summary>Why the whole suite of a platform plays</summary>\n",
          ($wide[:30][] | "- `\(.file)` — \(.why)"),
          (if ($wide | length) > 30 then "- … and \($wide | length - 30) more" else empty end),
          "\n</details>"
        else empty end)
  ' <<<"$selection" >>"$GITHUB_STEP_SUMMARY"
}

case "${1:-}" in
  select) shift; cmd_select "$@" ;;
  check) shift; cmd_check "$@" ;;
  *) die "usage: impacted.sh select [--all] [--base <ref>] [--github-output] | impacted.sh check" ;;
esac
