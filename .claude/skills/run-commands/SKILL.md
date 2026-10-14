---
name: run-commands
description: "Docker-first command enforcement. Use when running any development command, test, build, lint, or install. NEVER run PHP, Node, or Python directly on host."
user-invocable: false
---

# Docker-First Commands

**Use when** running any development command, test, build, lint or install.

**Read** `agent-os/standards/global/taskfile.md`; per component: `agent-os/standards/api/taskfile.md`, `agent-os/standards/admin/taskfile.md`.

## Gotchas

- Never run `composer`, `php`, `bin/console`, `symfony`, `npm`, `npx`, `node`, `python`, `pip`, `uv` or `pytest` on the host (its PHP is 8.1, the project needs 8.4+): use `task <namespace>:<command>`.
- Symfony console: `task api:console -- <args>`, the one passthrough; dependencies: `task api:require -- <pkg>`, `task admin:add -- <pkg>`.
- In a worktree, `task api:*`, `admin:*`, `agent:*` hit the main checkout: use `task fix:all` and `task wt:*`.
- Mobile locally is `task wt:test:mobile -- --tests …` only.
- `task test:all` and `task ci` are for CI; run targeted tests locally.
