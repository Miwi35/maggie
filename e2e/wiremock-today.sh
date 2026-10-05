#!/usr/bin/env bash
#
# Points the "all-day today" Google event (MAG-204) at the seed's anchor day
# (MAG-234).
#
# WireMock's `{{now}}` reads the JVM's clock, which libfaketime does not move and
# which disagrees with the seed's anchor for an hour or two each night in Paris
# (and for the whole run under E2E_NOW). The mapping file keeps `{{now}}` as a
# fallback; this rewrites the loaded stub with the anchor's day, in memory, each
# time the world is reseeded.
#
# Usage: COMPOSE="docker compose -f … -p …" e2e/wiremock-today.sh
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MANIFEST="$REPO_ROOT/api/var/e2e/seed-manifest.json"
: "${COMPOSE:?COMPOSE is the docker compose command line of the e2e stack}"

today="$(jq -r '.anchor // empty' "$MANIFEST" 2>/dev/null | cut -c1-10)"
[ -n "$today" ] || { echo "wiremock-today: no anchor in $MANIFEST: seed first" >&2; exit 1; }
tomorrow="$(TZ=UTC date -d "$today +1 day" +%F)"

admin() { $COMPOSE exec -T wiremock curl -fsS "$@"; }

stub="$(admin http://localhost:8080/__admin/mappings \
  | jq -c --arg today "$today" --arg tomorrow "$tomorrow" '
      .mappings[]
      | select(.response.jsonBody.items // [] | any(.id == "e2e-google-event-today"))
      | .response.jsonBody.items |= map(
          if .id == "e2e-google-event-today"
          then .start.date = $today | .end.date = $tomorrow
          else . end)')"

[ -n "$stub" ] || { echo "wiremock-today: the Google events stub is not loaded" >&2; exit 1; }

id="$(jq -r '.id' <<<"$stub")"
jq -c . <<<"$stub" | admin -X PUT "http://localhost:8080/__admin/mappings/$id" \
  -H 'Content-Type: application/json' --data-binary @- >/dev/null

echo "wiremock-today: « Escapade importée de Google » is on $today"
