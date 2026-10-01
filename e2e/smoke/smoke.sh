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
# Step 9 came with MAG-95: Maggie answers from the scripted model, so the agent
# is part of the harness rather than the reason a journey is flaky.
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

# Two, out of three in the database: the neighbour owns the third (MAG-114), so this
# is where a read that forgot its user filter shows up before any journey runs.
agendas="$(curl -sS "${AUTH[@]}" -H 'Accept: application/ld+json' "$BASE_URL/api/agendas")"
assert_eq 2 "$(printf '%s' "$agendas" | jq -r '.totalItems // (.member | length)')" \
  "the owner sees their two agendas and not the neighbour's"
if printf '%s' "$events" | grep -qF 'Déjeuner du voisin'; then
  fail "the neighbour's lunch is in the owner's events — a read lost its user filter"
else
  pass "nothing of the neighbour's is in the owner's events"
fi

# The anchor the seed actually used, not today's date. They are the same in CI
# and in a default local run, but a seed and a smoke run straddling midnight
# in Paris would drift, and anyone passing --now deliberately would break this step
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
for _ in $(seq 1 60); do
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
step "9. Maggie answers from the scripted model, not from Claude"
# ---------------------------------------------------------------------------
# LLM_PROVIDER=fake replaces the model and nothing else: the tool loop, the
# AG-UI streaming gateway and the MCP client are the production ones (MAG-95).
# So these steps exercise the whole chain — the only thing they do not exercise
# is Claude's judgement, which the eval suite covers on the real model.
#
# The first assertion is the one that proves the switch is in effect at all: an
# unscripted question answers with a sentence naming its own cause, which the
# real model would never produce.
AGENDA_QUESTION="Qu'est-ce que j'ai de prévu aujourd'hui ?"

chat_body() { jq -nc --arg m "$1" '{message: $m}'; }

chat() {
  curl -sS -X POST "${AUTH[@]}" -H 'Content-Type: application/json' \
    -d "$(chat_body "$1")" "$BASE_URL/agent/chat"
}

unscripted="$(chat "une question que personne n'a scriptée")"
assert_contains "$unscripted" '[fake-llm] aucun sc' \
  "an unscripted message says so instead of improvising"

agenda="$(chat "$AGENDA_QUESTION")"
assert_contains "$agenda" 'déjeuner avec Alex' "the scripted answer comes back"
assert_eq get_upcoming_events "$(printf '%s' "$agenda" | jq -r '.tool_calls[0].name // empty')" \
  "the scripted tool really ran"
if printf '%s' "$agenda" | jq -e '.tool_calls[0].result | fromjson | if type == "object" then has("error") else false end' >/dev/null 2>&1; then
  fail "the tool ran but errored — result: $(printf '%s' "$agenda" | jq -r '.tool_calls[0].result' | head -c 300)"
else
  pass "the tool returned the seeded agenda, not an error"
fi
# The recurring event, and not the seeded lunch: the lunch sits at the anchor
# plus twelve hours, so `get_upcoming_events` — which counts from *now* — drops
# it once the afternoon starts, and the step would pass in the morning and fail
# in the evening. Accent-free too, because the tool result is PHP's json_encode
# and arrives escaped as \uXXXX.
assert_contains "$agenda" 'Cours de piano' "the tool result carries the seeded agenda"

# A write, asserted through MCP rather than through Maggie's wording: basil is
# a seeded ingredient that the seed deliberately leaves off the grocery list.
basil="$(chat "Ajoute du basilic à ma liste de courses")"
assert_eq add_grocery_item "$(printf '%s' "$basil" | jq -r '.tool_calls[0].name // empty')" \
  "asking for an item calls the write tool"

grocery="$(curl -sS -X POST "${AUTH[@]}" "${mcp_headers[@]}" \
  -d '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"get_grocery_list","arguments":{}}}' \
  "$BASE_URL/_mcp" | sed 's/^data: //')"
assert_contains "$grocery" 'Basilic' "the item Maggie added is on the list"

# The streamed path: same fake, same loop, but the AG-UI events a client reads.
#
# Contexts only exist here, and everything below needs the conversation to start
# from nothing — which `task e2e:seed` guarantees, since it now empties the
# agent's own database too. Asserted rather than assumed: without it, a second
# smoke run on a stack nobody reseeded fails on "created" with no hint as to why.
assert_eq 0 "$(curl -sS "${AUTH[@]}" "$BASE_URL/agent/contexts" | jq -r 'length')" \
  "the seed left no conversation context behind — run 'task e2e:seed' if this fails"

