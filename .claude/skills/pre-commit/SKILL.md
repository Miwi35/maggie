---
name: pre-commit
description: "Pre-commit lint and test rules. Use before committing code to ensure all changed components pass lint and tests."
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
| Mobile | `cd mobile && ./gradlew lintProdRelease` | `cd mobile && ./gradlew testProdReleaseUnitTest` |

## Multi-Component Shortcut

```
task lint:all && task test:all
```

This runs API + Admin + Agent lint and tests (excludes mobile).

## Test Pyramid

| Layer | What | Runs in CI |
|-------|------|------------|
| Unit | Pure logic, mocked deps | Always |
| Integration | Real DB, real DI container | With service containers |
| API/HTTP | Full request cycle | With service containers |

## Rules

- Never run test commands on host — always via `task` or Docker
- CI runs lint before tests (lint gates test jobs)
- `MESSENGER_TRANSPORT_DSN=sync://` in test env — no RabbitMQ needed
- No test interdependencies — each test sets up and cleans its own state
- Fix any failures before committing — do NOT push broken code

## Reference

For full details, read `agent-os/standards/global/testing.md`
