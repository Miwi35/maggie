#!/usr/bin/env bash
#
# The smoke journey for the e2e stack (MAG-94).
#
# This ticket builds the harness, so its journey is the one that proves the
# harness works: the stack answers, the test login hands out a usable JWT, the
# seeded data is there with the dates the anchor implies, MCP still lists every
# module's tools, search reads a rebuilt index, and the bank sync reaches
# WireMock rather than the internet.
#
# Written in shell rather than Playwright on purpose: MAG-97 owns the browser
# harness. Every step here is a contract that harness will rely on, so it is
# worth having under CI before the browser arrives — and it stays afterwards as
# the check that the *stack* is sound when a UI journey fails.
#
# Usage:  task e2e:smoke
#         E2E_BASE_URL=http://127.0.0.1:32768 e2e/smoke/smoke.sh

set -euo pipefail

BASE_URL="${E2E_BASE_URL:?E2E_BASE_URL is required — run through 'task e2e:smoke'}"
LOGIN_TOKEN="${E2E_LOGIN_TOKEN:-e2e-login-token}"
SEED_EMAIL="${E2E_SEED_EMAIL:-e2e@maggie.local}"
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
COMPOSE=(docker compose -f "$REPO_ROOT/docker-compose.e2e.yml")
if [ -n "${COMPOSE_PROJECT_NAME:-}" ]; then
  COMPOSE+=(-p "$COMPOSE_PROJECT_NAME")
fi

passed=0
failed=0

pass() { printf '  \033[32m✓\033[0m %s\n' "$1"; passed=$((passed + 1)); }
fail() { printf '  \033[31m✗\033[0m %s\n' "$1"; failed=$((failed + 1)); }
step() { printf '\n\033[1m%s\033[0m\n' "$1"; }

# assert_eq <expected> <actual> <what>
assert_eq() {
  if [ "$1" = "$2" ]; then
    pass "$3"
  else
    fail "$3 — expected '$1', got '$2'"
  fi
}

assert_contains() {
  if printf '%s' "$1" | grep -qF -- "$2"; then
    pass "$3"
  else
    fail "$3 — '$2' not found in the response"
  fi
}

status_of() { curl -sS -o /dev/null -w '%{http_code}' "$@"; }

# ---------------------------------------------------------------------------
step "1. The stack answers through Traefik"
# ---------------------------------------------------------------------------
assert_eq 200 "$(status_of -H 'Accept: application/vnd.openapi+json' "$BASE_URL/api/docs")" \
  "GET /api/docs is served"
assert_eq 200 "$(status_of "$BASE_URL/agent/health")" \
  "GET /agent/health is served"
assert_eq 401 "$(status_of -H 'Accept: application/ld+json' "$BASE_URL/api/events")" \
  "an unauthenticated API call is refused"

# ---------------------------------------------------------------------------
step "2. The test login is closed without its token"
# ---------------------------------------------------------------------------
assert_eq 401 "$(status_of -X POST -H 'Content-Type: application/json' \
  -d "{\"email\":\"$SEED_EMAIL\"}" "$BASE_URL/api/auth/e2e/login")" \
  "POST /api/auth/e2e/login with no token is refused"
assert_eq 401 "$(status_of -X POST -H 'Content-Type: application/json' \
  -H 'X-E2E-Token: wrong-token' \
  -d "{\"email\":\"$SEED_EMAIL\"}" "$BASE_URL/api/auth/e2e/login")" \
  "POST /api/auth/e2e/login with a wrong token is refused"
assert_eq 400 "$(status_of -X POST -H 'Content-Type: application/json' \
  -H "X-E2E-Token: $LOGIN_TOKEN" -d '{}' "$BASE_URL/api/auth/e2e/login")" \
  "POST /api/auth/e2e/login with no email is rejected"

# ---------------------------------------------------------------------------
step "3. The test login hands out a usable token set"
# ---------------------------------------------------------------------------
login_response="$(curl -sS -X POST -H 'Content-Type: application/json' \
  -H "X-E2E-Token: $LOGIN_TOKEN" \
  -d "{\"email\":\"$SEED_EMAIL\"}" "$BASE_URL/api/auth/e2e/login")"

JWT="$(printf '%s' "$login_response" | jq -r '.token // empty')"
if [ -z "$JWT" ]; then
  fail "the login returned no JWT — response: $login_response"
  printf '\nAborting: nothing below can run without a token.\n'
  exit 1
fi
pass "the login returns a JWT"

assert_contains "$login_response" '"refreshToken"' "the login returns a refresh token"
assert_contains "$login_response" '"mercureToken"' "the login returns a Mercure subscriber token"
assert_eq "$SEED_EMAIL" "$(printf '%s' "$login_response" | jq -r '.user.email')" \
  "the login identifies the seeded user"

