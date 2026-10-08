# Taskfile

Uses [Task](https://taskfile.dev/) (v3) instead of Make.

## Enforcement Rule

**NEVER run runtime commands directly on the host.** All runtimes live in Docker containers.

Forbidden on host:
- `composer`, `php`, `bin/console`, `symfony`
- `npm`, `npx`, `node`
- `python`, `pip`, `uv`, `pytest`

Always use `task <namespace>:<command>` instead.

## Structure

- Root `Taskfile.yml` — Docker Compose, cross-service orchestration
- `api/Taskfile.yml` — PHP/Symfony (namespace: `api:`)
- `admin/Taskfile.yml` — Node/React (namespace: `admin:`)
- `agent/Taskfile.yml` — Python/FastAPI (namespace: `agent:`)
- `mobile/Taskfile.yml` — `mobile:install` (the owner's phone); agents run the unit tests with `task wt:test:mobile` (`wt/Taskfile.yml`)

## Naming

- `namespace:action` pattern: `api:install`, `admin:dev`, `agent:test`
- Infrastructure tasks: `up`, `down`, `build`, `ps`, `logs:{service}`, `shell:{service}`
- Each service provides: `install`, `test`, `lint`

## Passthrough

Only one passthrough allowed: `task api:console -- <args>`

All other tasks must be explicit (no `CLI_ARGS` catch-alls).
Exception: `task api:require -- <pkg>` and `task admin:add -- <pkg>` for adding deps.

## Quick Reference

| Action | Command |
|---|---|
| Start all | `task up` |
| Start + dev | `task up:dev` |
| Full setup | `task setup` |
| Stop | `task down` |
| Shell into PHP | `task shell:php` |
| Shell into Node | `task shell:node` |
| Shell into Agent | `task shell:agent` |
| Run all tests | `task test:all` |
