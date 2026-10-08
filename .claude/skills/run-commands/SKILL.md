---
name: run-commands
description: "Docker-first command enforcement. Use when running any development command, test, build, lint, or install. NEVER run PHP, Node, or Python directly on host."
user-invocable: false
---

# Docker-First Command Enforcement

**NEVER run runtime commands directly on the host.** Host PHP is 8.1, project needs 8.4+. All runtimes live in Docker containers.

## Forbidden on host

`composer`, `php`, `bin/console`, `symfony`, `npm`, `npx`, `node`, `python`, `pip`, `uv`, `pytest`

## Always use `task` commands

### API (Symfony)
```
task api:test                    # PHPUnit
task api:test -- --filter=MyTest # Specific test
task api:lint                    # PHPStan (level 6)
task api:console -- <command>    # Symfony console
task api:install                 # composer install
task api:require -- <pkg>        # composer require
task api:update                  # composer update
```

### Admin (React)
```
task admin:test       # Vitest
task admin:lint       # ESLint
task admin:typecheck  # tsc --noEmit
task admin:dev        # Vite dev server
task admin:build      # Production build
task admin:add -- <pkg>  # npm install
```

### Agent (Python)
```
task agent:test          # pytest
task agent:lint          # ruff check
task agent:lint:fix      # ruff check --fix
task agent:format        # ruff format
task agent:format:check  # ruff format --check
task agent:install       # uv pip install
```

### Mobile
```
task wt:test:mobile -- --tests 'com.maggie.app.ui.screens.chat.*'   # JVM unit tests, one Gradle build at a time
```
Locally, mobile = `task wt:test:mobile -- --tests …` only: never `./gradlew` by hand, never `assemble*` or `lint*`, never Maestro or an emulator (except `task e2e:mobile` to write or debug a journey). CI does the rest.

### Infrastructure
```
task up              # Start all containers
task up:dev          # Start all + Node dev server
task down            # Stop all
task build           # Build all images
task shell:php       # Shell into PHP container
task shell:node      # Shell into Node container
task shell:agent     # Shell into Agent container
task shell:db        # Open psql shell
task test:all        # Run all tests (API + Admin + Agent)
task lint:all        # Run all linters
task ci              # Full CI locally
```

## Reference

For full details, read `agent-os/standards/global/taskfile.md`
