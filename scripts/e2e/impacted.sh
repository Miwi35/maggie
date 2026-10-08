#!/usr/bin/env bash
#
# Which e2e journeys a change has to play (owner's decision, 8 Oct.).
#
# Usage:
#   impacted.sh select [--all] [--base <ref>] [--github-output]
#       The changed files, one per line, on stdin — or `git diff --name-only
#       <ref>...HEAD` with --base, or every journey with --all (the night). Prints
#       the selection as JSON: the journeys the changed files touch
#       (e2e/impact-map.yml), plus the critical core of every platform that plays
#       at all, the lots they run in, which of them are in quarantine, and why.
#       --github-output also writes the job outputs `ci.yml` reads, and a table
#       to the job summary.
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
        ((($map.areas // {}) | to_entries[]), {key: "transversal", value: ($map.transversal // [])}
          | select(.value | type != "array") | "\(.key) is not a list of globs")
      ] | .[]')"
  if [ -n "$problems" ]; then
    while IFS= read -r line; do echo "::error file=e2e/impact-map.yml::$line"; done <<<"$problems"
    exit 1
  fi
  echo "e2e/impact-map.yml: $(jq length <<<"$disk") journeys, all mapped."
}

cmd_select() {
  local all=false base='' github_output=false files map disk selection
  while [ $# -gt 0 ]; do
    case "$1" in
      --all) all=true ;;
      --base) base="${2:?--base needs a ref}"; shift ;;
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

  selection="$(jq -n \
    --argjson map "$map" --argjson files "$files" --argjson disk "$disk" \
    --argjson shards "$(shards_json)" --argjson all "$all" \
    --argjson per_lot "$WEB_FILES_PER_LOT" --argjson max_lots "$WEB_MAX_LOTS" \
    "$E2E_JQ_DEFS"'
    def res($globs): ($globs // []) | map(glob2re);
    def plat($id): if ($id | startswith("e2e/mobile/")) then "mobile" else "web" end;

    ($map | journeys) as $mapped
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

    | [ $files[] | select(matches($ignored) | not) | . as $f
        | if matches($all_re) then {file: $f, web: "all", mobile: "all", why: "transversal"}
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
        files: $per_file
      }')"

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
