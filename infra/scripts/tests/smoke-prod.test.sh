#!/usr/bin/env bash
# shellcheck disable=SC2015 # `cond && ok || bad`: ok and bad cannot fail
#
# Tests of infra/scripts/smoke-prod.sh against a stub of production (MAG-106).
#
# The suite decides whether production is rolled back, so it is held to two
# promises: green on a healthy production, and red — for the right reason — on
# each failure it exists to catch. Each case below breaks one thing in the stub.
#
# Usage: infra/scripts/tests/smoke-prod.test.sh

set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SMOKE="$HERE/../smoke-prod.sh"
TOKEN="stub-token"

failures=0
stub_pid=""

cleanup() { [ -n "$stub_pid" ] && kill "$stub_pid" 2>/dev/null; rm -f "${port_file:-}"; }
trap cleanup EXIT

start_stub() {
  cleanup
  port_file="$(mktemp)"
  STUB_BREAK="$1" STUB_TOKEN="$TOKEN" python3 "$HERE/stub_prod.py" >"$port_file" &
  stub_pid=$!
  for _ in $(seq 1 50); do
    [ -s "$port_file" ] && break
    sleep 0.1
  done
  PORT="$(cat "$port_file")"
}

# run_smoke <break> [env…] — runs the suite against the stub, output in $OUTPUT.
run_smoke() {
  local breaks="$1"
  shift
  start_stub "$breaks"
  OUTPUT="$(env SMOKE_TOKEN="$TOKEN" BASE_URL="http://127.0.0.1:$PORT" SMOKE_ATTEMPTS=1 SMOKE_DELAY=0 \
    "$@" "$SMOKE" 2>&1)"
  STATUS=$?
}

# expect_red <label> <break> <text the failure must name> [env…]
expect_red() {
  local label="$1" breaks="$2" needle="$3"
  run_smoke "$breaks" "${@:4}"
  if [ "$STATUS" -ne 0 ] && printf '%s' "$OUTPUT" | grep -qF -- "$needle"; then
    printf '  \033[32m✓\033[0m %s\n' "$label"
  else
    printf '  \033[31m✗\033[0m %s — expected red naming "%s", exit %d\n%s\n' "$label" "$needle" "$STATUS" "$OUTPUT"
    failures=$((failures + 1))
  fi
}

printf '\n\033[1mHealthy production\033[0m\n'
run_smoke ""
if [ "$STATUS" -eq 0 ]; then
  printf '  \033[32m✓\033[0m every check passes\n'
else
  printf '  \033[31m✗\033[0m a healthy production must pass — exit %d\n%s\n' "$STATUS" "$OUTPUT"
  failures=$((failures + 1))
fi

printf '\n\033[1mEach regression turns it red\033[0m\n'
expect_red "a degraded agent"                       agent_health        "the agent health body is not status ok"
expect_red "an admin that 404s"                     admin_missing       "GET /admin is served without a redirect"
expect_red "an admin redirect to http://"           admin_http_redirect "redirects to a plain http://"
expect_red "a Mercure hub refusing the origin"      mercure_cors        "the hub does not allow"
expect_red "a token the API rejects"                ""                  "GET /api/users/me did not return" SMOKE_TOKEN=wrong-token
expect_red "no MCP session"                         mcp_no_session      "the MCP handshake returned no session id"
expect_red "zero MCP tools"                         mcp_empty           "tools/list returned no tool"
expect_red "a module missing from MCP discovery"    mcp_missing_module  "no tool mentions 'grocery'"
expect_red "Maggie answering without a tool"        agent_no_tool       "Maggie did not answer through a tool"
expect_red "a tool that errors"                     agent_tool_error    "the tool call errored"
expect_red "Maggie apologising instead of answering" agent_apology      "the answer is an apology"
expect_red "an answer that guesses the title"       agent_guesses       "the answer does not carry the title"
expect_red "an event the account cannot create"     event_creation_fails "the test event could not be created"
expect_red "a history reset the agent refuses"      history_reset_fails "DELETE /agent/smoke/history did not answer 200"
expect_red "an agent that is down"                  agent_down          "Maggie did not answer through a tool"
expect_red "Elasticsearch out of sync"              ""                  "app:elasticsearch:status --check failed" ES_CHECK_CMD="echo DRIFT; exit 1"

printf '\n\033[1mThe technical account starts blank and leaves nothing\033[0m\n'
# The stub's model skips the tool whenever a history is behind it — the production failure.
run_smoke "imitates_history"
if [ "$STATUS" -eq 0 ]; then
  printf '  \033[32m✓\033[0m a history that makes the model skip the tool is wiped before the question\n'
else
  printf '  \033[31m✗\033[0m the suite must wipe the history first — exit %d\n%s\n' "$STATUS" "$OUTPUT"
  failures=$((failures + 1))
fi
state="$(curl -sS "http://127.0.0.1:$PORT/__state")"
if [ "$state" = '{"agendas": 0, "history": 0}' ]; then
  printf '  \033[32m✓\033[0m the test agenda and the history are gone after the run\n'
else
  printf '  \033[31m✗\033[0m the technical account kept data after the run: %s\n' "$state"
  failures=$((failures + 1))
fi

printf '\n\033[1mElasticsearch check\033[0m\n'
run_smoke "" ES_CHECK_CMD="true"
if [ "$STATUS" -eq 0 ] && printf '%s' "$OUTPUT" | grep -qF 'status --check is green'; then
  printf '  \033[32m✓\033[0m runs the command it is given\n'
else
  printf '  \033[31m✗\033[0m the ES check did not run or did not pass\n%s\n' "$OUTPUT"
  failures=$((failures + 1))
fi

printf '\n\033[1mSecrets\033[0m\n'
run_smoke "mcp_empty" SMOKE_TOKEN="$TOKEN"
if printf '%s' "$OUTPUT" | grep -qF -- "$TOKEN"; then
  printf '  \033[31m✗\033[0m the token appears in the output of a failing run\n'
  failures=$((failures + 1))
else
  printf '  \033[32m✓\033[0m the token never reaches the output\n'
fi

printf '\n'
if [ "$failures" -gt 0 ]; then
  printf '\033[31m%d failing case(s)\033[0m\n' "$failures"
  exit 1
fi
printf 'All cases pass.\n'
