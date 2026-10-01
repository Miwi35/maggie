# Standards for Context-aware conversation history

Included by reference rather than pasted, so they stay in sync with the originals.

| Standard | Why it applies |
|---|---|
| @agent-os/standards/global/testing.md | The Definition of Done. A gateway, a repository and a new module: happy path and every error branch, plus an e2e journey for user-visible behaviour. |
| @agent-os/standards/agent/architecture.md | Where a module goes under `agent/app/`, how settings are declared (pydantic-settings), how the gateways and the MCP client are wired. |
| @agent-os/standards/agent/testing.md | pytest async auto mode, the `conftest` fixtures (`chat_db` runs real SQL on SQLite), `respx` for HTTP, assert state not mock calls. |
| @agent-os/standards/global/e2e-environment.md | The fake LLM and its scenario files, the agent's environment in `docker-compose.e2e.yml`, `task e2e:up` / `task e2e:web`. |
| @agent-os/standards/global/worktree-checks.md | `task wt:test:agent`, `task wt:lint:agent`, `task fix:all` — a formatter must never run from a worktree against the dev stack. |

Not applicable: `api/*` (no PHP touched), `admin/*` and `mobile/*` (no client change — the
`/agent/chat` response gains nothing a client reads), `global/real-time` (no new Mercure
topic; the `contexts` publications are MAG-11's and MAG-12's and are untouched).
