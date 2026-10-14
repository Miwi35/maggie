# Taskfile

Uses [Task](https://taskfile.dev/) (v3) instead of Make.

## Enforcement Rule

**NEVER run runtime commands directly on the host.** All runtimes live in Docker containers (the host's PHP is 8.1, the project needs 8.4+).

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
| Run all linters | `task lint:all` (API + Admin + Agent, not mobile) |
| Open a psql shell | `task shell:db` (psql) |
| Build all images | `task build` |

`task test:all` and `task ci` run the full suites: CI does that on every PR, locally run only the targeted tests ([global/testing](testing.md)).

## Agent (`task agent:*`, Python)

| Task | What it does |
|---|---|
| `task agent:test` | pytest |
| `task agent:lint` | `ruff check` |
| `task agent:lint:fix` | `ruff check --fix` |
| `task agent:format` / `agent:format:check` | `ruff format` / `ruff format --check` (CI mode) |
| `task agent:install` | `uv pip install` |

The API and Admin references are in [api/taskfile](../api/taskfile.md) and [admin/taskfile](../admin/taskfile.md); PHPStan runs at level 6.

## Mobile

`task wt:test:mobile -- --tests 'com.maggie.app.ui.screens.chat.*'` — JVM unit tests, one Gradle build at a time on the machine. Locally this is the only mobile command: never `./gradlew` by hand, never `assemble*` or `lint*`, never Maestro or an emulator (except `task e2e:mobile` to write or debug a journey). CI does the rest.

## In a git worktree

`task api:*`, `task admin:*`, `task agent:*` and `task ciqual:*` run `docker compose exec` against the dev stack, which mounts the main checkout, not the worktree: they would test the wrong code and the formatters would rewrite the owner's working copy. Use `task fix:all` and `task wt:*` instead — see [global/worktree-checks](worktree-checks.md).
