#!/usr/bin/env bash
#
# Post-deploy smoke suite for production (MAG-106).
#
# Read-only: it GETs, lists MCP tools, and asks Maggie one question as the
# technical account. The only thing it writes is that account's own chat
# history — the owner's data is never touched. A failure makes the CD workflow
# roll the deployment back, so every check here must be something a healthy
# production does every time: no timing luck, no dependence on real data.
#
# Each check exists because a past regression was only visible in production
# (analysis MAG-93): config that differs from CI, images that differ from dev.
#
# Usage:
#   SMOKE_TOKEN=<jwt> infra/scripts/smoke-prod.sh
#
# Environment:
#   SMOKE_TOKEN     JWT of the technical account (`app:smoke:token`). Required.
#   BASE_URL        Public origin. Default https://maggieai.fr
#   ES_CHECK_CMD    Command whose exit code is the Elasticsearch check (the
#                   workflow runs `app:elasticsearch:status --check` in the php
#                   pod). Skipped when unset.
#   SMOKE_ATTEMPTS  Tries for the availability checks, to ride out the
#                   seconds an ingress needs after a rollout. Default 12.
#   SMOKE_DELAY     Seconds between two tries. Default 5.

set -uo pipefail

BASE_URL="${BASE_URL:-https://maggieai.fr}"
BASE_URL="${BASE_URL%/}"
TOKEN="${SMOKE_TOKEN:?SMOKE_TOKEN is required — mint it with 'bin/console app:smoke:token' in the php pod}"
SMOKE_EMAIL="${SMOKE_EMAIL:-smoke@maggieai.fr}"
ATTEMPTS="${SMOKE_ATTEMPTS:-12}"
DELAY="${SMOKE_DELAY:-5}"
ORIGIN="$BASE_URL"

# Asked to call a tool by name: the answer is then a test of the tool loop
# (MCP list loaded at agent startup, tool round-trip to the API), not of the
# model's mood. `get_upcoming_events` takes no argument and only reads.
QUESTION="Appelle l'outil get_upcoming_events, puis réponds en une phrase : combien d'événements vois-tu ?"

passed=0
failed=0
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

pass() { printf '  \033[32m✓\033[0m %s\n' "$1"; passed=$((passed + 1)); }
fail() { printf '  \033[31m✗\033[0m %s\n' "$1"; failed=$((failed + 1)); }
step() { printf '\n\033[1m%s\033[0m\n' "$1"; }

# The token must never reach the log: nothing below echoes a request, and
# responses are cut to a short excerpt.
excerpt() { printf '%s' "$1" | head -c 300 | tr '\n' ' '; }

status_of() { curl -sS -o /dev/null --max-time 15 -w '%{http_code}' "$@" 2>/dev/null || echo 000; }

# expect_status <what> <expected> <curl args…>, retried while the status differs.
expect_status() {
  local what="$1" expected="$2" got="" attempt
  shift 2
  for ((attempt = 1; attempt <= ATTEMPTS; attempt++)); do
    got="$(status_of "$@")"
    if [ "$got" = "$expected" ]; then
      pass "$what"
      return 0
    fi
    [ "$attempt" -lt "$ATTEMPTS" ] && sleep "$DELAY"
  done
  fail "$what — expected HTTP $expected, got $got"
  return 1
}

AUTH=(-H "Authorization: Bearer $TOKEN")

# ---------------------------------------------------------------------------
step "1. API, agent and admin answer on the public URL"
# ---------------------------------------------------------------------------
expect_status "GET /api/docs is served" 200 -H 'Accept: application/vnd.openapi+json' "$BASE_URL/api/docs"

expect_status "GET /agent/health is served" 200 "$BASE_URL/agent/health"
agent_health="$(curl -sS --max-time 15 "$BASE_URL/agent/health" 2>/dev/null || true)"
if printf '%s' "$agent_health" | jq -e '.status == "ok"' >/dev/null 2>&1; then
  pass "the agent reports status ok"
else
  fail "the agent health body is not status ok — got: $(excerpt "$agent_health")"
fi

# The admin SPA has cost five fixes for one outage: served under /admin without
# a trailing slash, a wrong basename, a redirect to http://. One request on each
# public entry point closes that family.
expect_status "GET /admin is served without a redirect" 200 "$BASE_URL/admin"
expect_status "GET /admin/ is served" 200 "$BASE_URL/admin/"
admin_type="$(curl -sS -o /dev/null --max-time 15 -w '%{content_type}' "$BASE_URL/admin" 2>/dev/null || true)"
case "$admin_type" in
  text/html*) pass "/admin answers with the SPA's HTML" ;;
  *) fail "/admin answers with '$admin_type', not HTML" ;;
