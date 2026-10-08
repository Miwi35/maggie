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

**The stack exists** (MAG-94): `task e2e:up`, `task e2e:seed`, `task e2e:smoke`,
`task e2e:down`, one per git worktree. How to use it, what the test login is,
how the fixtures stay deterministic and which external services are simulated:
[global/e2e-environment](e2e-environment.md).

**The fake LLM exists** (MAG-95): the stack runs on `LLM_PROVIDER=fake`, so a
journey can talk to Maggie deterministically. It replaces the model and nothing
else — the tool loop, the streaming gateway and MCP are the production ones.
Judgement (tone, tool choice, keeping the thread) is checked separately by the
eval suite, `task e2e:eval`, on the real model, nightly and outside CI. An
assertion that would hold with any plausible wording belongs in a journey with
the fake, not in the eval.

**The browser harness exists** (MAG-97): `e2e/web/`, `task e2e:web`, run by CI
on every pull request. A web-facing ticket writes its journey as a Playwright
spec — fixtures for the signed-in user and the neighbour, page objects per
module, helpers for real-time and for the AG-UI chat stream, and three
viewport widths. Read [e2e/web/README.md](../../../e2e/web/README.md) before
writing one; the traps it lists (index lag, subscribing after acting, locators
that match Maggie's answer as well as the page) are all ones that have already
cost a debugging session.

**The emulator harness exists** (MAG-98): `e2e/mobile/`, `task e2e:mobile`, run by
CI on every pull request that touches the app or the stack, and on a phone, a
foldable and a tablet in the nightly run. A mobile-facing ticket writes its
journey as a Maestro flow — the `e2e` Gradle flavor signs in through the test
login, and the app is addressed by the `testTag`s declared in
`mobile/app/src/main/java/com/maggie/app/ui/UiTags.kt`. Read
[e2e/mobile/README.md](../../../e2e/mobile/README.md) before writing one.

**Most of a mobile ticket's screen verifications are not a flow** (MAG-242). What a
screen draws from a server answer, in what order, its empty and error states, and
navigation inside the app are a **Compose test on the JVM**
(`mobile/app/src/test/…/…ScreenTest.kt`, Robolectric, seconds, in `Mobile Unit
Tests`). The emulator keeps what no other suite can see: the socle, permissions,
the microphone, deep links, real time end to end, notifications, and layout
against the platform — a sheet under the keyboard, an overlay that re-speaks on
open, a view that does not refresh. Which is which:
[mobile/screen-tests](../mobile/screen-tests.md). A ViewModel assertion that needs
no screen at all is a plain unit test ([mobile/testing](../mobile/testing.md)).

The journey tickets that carry the rest are MAG-99 chat, MAG-100 agenda, MAG-101
recipes/meals/groceries, MAG-102 finance, MAG-103 settings/search/notifications.
A flow names the fixtures it needs in `api/fixtures/e2e/`.

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

**In a git worktree** (parallel agents), `task api:test` runs against the dev stack, which
mounts the main checkout — it would test the wrong code. Use `task wt:test:api` instead:
a throwaway Postgres and this worktree's code. See
[global/worktree-checks](worktree-checks.md).

**Coverage may not drop (MAG-105).** Every test job measures its component's line coverage
— PHPUnit with pcov, pytest-cov (agent, ciqual), Vitest v8, Kover (mobile) — and fails when
it falls more than 0.10 point under its entry in `scripts/coverage/baseline.json`. No absolute
threshold: the baseline is what the component already has, and it only moves up. A comment on
the PR shows each component against its baseline (one comment, from `ci.yml`, for the components that ran).

- Red `Coverage dropped`: test what you changed. Deleting well-tested code can lower the
  percentage legitimately — then lower the entry in `baseline.json` in the same PR and say why
  in the description; the diff is the audit trail.
- Gain: once CI is green, `task coverage:ratchet -- <pr>` raises the baseline to what CI
  measured; commit the file. Not required, but unraised gains are room to erode.
- A component with no entry passes as `new` until the first `coverage:ratchet`.
- The scripts and their tests: `scripts/coverage/`, `infra/scripts/tests/coverage.test.sh`.

### 5. Documentation up to date (ADR-006)

Delivering an implementation spec means updating, in Linear: the **module's functional
spec** (concepts, business rules, journeys, current state) and the **user guide**.
Entry point: the team document « Index de la documentation Maggie ».

## The Recette account (MAG-249)

A recette agent tries a feature **in production** without touching the owner's data: it acts as
a technical account of its own, « Recette (compte technique) » (distinct from the smoke account
of `app:smoke:token`, which only reads). **Every recette object — event, meal, proaction,
message, rule — lives in this account, never in the owner's.**

| What | How |
|---|---|
| Get a token | `kubectl exec` into the `php` pod, `bin/console app:recette:token` — the JWT alone on stdout. The account is created and indexed at first use |
| Wipe it | `bin/console app:recette:reset` (`--dry-run` only counts). Deletes the account's data in every API module, its Elasticsearch documents and the agent's data (`maggie_agent`); keeps the account and its permission. It takes **no user argument**: the account is a constant in the command, so it cannot be pointed at anyone else. Run by hand, never scheduled |
| Grant the permission | `bin/console app:user:grant <email> ROLE_PROACTION_TRIGGER` — `app:user:revoke` takes it back. Console only, never a route. The role is stored on the user and carried by the JWT, so it applies to the **next** token; the Recette token has it, service tokens and normal users do not |

Reset before and after a recette: the next one starts from nothing, and a pass leaves nothing behind.
`app:elasticsearch:status --check` stays green after a reset. Skills are global to the agent, not the
account's: reset does not touch them.

### Recette of a proaction

Under `ROLE_PROACTION_TRIGGER`, with the Recette token (403 without it), always for the calling account:

1. **Rule** — add the planning rule (instruction) to the account, as a user would.
2. **Generation** — `POST /agent/proactions/generate` runs the daily planner now (the scheduler's own call)
   and returns what it planned.
3. **Proaction** — `GET /agent/proactions` lists them with rule (`prompt`), due date (`scheduledAt`) and `status`.
4. **Execution** — `POST /agent/proactions/{id}/execute` runs a pending proaction through the consumer's path
   and returns the message; a proaction already taken answers 409.

**Dry run for calibration:** add `?dry_run=true` to either route. Nothing is recorded — no proaction, no
message, no notification; the tools that write are simulated (read tools run for real) and the response
lists them under `simulatedTools`. Repeat until the prompt behaves, then run it for real.

The account has no side effect outside the platform: no push notification, no Google sync.

## Test Pyramid

| Layer | What | Runs in CI |
|---|---|---|
| Unit | Pure logic, mocked deps | Always |
| Integration | Real DB, real DI container | With service containers |
| API/HTTP | Full request cycle | With service containers |
| E2E | Playwright (web, `task e2e:web`) and Maestro (mobile, `task e2e:mobile`), fake LLM | Blocking (MAG-96) |
| Eval | Real model, prompt-lab scenarios | Nightly, non-blocking (ADR-005) |

## Per-Component Quick Reference

| Component | Framework | Run command | Config |
|---|---|---|---|
| API | PHPUnit 12 | `task api:test` (`-- --testsuite <Module>` to narrow) | `api/phpunit.dist.xml` |
| Agent | pytest 9 | `task agent:test` | `agent/pyproject.toml` |
| Admin | Vitest 3 | `task admin:test` | `admin/vite.config.ts` |
| Mobile | JUnit 4 + MockK | `task wt:test:mobile -- --tests '<class or package>'` — the only local Gradle build; CI runs `prodRelease` | `build.gradle.kts` |
| Web journeys | Playwright | `task e2e:web` (needs `task e2e:up`) | `e2e/web/playwright.config.ts` |
| Mobile journeys | Maestro | `task e2e:mobile` (needs `task e2e:up` and a device) | `e2e/mobile/config.yaml` |

## Rules

- Coverage is measured by CI, not locally: the ratchet compares like with like (pcov, not Xdebug)
- Never run test commands on host — always via `task` or Docker
- Locally, mobile = `task wt:test:mobile -- --tests …` only: never `./gradlew` by hand, never `assemble*` or `lint*`, never Maestro or an emulator (except `task e2e:mobile` to write or debug a journey). CI does the rest.
- CI runs lint before tests (lint gates test jobs)
- `MESSENGER_TRANSPORT_DSN=sync://` in test env — no RabbitMQ needed
- No test interdependencies — each test sets up and cleans its own state
- A new module missing from `phpunit.dist.xml` runs zero tests silently — add its
  `<testsuite>` and `<source><directory>` entries
