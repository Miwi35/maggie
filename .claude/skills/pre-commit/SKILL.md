---
name: pre-commit
description: "Pre-commit lint and test rules, plus the definition of done. Use before committing code and before calling a ticket finished."
user-invocable: false
---

# Pre-Commit Rules

**Use when** about to commit, push, or call a ticket finished.

**Read** `agent-os/standards/global/testing.md` (Definition of Done, lint and test commands per component) and `agent-os/standards/global/worktree-checks.md`.

## Gotchas

- Before every commit: lint of every touched component and the **targeted** tests only; the full suites run in CI.
- In a worktree use `task fix:all` and `task wt:*`: the `task api:*` family tests the main checkout, and formatters would rewrite the owner's working copy.
- Exit code 75 means the machine is busy: push and let CI run it.
- Done means five things: tests for every unit touched, an e2e journey (or `E2E: N/A — <reason>` in `plan.md`), a red-first reproduction for a bug, green CI, docs updated.
- Fix failures before committing; never `--no-verify`.