AUTH=(-H "Authorization: Bearer $JWT")

assert_eq 404 "$(status_of -X POST -H 'Content-Type: application/json' \
  -H "X-E2E-Token: $LOGIN_TOKEN" \
  -d '{"email":"nobody@maggie.local"}' "$BASE_URL/api/auth/e2e/login")" \
  "logging in as a user the seed never made is refused"

# ---------------------------------------------------------------------------
step "4. The seeded data is there, with the dates the anchor implies"
# ---------------------------------------------------------------------------
events="$(curl -sS "${AUTH[@]}" -H 'Accept: application/ld+json' "$BASE_URL/api/events?itemsPerPage=100")"
assert_contains "$events" 'Déjeuner avec Alex' "the seeded event of today is listed"
assert_contains "$events" 'Cours de piano' "the seeded recurring event is listed"

agendas="$(curl -sS "${AUTH[@]}" -H 'Accept: application/ld+json' "$BASE_URL/api/agendas")"
assert_eq 2 "$(printf '%s' "$agendas" | jq -r '.totalItems // (.member | length)')" \
  "both seeded agendas belong to the test user"

# The anchor the seed actually used, not today's date. They are the same in CI
# and in a default local run, but a seed and a smoke run straddling midnight
# UTC would drift, and anyone passing --now deliberately would break this step
# for no reason. The seed records the anchor for exactly this.
manifest="$REPO_ROOT/api/var/e2e/seed-manifest.json"
if [ -f "$manifest" ]; then
  anchor_date="$(jq -r '.anchor' "$manifest" | cut -c1-10)"
else
  fail "no seed manifest at $manifest — run 'task e2e:seed' first"
  anchor_date=""
fi

lunch_date="$(printf '%s' "$events" | jq -r '[.member[] | select(.summary == "Déjeuner avec Alex")][0].startAt // empty' | cut -c1-10)"
assert_eq "$anchor_date" "$lunch_date" \
  "seeded dates follow the seed's anchor, not the wall clock"

accounts="$(curl -sS "${AUTH[@]}" -H 'Accept: application/ld+json' "$BASE_URL/api/accounts")"
assert_contains "$accounts" 'Compte courant' "the seeded finance accounts are listed"

recipes="$(curl -sS "${AUTH[@]}" -H 'Accept: application/ld+json' "$BASE_URL/api/recipes")"
assert_contains "$recipes" 'Pâtes à la tomate' "the seeded recipes are listed"

# ---------------------------------------------------------------------------
step "5. MCP still exposes one tool per module"
# ---------------------------------------------------------------------------
# A module missing from discovery.scan_dirs in api/config/packages/mcp.yaml has
# its tools silently hidden — no error, just an agent that cannot do the thing.
# Checking the list here is the cheapest place to catch it.
#
# The HTTP transport is stateful: tools/list without an initialize handshake
# answers "a valid session id is REQUIRED", which looks exactly like an empty
# tool list if you do not read the error.
mcp_headers=(-H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream')

curl -sS -D /tmp/e2e-mcp-headers -o /dev/null -X POST "${AUTH[@]}" "${mcp_headers[@]}" \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"e2e-smoke","version":"1.0.0"}}}' \
  "$BASE_URL/_mcp"

mcp_session="$(grep -i '^Mcp-Session-Id:' /tmp/e2e-mcp-headers | tr -d '\r' | cut -d' ' -f2)"
if [ -n "$mcp_session" ]; then
  pass "the MCP handshake returns a session id"
else
  fail "the MCP handshake returned no session id"
fi
mcp_headers+=(-H "Mcp-Session-Id: $mcp_session")

curl -sS -o /dev/null -X POST "${AUTH[@]}" "${mcp_headers[@]}" \
  -d '{"jsonrpc":"2.0","method":"notifications/initialized"}' "$BASE_URL/_mcp"

tools="$(curl -sS -X POST "${AUTH[@]}" "${mcp_headers[@]}" \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/list"}' "$BASE_URL/_mcp" | sed 's/^data: //')"

tool_count="$(printf '%s' "$tools" | jq -r '[.result.tools[]?.name] | length' 2>/dev/null || echo 0)"
if [ "$tool_count" -gt 0 ]; then
  pass "tools/list returns $tool_count tools"
else
  fail "tools/list returned no tool — response: $(printf '%s' "$tools" | head -c 400)"
fi

for keyword in agenda event recipe grocery account notification; do
  if printf '%s' "$tools" | jq -e --arg k "$keyword" '[.result.tools[]?.name | select(test($k; "i"))] | length > 0' >/dev/null 2>&1; then
    pass "a tool mentioning '$keyword' is exposed"
  else
    fail "no tool mentions '$keyword' — is its module in discovery.scan_dirs?"
  fi