esac

# The raw Location header: curl's %{redirect_url} resolves a relative one against
# the request, which would hide exactly what is being checked.
root_location="$(curl -sS -D - -o /dev/null --max-time 15 "$BASE_URL/" 2>/dev/null | tr -d '\r' | grep -i '^location:' | head -1 | cut -d' ' -f2-)"
case "$root_location" in
  http://*) fail "/ redirects to a plain http:// URL ($root_location) — TLS is terminated upstream, redirects must stay relative" ;;
  *) pass "/ does not downgrade to http://" ;;
esac

# ---------------------------------------------------------------------------
step "2. Mercure is reachable from the origin the browser uses"
# ---------------------------------------------------------------------------
# The hub's CORS origin was once frozen when the admin bundle was compiled, so
# a healthy hub refused the only page that talks to it. A preflight from the
# public origin is the request a browser makes first.
mercure_url="$BASE_URL/.well-known/mercure"
preflight=""
for ((attempt = 1; attempt <= ATTEMPTS; attempt++)); do
  preflight="$(curl -sS -D - -o /dev/null --max-time 15 -X OPTIONS \
    -H "Origin: $ORIGIN" -H 'Access-Control-Request-Method: GET' \
    "$mercure_url?match=smoke" 2>/dev/null | tr -d '\r' || true)"
  printf '%s' "$preflight" | grep -qiE '^HTTP/[0-9.]+ (2|3)[0-9][0-9]' && break
  [ "$attempt" -lt "$ATTEMPTS" ] && sleep "$DELAY"
done
allowed="$(printf '%s' "$preflight" | grep -i '^access-control-allow-origin:' | head -1 | cut -d' ' -f2-)"
if [ "$allowed" = "$ORIGIN" ] || [ "$allowed" = "*" ]; then
  pass "the hub answers a preflight from $ORIGIN and allows it"
else
  fail "the hub does not allow $ORIGIN — Access-Control-Allow-Origin: '${allowed:-absent}'; response: $(excerpt "$preflight")"
fi

# ---------------------------------------------------------------------------
step "3. The technical account is accepted by the API"
# ---------------------------------------------------------------------------
# Proves the JWT keys are mounted, signature and claims agree between pods, and
# the database answers — the only database read the public URL gives us.
me="$(curl -sS --max-time 15 "${AUTH[@]}" -H 'Accept: application/ld+json' "$BASE_URL/api/users/me" 2>/dev/null || true)"
if [ "$(printf '%s' "$me" | jq -r '.email // empty' 2>/dev/null)" = "$SMOKE_EMAIL" ]; then
  pass "GET /api/users/me returns the technical account"
else
  fail "GET /api/users/me did not return $SMOKE_EMAIL — got: $(excerpt "$me")"
fi

# ---------------------------------------------------------------------------
step "4. MCP lists the tools of every module"
# ---------------------------------------------------------------------------
# A production image without the DataFixtures made tool discovery crash once:
# zero tools, no error a health check would see. The HTTP transport is
# stateful — tools/list before initialize reads like an empty list.
mcp_headers=(-H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream')

curl -sS -D "$tmp/mcp-headers" -o /dev/null --max-time 20 -X POST "${AUTH[@]}" "${mcp_headers[@]}" \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"prod-smoke","version":"1.0.0"}}}' \
  "$BASE_URL/_mcp" 2>/dev/null || true

mcp_session="$(grep -i '^Mcp-Session-Id:' "$tmp/mcp-headers" 2>/dev/null | tr -d '\r' | cut -d' ' -f2)"
if [ -n "$mcp_session" ]; then
  pass "the MCP handshake returns a session id"
  mcp_headers+=(-H "Mcp-Session-Id: $mcp_session")

  curl -sS -o /dev/null --max-time 20 -X POST "${AUTH[@]}" "${mcp_headers[@]}" \
    -d '{"jsonrpc":"2.0","method":"notifications/initialized"}' "$BASE_URL/_mcp" 2>/dev/null || true

  tools="$(curl -sS --max-time 20 -X POST "${AUTH[@]}" "${mcp_headers[@]}" \
    -d '{"jsonrpc":"2.0","id":2,"method":"tools/list"}' "$BASE_URL/_mcp" 2>/dev/null)"
  # The streamable transport may answer as server-sent events: keep the JSON
  # of the last `data:` line, whatever `event:` or `id:` lines surround it.
  if printf '%s' "$tools" | grep -q '^data:'; then
    tools="$(printf '%s' "$tools" | grep '^data:' | tail -n 1 | sed 's/^data: *//')"
  fi
  tool_count="$(printf '%s' "$tools" | jq -r '[.result.tools[]?.name] | length' 2>/dev/null || echo 0)"
  if [ "${tool_count:-0}" -gt 0 ]; then
    pass "tools/list returns $tool_count tools"
  else
    fail "tools/list returned no tool — response: $(excerpt "$tools")"
  fi

  for keyword in agenda event recipe grocery account notification; do
    if printf '%s' "$tools" | jq -e --arg k "$keyword" '[.result.tools[]?.name | select(test($k; "i"))] | length > 0' >/dev/null 2>&1; then
      pass "a tool mentioning '$keyword' is exposed"
    else
      fail "no tool mentions '$keyword' — is its module in discovery.scan_dirs?"
    fi
  done