stream="$(curl -sS -N -X POST "${AUTH[@]}" -H 'Content-Type: application/json' \
  -H 'Accept: text/event-stream' \
  -d "$(chat_body "$AGENDA_QUESTION")" "$BASE_URL/agent/chat/stream")"

for event in RUN_STARTED TOOL_CALL_START TOOL_CALL_END TEXT_MESSAGE_START TEXT_MESSAGE_CONTENT RUN_FINISHED; do
  assert_contains "$stream" "$event" "the stream emits $event"
done

# More than one content event: a single delta would mean the gateway buffered
# the whole answer, which is the bug streaming exists to avoid.
deltas="$(printf '%s' "$stream" | grep -c 'TEXT_MESSAGE_CONTENT' || true)"
if [ "${deltas:-0}" -gt 1 ]; then
  pass "the answer arrives as $deltas deltas, token by token"
else
  fail "the answer arrived in $deltas event — the stream is not streaming"
fi

# Parsed rather than grepped: the events are `json.dumps` output, so the
# separators carry spaces and a compact needle never matches.
context_action() { printf '%s' "$1" | sed -n 's/^data: //p' | jq -r 'select(.name == "context_update") | .value.action'; }

assert_eq created "$(context_action "$stream")" \
  "the context router opened a context"
assert_contains "$stream" 'Conversation e2e' \
  "the context it opened carries the scripted label"

# A second streamed message has to land in that same context. This is the one
# step that checks the router's answer *fits* — it hands back an id it read from
# its own prompt, and a pattern matching the wrong id format would still make
# every message look fine while opening a context per message.
contexts_before="$(curl -sS "${AUTH[@]}" "$BASE_URL/agent/contexts" | jq -r 'length')"

followup="$(curl -sS -N -X POST "${AUTH[@]}" -H 'Content-Type: application/json' \
  -H 'Accept: text/event-stream' \
  -d "$(chat_body "Et mes rendez-vous de la semaine prochaine ?")" \
  "$BASE_URL/agent/chat/stream")"
assert_eq matched "$(context_action "$followup")" \
  "the next message joins the context already open"

assert_eq "$contexts_before" "$(curl -sS "${AUTH[@]}" "$BASE_URL/agent/contexts" | jq -r 'length')" \
  "the follow-up opened no second context for the same subject"

# The voice path, the one place two stand-ins have to agree: WireMock dictates a
# fixed sentence, and a scenario returns that sentence cleaned. Each is plausible
# on its own, so only driving both at once catches one being edited without the
# other.
#
# Selected on the stub's URL rather than by index, so a second OpenAI stub cannot
# silently retarget this.
dictated="$(jq -r '.mappings[] | select(.request.urlPathPattern | test("transcriptions")) | .response.body' \
  "$REPO_ROOT/.docker/e2e/wiremock/mappings/openai.json")"

# Any non-empty bytes: the route refuses an empty upload, and WireMock answers
# its fixed sentence whatever it receives. Trapped, because `set -e` would skip
# the cleanup if the request failed.
dictation="$(mktemp --suffix=.webm)"
trap 'rm -f "$dictation"' EXIT
printf 'not really webm, and WireMock does not care' >"$dictation"
transcript="$(curl -sS -X POST "${AUTH[@]}" -F "audio=@$dictation;type=audio/webm" \
  "$BASE_URL/agent/transcribe")"

assert_eq "$dictated" "$(printf '%s' "$transcript" | jq -r '.raw // empty')" \
  "Whisper answered from WireMock, not from OpenAI"

cleaned="$(printf '%s' "$transcript" | jq -r '.clean // empty')"

# The assertion that actually binds the two: comparing `.raw` to the mapping is a
# tautology — the stub *is* the mapping — and "the answer changed" only proves
# something happened. The longest word of what was dictated has to survive into
# what the model handed back, and the `[fake-llm]` check is what stops the
# no-scenario sentence from passing: it quotes the dictated text back, so every
# word of it would be found there too.
keyword="$(printf '%s' "$dictated" | tr ' ' '\n' | awk '{ print length, $0 }' | sort -rn | head -1 | cut -d' ' -f2-)"
if printf '%s' "$cleaned" | grep -qF -- '[fake-llm]'; then
  fail "no scenario cleaned the dictation — is 20-transcription-cleanup.yaml still matching? Got: $cleaned"
