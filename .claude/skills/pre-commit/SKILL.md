---
name: pre-commit
description: "Pre-commit lint and test rules, plus the definition of done. Use before committing code and before calling a ticket finished."
user-invocable: false
---

# Pre-Commit Rules

**MANDATORY**: Run lint + tests for ALL changed components BEFORE `git commit`.

## Per-Component Commands

| Component | Lint | Test |
|-----------|------|------|
| API | `task api:lint` | `task api:test` |
| Agent | `task agent:lint && task agent:format:check` | `task agent:test` |
| Admin | `task admin:lint && task admin:typecheck` | `task admin:test` |
| Mobile | CI only | `task wt:test:mobile -- --tests '<class or package>'` — the only local Gradle build |

## Multi-Component Shortcut

```
task lint:all
```

This runs API + Admin + Agent lint (excludes mobile). **Tests: only the targeted ones** — the tests of what you changed (`task api:test -- --filter …`, one spec file, one pytest path). The full suites run in CI on every PR; never run them locally.

**In a git worktree**, `task api:test` runs against the dev stack, which mounts the main
checkout — it would test the wrong code. Use the worktree-local tasks:

```
task fix:all                          # before every push: all fixers, all linters, PHPStan on changed files
task wt:phpstan -- <files>            # ~2 s warm, one container
task wt:test:api                      # PHPUnit on a throwaway Postgres, ~30 s
task wt:up                            # test loop: keep the Postgres once...
task wt:test:api -- --testsuite X     # ...reuse it, seconds before the tests...
task wt:down                          # ...and stop it as soon as verification is done, before the PR
task wt:lint:admin && task wt:test:admin
task wt:lint:agent && task wt:test:agent
task wt:lint:ciqual && task wt:test:ciqual
```

Minimal, one shot, no published port, and a load guard that refuses to start when the
machine is busy — exit code 75 means "push and let CI run it", not "your change is
broken". Details: `agent-os/standards/global/worktree-checks.md`.

For a journey, or anything needing the whole system, bring up the e2e stack instead:
`task e2e:up && task e2e:seed && task e2e:smoke`, then `task e2e:down`
(`agent-os/standards/global/e2e-environment.md`).

## Definition of Done — check before saying a ticket is finished

Green lint and tests are the floor, not the bar. All five must hold:

- [ ] **Every unit touched has a unit or integration test** — MCP tool, endpoint, entity,
      service, admin component, mobile ViewModel, agent route. Minimum per unit:
    - MCP tool: no user bound, bad input (missing arg **and** unknown `action`), happy
      path asserting DB state, `assertMercureUpdatePublished()`,
      `assertElasticsearchIndexDispatched()` when indexed
    - endpoint: 401 unauthenticated, 400 bad input, then the same list
    - entity: Create/Update/Delete Mercure publication
    - admin component: render, the interaction it exists for, error/empty state
    - mobile ViewModel: one test per state transition (loading, success, error)
- [ ] **At least one e2e journey covers the feature**, new or extended. The stack exists
      (`task e2e:up`), the browser harness does not yet (MAG-97/98) — until then, a
      Given/When/Then `## E2E journey` section in the spec's `plan.md` plus the journey
      ticket that will carry it (MAG-99→MAG-103). Data the journey needs goes in
      `api/fixtures/e2e/` now, with its counts added to `E2eSeedCommandTest`. No
      user-facing behavior → write `E2E: N/A — <reason>` in `plan.md`.
- [ ] **A fixed bug has the test that reproduces it**, written red before the fix.
- [ ] **CI green on a PR** that links the ticket. One ticket = one branch = one PR.
- [ ] **Module functional spec and user guide updated in Linear** (ADR-006).

An exemption that isn't written down in `plan.md` is a missing test, not an exemption.

## Rules

- Never run test commands on host — always via `task` or Docker
- CI runs lint before tests (lint gates test jobs)
- `MESSENGER_TRANSPORT_DSN=sync://` in test env — no RabbitMQ needed
- No test interdependencies — each test sets up and cleans its own state
- Fix any failures before committing — do NOT push broken code

## Reference

For full details, read `agent-os/standards/global/testing.md`