else
  fail "the MCP handshake returned no session id — headers: $(excerpt "$(cat "$tmp/mcp-headers" 2>/dev/null)")"
fi

# ---------------------------------------------------------------------------
step "5. Elasticsearch is reachable and in sync with the database"
# ---------------------------------------------------------------------------
# Indexed collections are served from Elasticsearch and fall back to the
# database only on an exception: a stale or unreachable index answers an empty
# list without a word. `--check` exits non-zero on a missing index or a drift.
if [ -n "${ES_CHECK_CMD:-}" ]; then
  # Indexing goes through the worker, so a document dispatched a moment ago
  # (the technical account's own) may not be counted yet: retried like the rest.
  es_ok=0
  for ((attempt = 1; attempt <= ATTEMPTS; attempt++)); do
    if es_output="$(bash -c "$ES_CHECK_CMD" 2>&1)"; then
      es_ok=1
      break
    fi
    [ "$attempt" -lt "$ATTEMPTS" ] && sleep "$DELAY"
  done
  if [ "$es_ok" -eq 1 ]; then
    pass "app:elasticsearch:status --check is green"
  else
    # The verdict is at the end of the output, after the cluster table.
    fail "app:elasticsearch:status --check failed — $(printf '%s' "$es_output" | tail -c 300 | tr '\n' ' ')"
  fi
else
  printf '  - skipped: ES_CHECK_CMD is not set\n'
fi

# ---------------------------------------------------------------------------
step "6. Maggie answers a simple question through a tool"
# ---------------------------------------------------------------------------
# Covers what no health check can: the agent loaded its tool list at startup
# (a race once left it empty), and every uvicorn worker serves the same
# configuration. The question goes through the public URL, like a client's.
# Two tries: the model may answer without the tool once, and a rollback of
# production is too costly to hang on a single sample of it.
asked_ok=""
chat_output=""
for try in 1 2; do
  chat_output="$(curl -sS --max-time 120 -X POST "${AUTH[@]}" -H 'Content-Type: application/json' \
    -d "$(jq -nc --arg m "$QUESTION" '{message: $m}')" "$BASE_URL/agent/chat" 2>/dev/null || true)"

  answer="$(printf '%s' "$chat_output" | jq -r '.response // empty' 2>/dev/null)"
  tool_name="$(printf '%s' "$chat_output" | jq -r '.tool_calls[0].name // empty' 2>/dev/null)"

  if [ -n "$answer" ] && [ -n "$tool_name" ]; then
    asked_ok=yes
    break
  fi
  [ "$try" -lt 2 ] && sleep "$DELAY"
done

if [ -z "$asked_ok" ]; then
  fail "Maggie did not answer through a tool — response: $(excerpt "$chat_output")"
else
  pass "Maggie answered and called '$tool_name'"

  if printf '%s' "$chat_output" | jq -e '.tool_calls[0].result | fromjson | if type == "object" then has("error") else false end' >/dev/null 2>&1; then
    fail "the tool call errored — result: $(excerpt "$(printf '%s' "$chat_output" | jq -r '.tool_calls[0].result')")"
  else
    pass "the tool reached the API and did not error"
  fi

  case "$answer" in
    *"erreur est survenue"* | *"pas configuré"*) fail "the answer is an apology, not an answer — $(excerpt "$answer")" ;;
    *) pass "the answer is a real sentence" ;;
  esac
fi

# ---------------------------------------------------------------------------
printf '\n\033[1mProduction smoke: %d passed, %d failed\033[0m\n' "$passed" "$failed"

[ "$failed" -eq 0 ]