else
  assert_contains "$cleaned" "$keyword" "the cleanup kept what Whisper dictated ('$keyword')"
  assert_eq false "$([ "$cleaned" = "$dictated" ] && echo true || echo false)" \
    "the cleanup returned something other than the raw transcript"
fi

# And the cleaned sentence is itself scripted, so dictating ends on a real write
# rather than on "[fake-llm] aucun scénario".
dictated_answer="$(chat "$cleaned")"
assert_eq add_grocery_item "$(printf '%s' "$dictated_answer" | jq -r '.tool_calls[0].name // empty')" \
  "what she heard reaches the grocery list"

# The other half of the voice path, and the only half with no screen behind it:
# the admin does not speak, so `TTS_PROVIDER=fake` is checked here rather than
# in a browser journey. Mobile is what reads it back — four of MAG-93's
# regressions are that overlay revocalising an old answer — and the flows that
# cover those wait on MAG-98.
#
# `TTS_PROVIDER=fake` streams a valid silent MP3 frame instead of opening a
# WebSocket to Microsoft, so this asserts that audio arrives and that the
# provider is the stand-in: real Edge TTS for one sentence is several kilobytes
# and several hundred milliseconds, neither of which belongs in CI.
voices="$(curl -sS "${AUTH[@]}" "$BASE_URL/agent/tts/voices")"
assert_contains "$voices" 'fr-FR-DeniseNeural' "the curated voice list comes back"

# Its own text, 150 characters of one letter, rather than the dictated sentence:
# the fake emits one 104-byte frame per 50 characters of prepared text, so the
# byte count below is exact and says what it means. Borrowing the dictation
# fixture would tie this assertion to a sentence another journey is free to
# reword, and shortening that sentence would fail here for no reason.
spoken="$(mktemp)"
trap 'rm -f "$dictation" "$spoken"' EXIT
tts_text="$(printf 'a%.0s' $(seq 1 150))"
tts_status="$(curl -sS -o "$spoken" -w '%{http_code}' -X POST "${AUTH[@]}" \
  -H 'Content-Type: application/json' \
  -d "$(jq -nc --arg t "$tts_text" '{text: $t}')" "$BASE_URL/agent/tts/synthesize")"
assert_eq 200 "$tts_status" "synthesising a sentence answers"

# An MP3 frame starts with the 11 sync bits — 0xFF then 0xFB here. Checked on
# the bytes rather than on the Content-Type, which a proxy sets over an empty
# body just as happily, and `od` rather than `xxd`, which is not everywhere.
assert_eq fffb "$(head -c 2 "$spoken" | od -An -tx1 | tr -d ' \n')" \
  "what came back is audio, not an error page"

# Three whole frames, 104 bytes each: the stand-in answered, and its chunking
# loop ran the number of times the text implies. It says nothing about the
# response being streamed rather than buffered — `curl -o` writes the same
# bytes either way — only that the whole body arrived and came from the fake
# provider and not from Edge TTS, whose output for this text would be neither
# silent nor a round multiple of a frame.
assert_eq 312 "$(wc -c <"$spoken")" "the stand-in returned one 104-byte frame per 50 characters"

assert_eq 400 "$(status_of -X POST "${AUTH[@]}" -H 'Content-Type: application/json' \
  -d '{"text":"bonjour","voice":"fr-FR-Inexistante"}' "$BASE_URL/agent/tts/synthesize")" \
  "an unknown voice is refused rather than substituted"

# ---------------------------------------------------------------------------
step "10. A conflict is read in the owner's day, not in UTC"
# ---------------------------------------------------------------------------
# MAG-114 § 1: `check_conflicts` receives a bare date and a bare time, and the
# events it compares them against are instants. Built without the owner's offset,
# 10:30 in Paris lands an hour or two away and a busy morning answers "free".
#
# Here rather than in a browser journey, and that is not a shortcut. Conflict
# detection is a read with no screen of its own: the only user-visible half is a
# sentence, and with `LLM_PROVIDER=fake` that sentence is a fixture — asserting it
# would prove nothing about the arithmetic. The tool's own answer is the thing, so
# it is driven through the real MCP transport, and the Paris boundary is pinned on
# both sides of the DST change in `CheckConflictsToolTest`.
#
# Reads the first `text` field anywhere in the envelope rather than a fixed path:
# the MCP transport is free to move it, and a hard-coded `.result.content[0]` would
# fail as "no conflict" the day it does.
tool_text() { printf '%s' "$1" | jq -r '[.. | objects | select(has("text")) | .text] | first // empty'; }