done

# ---------------------------------------------------------------------------
step "6. Search reads an index the seed rebuilt"
# ---------------------------------------------------------------------------
# A stale index returns an empty list in silence, so this is the only way to
# tell "nothing matches" from "the seed forgot to reindex".
search="$(curl -sS "${AUTH[@]}" -H 'Accept: application/json' "$BASE_URL/api/search?q=tomate")"
if printf '%s' "$search" | grep -qiF 'tomate'; then
  pass "searching for a seeded term returns a hit"
else
  fail "search returned nothing for a seeded term — response: $(printf '%s' "$search" | head -c 300)"
fi

# ---------------------------------------------------------------------------
step "7. The bank sync reaches WireMock, not the internet"
# ---------------------------------------------------------------------------
"${COMPOSE[@]}" exec -T wiremock sh -c 'curl -sS -X POST http://localhost:8080/__admin/requests/reset' >/dev/null

sync_output="$("${COMPOSE[@]}" exec -T php bin/console --env=e2e app:finance:sync "$SEED_EMAIL" --write 2>&1 || true)"

requests="$("${COMPOSE[@]}" exec -T wiremock sh -c 'curl -sS http://localhost:8080/__admin/requests')"
bank_calls="$(printf '%s' "$requests" | jq -r '[.requests[]? | select(.request.url | startswith("/enablebanking"))] | length')"

if [ "${bank_calls:-0}" -gt 0 ]; then
  pass "the sync made $bank_calls calls, all of them to WireMock"
else
  fail "the sync made no call to WireMock — output: $(printf '%s' "$sync_output" | tail -c 400)"
fi

unmatched="$("${COMPOSE[@]}" exec -T wiremock sh -c 'curl -sS http://localhost:8080/__admin/requests/unmatched' | jq -r '.requests | length')"
assert_eq 0 "$unmatched" "every request the sync made had a stub"

# Indexing runs through RabbitMQ and the worker, and the transaction collection
# is served from Elasticsearch — so the row exists before it is findable. Every
# journey asserting on a freshly written indexed entity has to wait like this;
# reading once is how a passing feature gets reported as broken.
imported=""
for _ in $(seq 1 30); do
  transactions="$(curl -sS "${AUTH[@]}" -H 'Accept: application/ld+json' "$BASE_URL/api/transactions?itemsPerPage=100")"
  if printf '%s' "$transactions" | grep -qF 'LECLERC RENNES CB'; then
    imported=yes
    break
  fi
  sleep 1
done

if [ -n "$imported" ]; then
  pass "a transaction from the stubbed bank landed in the database and got indexed"
else
  fail "the stubbed bank's transaction never appeared — sync said: $(printf '%s' "$sync_output" | tail -c 300)"
fi

# ---------------------------------------------------------------------------
step "8. The Google redirect holds end to end"
# ---------------------------------------------------------------------------
# Not just the unit test on ->rootUrl: drive a real Google Tasks pull and check
# it landed on WireMock. If a client ever stopped honouring its base URL, an
# e2e run would otherwise reach the real API and only fail on someone's quota.
"${COMPOSE[@]}" exec -T wiremock sh -c 'curl -sS -X POST http://localhost:8080/__admin/requests/reset' >/dev/null

google_output="$("${COMPOSE[@]}" exec -T php \
  bin/console --env=e2e maggie:google-calendar:sync --tasks 2>&1 || true)"

google_requests="$("${COMPOSE[@]}" exec -T wiremock sh -c 'curl -sS http://localhost:8080/__admin/requests')"
google_calls="$(printf '%s' "$google_requests" | jq -r '[.requests[]? | select(.request.url | startswith("/google"))] | length')"

if [ "${google_calls:-0}" -gt 0 ]; then
  pass "the Google client reached WireMock ($google_calls calls), not googleapis.com"
else
  fail "no Google call reached WireMock — GOOGLE_API_BASE_URL is not honoured. Sync said: $(printf '%s' "$google_output" | tail -c 300)"
fi

google_unmatched="$("${COMPOSE[@]}" exec -T wiremock sh -c 'curl -sS http://localhost:8080/__admin/requests/unmatched' | jq -r '.requests | length')"
assert_eq 0 "$google_unmatched" "every Google request the sync made had a stub"

# ---------------------------------------------------------------------------
printf '\n\033[1mSmoke journey: %d passed, %d failed\033[0m\n' "$passed" "$failed"

if [ "$failed" -gt 0 ]; then
  printf '\nRun `task e2e:logs` to see what the stack was doing.\n'
  exit 1
fi
