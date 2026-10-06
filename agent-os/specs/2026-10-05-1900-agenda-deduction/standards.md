# Standards that apply — MAG-150

Selected from `agent-os/standards/index.yml` by the paths this feature touches:
`api/modules/calendar/`, `api/contract/`, `agent/fixtures/fake-llm/`, `e2e/web/`,
`scripts/prompt-lab/scenarios/`.

| Standard | Why it applies |
|---|---|
| @agent-os/standards/global/testing.md | The Definition of Done. Always relevant; read, not confirmed. A service owes its happy path **and every error branch**; an MCP tool owes no-user, bad input, DB state, Mercure and Elasticsearch |
| @agent-os/standards/api/mcp-tools.md | `create_event` is an MCP tool: writes go through the bus, reads through a repository, the user comes from `McpUserContext` |
| @agent-os/standards/api/testing.md | PHPUnit with nelmio/alice fixtures, the `api/tests/Support/` traits, `resetMercure()` + `resetAsyncTransport()` in `setUp()` |
| @agent-os/standards/agenda/google-calendar-model.md | The agendas and events being scored are the Google Calendar model — recurrence and exception instances mean a `cancelled` occurrence is a real row the history has to ignore |
| @agent-os/standards/global/e2e-environment.md | The journey runs on the e2e stack with `LLM_PROVIDER=fake`; the fixtures are anchor-relative, and a scenario file has no templating |
| @agent-os/standards/global/worktree-checks.md | This session runs in a worktree: `task fix:all`, `task wt:up` / `wt:test:api` / `wt:down`, never `task api:*` |
| @agent-os/standards/global/agent-guard-rails.md | What may be merged alone — nothing here touches `infra/`, `.github/`, secrets, auth or a migration |

Not relevant: `admin/*` (no React), `mobile/*` (no Kotlin), `agent/architecture` and
`agent/testing` (no Python changes — the fixtures are data), `global/real-time` (Mercure
publication is unchanged, only asserted), `docker/*`.

## Key points to honour

- **Contract files** (`api/contract/README.md`, MAG-104): `mcp-tools.json` records the tool
  descriptions. Changing `create_event`'s description makes the `Contract` suite fail on
  purpose — regenerate with `UPDATE_CONTRACT=1` and commit the diff so a reviewer sees it.
- **MCP user context**: the deduction reads only `requireUser()`'s agendas and events; no
  `findAll()`, no cross-user row, and no other user's agenda in any error message.
- **Coverage may not drop** (MAG-105): the new service is pure logic with no excuse for an
  untested branch.