# One id per call, never a constant: JSON-RPC correlates a response to its request
# by id, and a session that has already answered 10 does not answer it again — the
# second call then comes back with nothing readable, and the assertion blames the
# tool for a mistake the caller made. Ids 1 to 3 are taken by the steps above.
mcp_request_id=10

# Calls a tool and prints its answer. `tool_text` reads the first `text` field
# anywhere in the response rather than a fixed path: the MCP transport is free to
# move it, and a hard-coded `.result.content[0]` would read as "no conflict" the day
# it does.
#
# An unreadable envelope goes to stderr, not through `fail`: this runs inside a
# command substitution, so `fail`'s line would be captured into the caller's variable
# instead of printed and its counter would be lost with the subshell. The empty
# answer makes the caller's own assertion fail, with the envelope logged just above it.
mcp_tool() {
  mcp_request_id=$((mcp_request_id + 1))
  local raw text
  raw="$(curl -sS -X POST "${AUTH[@]}" "${mcp_headers[@]}" \
    -d "$(jq -nc --arg name "$1" --argjson args "$2" --argjson id "$mcp_request_id" \
      '{jsonrpc: "2.0", id: $id, method: "tools/call", params: {name: $name, arguments: $args}}')" \
    "$BASE_URL/_mcp" | sed 's/^data: //')"
  text="$(tool_text "$raw")"

  if [ -z "$text" ]; then
    printf '    (%s answered nothing readable: %s)\n' "$1" "$(printf '%s' "$raw" | head -c 400)" >&2
  fi

  printf '%s' "$text"
}

# The seed books "Réunion d'équipe" from 10:00 to 11:00 *in Paris* on the day after
# the anchor; 10:30 for half an hour sits squarely inside it.
conflict_day="$(date -u -d "$anchor_date + 1 day" +%F)"
conflicts="$(mcp_tool check_conflicts "$(jq -nc --arg d "$conflict_day" \
  '{date: $d, time: "10:30", duration: 30}')")"

assert_eq true "$(printf '%s' "$conflicts" | jq -r '.hasConflicts // empty')" \
  "10:30 inside a 10:00–11:00 meeting in Paris is a conflict"
assert_eq 1 "$(printf '%s' "$conflicts" | jq -r '[.conflicts[]?] | length')" \
  "the one meeting that overlaps is the one reported"

# The slot the tool says it checked, which is where the bug would show: an hour or
# two out, and `hasConflicts` above would be false for a reason nothing else names.
assert_eq "${conflict_day}T10:30:00" \
  "$(printf '%s' "$conflicts" | jq -r '.checkedSlot.start // empty' | cut -c1-19)" \
  "the slot checked is the one that was asked for"
offset="$(printf '%s' "$conflicts" | jq -r '.checkedSlot.start // empty' | cut -c20-)"
assert_eq false "$([ "$offset" = '+00:00' ] && echo true || echo false)" \
  "the slot carries Paris's offset ('$offset'), not UTC"

# An evening slot on the same day is free — the control. Without it, a tool that
# answered "conflict" to everything would pass every assertion above.
free="$(mcp_tool check_conflicts "$(jq -nc --arg d "$conflict_day" \
  '{date: $d, time: "22:00", duration: 30}')")"
assert_eq false "$(printf '%s' "$free" | jq -r '.hasConflicts // empty')" \
  "an evening nobody booked is still free"

# ---------------------------------------------------------------------------
step "11. A due reminder becomes a notification"
# ---------------------------------------------------------------------------
# The reminder chain has no browser surface at all — nothing in the admin sets a
# reminder, and the only producer is a cron the browser cannot run. So it is here,
# where `docker compose exec` can, like the two sync steps above.
#
# The event is created against the real clock rather than taken from the seed, and
# that is forced: `maggie:notification:check-reminders` fires on a reminder whose
# trigger time has passed for an event starting within 24 hours, and no
# anchor-relative time satisfies both bounds at every hour a run might start at.
# Forty minutes out with a reminder an hour before leaves the trigger twenty
# minutes behind us, whenever "now" is.
reminder_agenda="$(printf '%s' "$agendas" | jq -r '[.member[] | select(.default == true)][0]."@id" // empty')"
if [ -n "$reminder_agenda" ]; then
  pass "the owner has a default agenda to book into"
