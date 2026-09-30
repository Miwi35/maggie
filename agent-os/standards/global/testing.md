# Testing Strategy

One behavior per test. Mock all external services. Only integration tests use real DB.

## Definition of Done

A ticket is done only when **all five** hold:

1. **Unit or integration tests cover every unit touched** — endpoint, MCP tool, entity,
   ViewModel, component. See [what "covered" means](#1-what-covered-means).
2. **At least one e2e journey covers the feature**, new or extended.
3. **A bug fix ships with the test that reproduces it**, written red before the fix.
4. **CI is green on a pull request** that links the ticket.
5. **The module's functional spec and the user guide are up to date in Linear** (ADR-006).

Nothing here is implicitly waived. Every "not applicable" is written in the spec's
`plan.md` with its one-line reason — an unwritten exemption is a missing test.

### 1. What "covered" means

Per unit touched, whatever the ticket is about:

| Touched | Minimum tests |
|---|---|
| MCP tool | no user bound, bad input (missing required arg **and** unknown `action`), happy path asserting DB state, `assertMercureUpdatePublished()`, `assertElasticsearchIndexDispatched()` when the entity is indexed |
| API endpoint (REST controller, API Platform operation) | 401 unauthenticated, 400 bad input, happy path asserting DB state, Mercure, Elasticsearch — same list |
| Entity | Create/Update/Delete Mercure publication, in `MercurePublishMiddlewareTest` |
| Service, repository, pure logic | happy path **and** every error branch |
| Admin component | initial render, the user interaction it exists for, error/empty state |
| Mobile ViewModel | one test per state transition — loading, success, error — asserting `uiState` |
| Agent route, gateway, MCP client | happy path and error branch, HTTP mocked with `respx` |

Assert observable state, not that a mock was called. Reference implementations:
`api/modules/grocery/tests/Mcp/GroceryToolsTest.php` (MCP tool),
`api/modules/grocery/tests/Controller/CheckGroceryItemControllerTest.php` (endpoint).

Helpers live in `api/tests/Support/`: `FixtureLoaderTrait`, `SecurityTokenTrait`,
`MercureAssertionTrait`, `ElasticsearchAssertionTrait`, `AuthenticatedTestTrait`.
Call `resetMercure()` and `resetAsyncTransport()` in `setUp()`, otherwise assertions
leak between tests. Delete paths assert `assertElasticsearchDeleteDispatched()`.

Per-component conventions: [api/testing](../api/testing.md),
[admin/testing](../admin/testing.md), [agent/testing](../agent/testing.md),
[mobile/testing](../mobile/testing.md).

### 2. The e2e journey

Per ADR-005: Playwright for the web (`e2e/web/`), Maestro for the mobile app
(`e2e/mobile/`), both on an isolated stack with test data, a test login and a
**deterministic fake LLM**. E2E block pull requests.

**The harness does not exist yet** (MAG-94 environment, MAG-95 fake LLM, MAG-97
Playwright, MAG-98 Maestro). Until it lands, a ticket satisfies this rule by:

- a `## E2E journey` section in its `plan.md`, written as Given / When / Then steps
  precise enough to be transcribed into a Playwright spec or a Maestro flow without
  deciding anything again — selectors by role and text, the real-time updates to wait
  for, the DB state to check;
- naming the journey ticket that will carry it, so no journey gets lost: MAG-99 chat,
  MAG-100 agenda, MAG-101 recipes/meals/groceries, MAG-102 finance, MAG-103
  settings/search/notifications.

Once the harness exists, the executable flow is part of the ticket and CI runs it.

**No user-facing behavior** — standards, docs, prompts, infra, refactor with no
behavior change: write `E2E: N/A — <reason>` in `plan.md`. Anything the user can see
or click gets a journey.

### 3. A bug fix starts red

Reproduce first: write the test, run it, watch it fail *for the reason the bug
describes*, then fix. A fix whose test never failed proves nothing. The PR shows both,
and the test keeps its ticket reference in the test name or a one-line comment.

### 4. CI green on a PR

One ticket = one branch = one PR that links the ticket. Lint and tests of every touched
component pass before committing — see the [pre-commit](../../../.claude/skills/pre-commit/SKILL.md)
skill for the commands.

**In a git worktree** (parallel agents), `task *:test` runs against the dev stack, which
mounts the main checkout — it would test the wrong code. Push and let CI verify until the
isolated e2e stack exists (MAG-94).

### 5. Documentation up to date (ADR-006)

Delivering an implementation spec means updating, in Linear: the **module's functional
spec** (concepts, business rules, journeys, current state) and the **user guide**.
Entry point: the team document « Index de la documentation Maggie ».

## Test Pyramid

| Layer | What | Runs in CI |
|---|---|---|
| Unit | Pure logic, mocked deps | Always |
| Integration | Real DB, real DI container | With service containers |
| API/HTTP | Full request cycle | With service containers |
| E2E | Playwright (web) and Maestro (mobile), fake LLM | Blocking, once the harness lands (MAG-96) |
| Eval | Real model, prompt-lab scenarios | Nightly, non-blocking (ADR-005) |

## Per-Component Quick Reference

| Component | Framework | Run command | Config |
|---|---|---|---|
| API | PHPUnit 12 | `task api:test` (`-- --testsuite <Module>` to narrow) | `api/phpunit.dist.xml` |
| Agent | pytest 9 | `task agent:test` | `agent/pyproject.toml` |
| Admin | Vitest 3 | `task admin:test` | `admin/vite.config.ts` |
| Mobile | JUnit 4 + MockK | `cd mobile && ./gradlew testProdReleaseUnitTest` | `build.gradle.kts` |

## Rules

- Never run test commands on host — always via `task` or Docker
- CI runs lint before tests (lint gates test jobs)
- `MESSENGER_TRANSPORT_DSN=sync://` in test env — no RabbitMQ needed
- No test interdependencies — each test sets up and cleans its own state
- A new module missing from `phpunit.dist.xml` runs zero tests silently — add its
  `<testsuite>` and `<source><directory>` entries
