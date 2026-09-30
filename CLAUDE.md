# Maggie

Personal AI agent platform. Tickets live in Linear (workspace Meven, team **Maggie**, key `MAG`).

| Directory | Stack |
|---|---|
| `api/` | Symfony 7, API Platform 4, Doctrine, Mercure, MCP bundle — modular bundles in `api/modules/` (calendar, core, cookbook, grocery, notification, finance) |
| `agent/` | Python 3.12, FastAPI, Anthropic Claude, MCP client |
| `admin/` | React (API Platform Admin), Vite |
| `mobile/` | Kotlin, Jetpack Compose, Ktor, Koin |

Project skills are in `.claude/skills/`, agent-os commands in `.claude/commands/agent-os/`, standards and specs in `agent-os/`.

## Workflow

- No code without a plan: the ticket needs `agent-os/specs/*/plan.md`. No spec yet → `/shape-spec`; otherwise `/implement-spec`. Pull standards on demand with `/inject-standards` (index: `agent-os/standards/index.yml`) — never paste all of them into context.
- One ticket = one branch = one PR that links the ticket.
- Delivering a spec also means updating the module's functional spec and the user guide (Linear Documents, entry point: team doc « Index de la documentation Maggie »).
- Keep specs, prompts and comments short and actionable: acceptance criteria and e2e journeys, not prose.

## Autonomy — decide by default, ask only when it matters

Agents take tickets unattended, from shaping to PR. At any step, **decide and move on** when the ticket, the module's functional spec, the standards or the existing code give an answer.

Every decision taken alone leaves a trace: a comment on the Linear ticket, posted when the decision is made, with **Dilemma** (the question, in one line), **Options** (those considered), **Choice** and **Why** (the ticket, spec, standard or code that settles it). Repeat the list in the PR description.

Stop and ask only for:
- a product, UX or data-model decision nothing answers and that is costly to undo;
- conflicting requirements;
- something destructive or irreversible (deleting data, prod, secrets, infra);
- a missing access or credential;
- the same failure twice (CI still red after a fix, a rebase conflict you cannot resolve).

To ask: reply in the ticket's agent thread with short questions, each as options with your recommendation first; add the `needs-human` label; stop cleanly. When the answer arrives in the thread, remove `needs-human` and resume where you stopped.

## Commands — Docker only

Never run `php`, `composer`, `bin/console`, `npm`, `pytest` or `uv` on the host: runtimes live in containers. Use the Taskfile.

| Component | Lint | Tests |
|---|---|---|
| API | `task api:lint` | `task api:test` (`-- --testsuite <Module>` to narrow) |
| Agent | `task agent:lint` + `task agent:format:check` | `task agent:test` |
| Admin | `task admin:lint` + `task admin:typecheck` | `task admin:test` |
| Mobile | CI (local `lintProdRelease` crashes on a known AGP/K2 bug) | `cd mobile && ./gradlew testProdReleaseUnitTest` (Java 21 via `org.gradle.java.home`) |

Symfony console: `task api:console -- <args>`.

**In a git worktree** (parallel agents): `task *:test` runs `docker compose exec` against the dev stack, which mounts the main checkout, not your worktree — it would test the wrong code. Do not start a second stack either (host ports collide). Push and let CI verify until the isolated e2e stack exists.

## Tests — every change

A ticket is **done** only when all five hold. Full rules, including what each kind of unit owes: `agent-os/standards/global/testing.md`.

1. **Every unit touched has a test.** API endpoint or MCP tool: 401 unauthenticated, 400 bad input, happy path asserting **DB state**, `assertMercureUpdatePublished`, `assertElasticsearchIndexDispatched`. Use `MercureAssertionTrait` + `ElasticsearchAssertionTrait`; call `resetMercure()` + `resetAsyncTransport()` in `setUp`. Reference: `api/modules/grocery/tests/Mcp/GroceryToolsTest.php`. Also: entity → Mercure Create/Update/Delete, admin component → render + interaction + error, mobile ViewModel → one test per state transition.
2. **At least one e2e journey covers the feature**, new or extended (Playwright web, Maestro mobile, deterministic fake LLM). The harness isn't built yet: until then, write the journey as Given/When/Then in the spec's `plan.md` and name the journey ticket that will carry it.
3. **Every bug fix ships its reproduction test**, written red before the fix.
4. **CI green on a PR** that links the ticket.
5. **Module functional spec and user guide updated in Linear** (ADR-006).

No user-facing behavior (standards, docs, infra, pure refactor)? Write `E2E: N/A — <reason>` in `plan.md`. An exemption that isn't written down is a missing test. Lint and tests of every touched component must pass before committing.

## Commits

- English, imperative subject that says the intent (what changes for the user or the code), body explains why.
- **No `Co-Authored-By` trailer.**
- This repository is **public**: never commit secrets, `.env.local`, `*.pem`, keystores or real personal data.

## Gotchas

- **MCP auth**: `/_mcp` requires a bearer (user JWT, or `SERVICE_TOKEN` + `X-Maggie-User-Id`). Tools read the user through `McpUserContext` (`requireUser()`), filter reads by user, and catch `MissingMcpUserException`. Never `findAll()[0]`.
- **MCP discovery**: a module missing from `discovery.scan_dirs` in `api/config/packages/mcp.yaml` has its tools silently hidden. New bundle → add `modules/<name>/src` and check with a real `tools/list`.
- **API Platform**: never declare a `Patch` operation on an `uriTemplate` without an identifier (`/…/me`): it instantiates a new entity → 500. Use a dedicated REST controller that only applies the fields present.
- **Elasticsearch**: indexable collections are served from ES and fall back to Doctrine only on exceptions — a stale index returns an empty list silently. Check with `task api:console -- app:elasticsearch:status --check`.
- Entities use ULID; events follow the Google Calendar model (RRULE, reminders, exception instances). API Platform 4 collections use the `member` key.
- Mobile autocomplete opens a dedicated full-screen search, never an inline dropdown.