else
  fail "no default agenda in /api/agendas — the reminder step has nowhere to write"
fi

reminder_title="Rappel smoke $(date -u +%H%M%S)"
reminder_event="$(curl -sS -X POST "${AUTH[@]}" \
  -H 'Content-Type: application/ld+json' -H 'Accept: application/ld+json' \
  -d "$(jq -nc --arg s "$reminder_title" --arg a "$reminder_agenda" \
    --arg start "$(date -u -d '+40 minutes' +%FT%T+00:00)" \
    --arg end "$(date -u -d '+70 minutes' +%FT%T+00:00)" \
    '{summary: $s, startAt: $start, endAt: $end, agenda: $a}')" \
  "$BASE_URL/api/events")"
reminder_event_id="$(printf '%s' "$reminder_event" | jq -r '.id // empty')"
if [ -n "$reminder_event_id" ]; then
  pass "the event the reminder hangs off was created"
else
  fail "could not create the event — response: $(printf '%s' "$reminder_event" | head -c 300)"
fi

# A second request, because `CreateEventCommand` carries no reminders: POST drops
# them in silence and PATCH is the only way in. Google's own shape —
# `{useDefault, overrides: [{method, minutes}]}` — which is what the command reads;
# a bare list is no reminder at all, and nothing says so.
reminder_patch="$(status_of -X PATCH "${AUTH[@]}" \
  -H 'Content-Type: application/merge-patch+json' \
  -d '{"reminders":{"useDefault":false,"overrides":[{"method":"popup","minutes":60}]}}' \
  "$BASE_URL/api/events/$reminder_event_id")"
assert_eq 200 "$reminder_patch" "the reminder was set on the event"

# The cron reads Doctrine, so it sees the event the moment the PATCH returns; the
# notification it writes is read back from Elasticsearch, which is what the wait
# below is for.
"${COMPOSE[@]}" exec -T php bin/console --env=e2e maggie:notification:check-reminders >/dev/null

reminders_of() {
  curl -sS "${AUTH[@]}" -H 'Accept: application/ld+json' "$BASE_URL/api/notifications?itemsPerPage=100" \
    | jq -c --arg t "$reminder_title" '[.member[] | select(.title == $t)]'
}

notified=""
for _ in $(seq 1 60); do
  reminder_notifications="$(reminders_of)"
  if [ "$(printf '%s' "$reminder_notifications" | jq -r 'length')" -gt 0 ]; then
    notified=yes
    break
  fi
  sleep 1
done

# Asserted on the notification rather than on the command's output: the owner reads
# the bell, and a command that printed a success line while writing nothing is
# exactly the failure this step is for.
if [ -n "$notified" ]; then
  pass "the due reminder produced a notification"
else
  fail "the cron wrote no notification for '$reminder_title' — is the reminder shape still {useDefault, overrides}?"
fi

assert_eq reminder "$(printf '%s' "$reminder_notifications" | jq -r '.[0].type // empty')" \
  "the notification carries the reminder kind"
assert_eq "/api/events/$reminder_event_id" \
  "$(printf '%s' "$reminder_notifications" | jq -r '.[0].relatedEntityIri // empty')" \
  "the notification points back at the event"

# Twice in a row must not notify twice: the cron runs every few minutes, and
# without the dedup on (event, minutes) the owner would be reminded on every tick.
#
# Read after a window rather than at once, and that is the whole difficulty of
# proving an absence here: a duplicate written to Postgres is not yet in the index,
# so an immediate read answers "one" whether the dedup held or not. Five seconds is
# the same bargain `expectSilence` makes in the browser harness — there is no event
# to wait on instead.
"${COMPOSE[@]}" exec -T php bin/console --env=e2e maggie:notification:check-reminders >/dev/null
sleep 5
assert_eq 1 "$(reminders_of | jq -r 'length')" "a second run of the cron reminds nobody twice"

# ---------------------------------------------------------------------------
printf '\n\033[1mSmoke journey: %d passed, %d failed\033[0m\n' "$passed" "$failed"

if [ "$failed" -gt 0 ]; then
  printf '\nRun `task e2e:logs` to see what the stack was doing.\n'
  exit 1
fi
