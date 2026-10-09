#!/usr/bin/env bash
#
# The coverage the e2e stack recorded per journey, as raw files (spec « Sélection
# e2e par couverture », contract 2). Run through `task e2e:coverage:collect`,
# after the journeys, on a stack started with E2E_COVERAGE=1.
#
# Writes, emptied first:
#   e2e/coverage/raw/api/<slug>.json     what the API ran for each journey
#   e2e/coverage/raw/agent/<slug>.json   what the agent ran for each journey
# each `{"journey": "<id>", "files": {"<repo path>": [executed lines]}}`.
#
# Reads without consuming: run it again later and it gives everything recorded
# so far. The agent is restarted on the way (its coverage is flushed when its
# process exits) and is healthy again when this returns. See README.md here.
#
# Needs COMPOSE: the e2e stack's `docker compose -f … -p …` (the Taskfile sets it).

set -euo pipefail

: "${COMPOSE:?set COMPOSE to the docker compose command of the e2e stack, or run task e2e:coverage:collect}"
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
out="$root/e2e/coverage/raw"

rm -rf "$out/api" "$out/agent"
mkdir -p "$out/api" "$out/agent"

# --- API: pcov wrote one file per journey and php-fpm worker; fold them. ------
# No simulated clock for a console command, as for every exec in e2e/Taskfile.yml.
$COMPOSE exec -T -e LD_PRELOAD= -e FAKETIME= php bin/console --env=e2e app:e2e:coverage:merge
find "$root/api/var/e2e/coverage/raw/api" -maxdepth 1 -name '*.json' -exec cp {} "$out/api/" \; 2>/dev/null || true

# --- Agent: coverage.py saves its data when the process ends. -----------------
# Stop (SIGTERM: uvicorn shuts down, coverage.py writes its file), start again —
# the data directory is bind-mounted and every process writes its own file — and
# export from inside the running container, which has coverage installed.
$COMPOSE stop -t 30 agent
$COMPOSE up -d --wait --no-deps agent
$COMPOSE exec -T agent python -m app.e2e_coverage.export --data-dir .e2e-coverage --out .e2e-coverage/raw
find "$root/agent/.e2e-coverage/raw" -maxdepth 1 -name '*.json' -exec cp {} "$out/agent/" \; 2>/dev/null || true

api_count="$(find "$out/api" -name '*.json' | wc -l)"
agent_count="$(find "$out/agent" -name '*.json' | wc -l)"
echo "e2e coverage: $api_count journeys for the API, $agent_count for the agent, in e2e/coverage/raw/"
