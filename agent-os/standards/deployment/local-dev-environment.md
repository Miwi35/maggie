# Local Development Environment — Maggie v3

## Prerequisites

### Host Machine Requirements
| Tool | Required | Notes |
|------|----------|-------|
| Docker Engine + Compose | v24+ / v2.20+ | Traefik v3.6 needs Docker API v1.44+ |
| Task CLI | any | `go install github.com/go-task/task/v3/cmd/task@latest` or package manager |
| Git | any | Version control |
| Android Studio | optional | Only for mobile development — JBR Java 21 at `/opt/android-studio-for-platform/jbr`, SDK at `~/Android/Sdk` |

**No host-level PHP, Node, or Python needed** — everything runs in Docker containers.

---

## First-Time Setup

```bash
# 1. Add local domain to /etc/hosts
task hosts:add     # runs: sudo sh -c 'echo "127.0.0.1 maggie.local" >> /etc/hosts'

# 2. Full project setup (build images, start containers, install deps, migrate DB)
task setup

# 3. Open in browser
task open          # opens http://maggie.local
```

`task setup` performs:
1. Checks if `maggie.local` is in `/etc/hosts`
2. Builds all Docker images
3. Starts all containers (with `--profile dev` for Node)
4. Installs Composer + npm dependencies
5. Runs database migrations

---

## Docker Compose Services (`docker-compose.yml`)

**Project name:** `maggie`
**Network:** `maggie` (bridge)

| Service | Image / Build | Port | Profile | Healthcheck |
|---------|---------------|------|---------|-------------|
| traefik | `traefik:v3.6` | 80, 8080 (dashboard) | default | — |
| php | `.docker/php/Dockerfile` target: dev | 9000 (internal) | default | — |
| nginx | `.docker/nginx/Dockerfile` target: dev | 80 (internal) | default | — |
| node | `.docker/node/Dockerfile` | 5173 (internal) | **dev** | — |
| database | `postgres:17-alpine` | 5432 | default | `pg_isready` |
| mercure | `dunglas/mercure` | 80 (internal) | default | — |
| rabbitmq | `rabbitmq:3-management-alpine` | 5672, 15672 (mgmt UI) | default | `rabbitmq-diagnostics ping` |
| agent | `.docker/python/Dockerfile` target: dev | 8001 (internal) | default | — |

### Dependencies
- **php** depends on: database (healthy), rabbitmq (healthy)
- **agent** depends on: nginx

### Volumes
| Volume | Mount | Purpose |
|--------|-------|---------|
| `./api` → `/var/www/api` | php container | Live PHP source (cached) |
| `./api/public` → `/var/www/api/public` | nginx container | API static assets (ro) |
| `./admin/dist` → `/var/www/admin` | nginx container | Built admin SPA (ro) |
| `./admin` → `/var/www/admin` | node container | Live admin source (cached) |
| `./agent` → `/app` | agent container | Live Python source (cached) |
| `postgres_data` | database | DB persistence |
| `rabbitmq_data` | rabbitmq | Queue persistence |

### Node Service (dev profile)
The Node container runs Vite dev server with HMR and is **only started** when using `--profile dev`:
```bash
task up:dev    # starts all services including Node
task up        # starts all services EXCEPT Node
```

---

## Traefik Routing (`maggie.local`)

All services are accessed through Traefik on port 80 at `http://maggie.local`.

| Path | Routed To | Service |
|------|-----------|---------|
| `/` | 302 → `/admin/` | redirect middleware |
| `/admin/*` | node:5173 (dev) or nginx:80 (built) | React SPA |
| `/api/*` | nginx → php:9000 | Symfony API |
| `/_mcp` | nginx → php:9000 | MCP Server |
| `/bundles/*` | nginx:80 | Symfony static assets |
| `/agent/*` | agent:8001 | Python FastAPI |
| `/.well-known/mercure` | mercure:80 | Real-time hub |

**Dashboard:** `http://maggie.local:8080`

Router names are prefixed `maggie-*` to avoid conflicts with other projects sharing the Docker socket.

---

## Environment Variables (`.env`)

```bash
# === Compose ===
COMPOSE_PROJECT_NAME=maggie
UID=1001
GID=1001

# === Versions ===
PHP_VERSION=8.4
NODE_VERSION=20
POSTGRES_VERSION=17
PYTHON_VERSION=3.12

# === Symfony ===
APP_ENV=dev
APP_DEBUG=1
APP_SECRET=!ChangeMe!
CORS_ALLOW_ORIGIN='^https?://(localhost|127\.0\.0\.1|maggie\.local)(:[0-9]+)?$'

# === Domain ===
APP_DOMAIN=maggie.local
NGINX_PORT=80
TRAEFIK_DASHBOARD_PORT=8080

# === Database ===
POSTGRES_USER=maggie
POSTGRES_PASSWORD=maggie
POSTGRES_DB=maggie
DATABASE_URL=postgresql://maggie:maggie@database:5432/maggie?serverVersion=17

# === Mercure ===
MERCURE_URL=http://mercure/.well-known/mercure
MERCURE_PUBLIC_URL=http://${APP_DOMAIN}/.well-known/mercure
MERCURE_JWT_SECRET=!ChangeThisMercureHubJWTSecretKey!

# === RabbitMQ ===
RABBITMQ_USER=guest
RABBITMQ_PASSWORD=guest
MESSENGER_TRANSPORT_DSN=amqp://guest:guest@rabbitmq:5672/%2f/messages

# === Admin ===
VITE_API_URL=http://${APP_DOMAIN}/api

# === Agent ===
AGENT_HUB_URL=http://agent:8001
ANTHROPIC_API_KEY=sk-ant-api03-...   # Real key needed for agent to work
ANTHROPIC_MODEL=claude-haiku-4-5-20251001
MCP_SERVER_URL=http://nginx/_mcp
```

### Additional Env Files
| File | Purpose |
|------|---------|
| `api/.env` | Base Symfony config (git-tracked defaults) |
| `api/.env.dev` | Dev-specific overrides |
| `api/.env.test` | Test env: `maggie_test` DB, `sync://` messenger |

---

## Taskfile Commands Reference

### Docker Lifecycle
```bash
task up              # Start all containers
task up:dev          # Start all + Node dev server
task down            # Stop all containers
task down:volumes    # Stop + remove volumes (destructive)
task restart         # Restart containers
task build           # Build all images
task build:nocache   # Build without cache
task ps              # Show running containers
task logs            # Tail all logs
task logs:php        # Tail PHP logs
task logs:nginx      # Tail Nginx logs
task logs:agent      # Tail Agent logs
task logs:traefik    # Tail Traefik logs
```

### Shell Access
```bash
task shell:php       # Open shell in PHP container
task shell:node      # Open shell in Node container
task shell:agent     # Open shell in Agent container
task shell:db        # Open psql shell in database
```

### Installation
```bash
task install         # Install all deps (composer + npm)
task api:install     # composer install --prefer-source
task admin:install   # npm install
task agent:install   # uv pip install --system -e ".[dev]"
```

### API (Symfony) — `task api:*`
```bash
task api:console -- <args>      # Run any Symfony console command
task api:test                   # Run PHPUnit tests
task api:test:coverage          # PHPUnit with HTML coverage report
task api:phpstan                # Run PHPStan static analysis
task api:lint                   # Alias for phpstan
task api:require                # composer require <package>
task api:update                 # composer update
task api:validate               # composer validate
task api:dump-autoload          # Regenerate autoloader
```

### Admin (React) — `task admin:*`
```bash
task admin:dev         # Start Vite dev server
task admin:build       # Production build (tsc + vite)
task admin:test        # Run Vitest tests
task admin:lint        # Run ESLint
task admin:typecheck   # TypeScript type checking (tsc --noEmit)
task admin:install     # npm install
task admin:update      # npm update
task admin:add         # npm install <package>
```

### Agent (Python) — `task agent:*`
```bash
task agent:dev           # Start uvicorn with reload
task agent:test          # Run pytest
task agent:lint          # ruff check app/
task agent:lint:fix      # ruff check --fix app/
task agent:format        # ruff format app/
task agent:format:check  # ruff format --check app/
task agent:install       # uv pip install --system -e ".[dev]"
```

### Testing & CI
```bash
task test:all          # Run all tests (API + Admin + Agent)
task lint:all          # Run all linters (PHPStan + Ruff + ESLint + tsc)
task ci                # Full CI locally: lint:all then test:all
task test:integration  # End-to-end integration tests (scripts/test-integration.sh)
```

### Deployment (local build)
```bash
task deploy:build      # Build production Docker images locally
task deploy:push       # Tag + push to GHCR
```

---

## Dev Dockerfiles Detail

### PHP Dev (`.docker/php/Dockerfile`, target: `dev`)

Base: `php:8.4-fpm-alpine`

**System packages:** acl, fcgi, git, curl, libpng, libjpeg, freetype, libzip, icu, postgresql, rabbitmq-c, su-exec

**PHP extensions:** pdo, pdo_pgsql, pgsql, gd (freetype+jpeg), intl, zip, opcache, bcmath, apcu (pecl), amqp (pecl)

**Xdebug installed** — config in `.docker/php/conf.d/xdebug.ini`:
```ini
xdebug.mode = develop,debug
xdebug.client_host = host.docker.internal
xdebug.client_port = 9003
xdebug.start_with_request = trigger
xdebug.idekey = PHPSTORM
xdebug.log_level = 0
```

Xdebug is **trigger-based** — only activates when request includes `XDEBUG_TRIGGER` header/cookie. Set IDE key to `PHPSTORM`.

**Non-root user:** `app` (UID/GID from build args, default 1001)
**Entrypoint:** `docker-entrypoint.sh` (permission setup)
**PHP config:** `.docker/php/conf.d/app.ini` (256M memory, 60s timeout, secure session cookies)

### Nginx Dev (`.docker/nginx/Dockerfile`, target: `dev`)

Base: `nginx:alpine`

Source volumes provide API public files and admin dist. Config via templates in `.docker/nginx/templates/`.

**Routing:** See Nginx section below.

### Node Dev (`.docker/node/Dockerfile`)

Base: `node:20-alpine`
**User:** `app` (non-root, UID/GID matched)
**Port:** 5173 (Vite HMR)
**Command:** `npm run dev -- --host 0.0.0.0`

### Agent Dev (`.docker/python/Dockerfile`, target: `dev`)

Base: `python:3.12-slim`
**Package manager:** `uv` (from `ghcr.io/astral-sh/uv:latest`)
**User:** `app` (non-root, site-packages writable)
**Port:** 8001
**Command:** `uvicorn app.main:app --host 0.0.0.0 --port 8001 --reload`

Auto-reload on file changes via uvicorn `--reload`.

---

## Nginx Configuration (`.docker/nginx/templates/default.conf.template`)

**Root:** `/var/www/api/public`

| Location | Target | Notes |
|----------|--------|-------|
| `/` | 302 → `/admin/` | Root redirect |
| `/admin/` | alias `/var/www/admin/` | SPA fallback: try_files → index.html |
| `/api` | FastCGI → php:9000 | Symfony front controller |
| `/bundles` | static files | 404 if not found |
| `/_mcp` | FastCGI → php:9000 | MCP server endpoint |
| `~ \.php$` | return 404 | Block direct PHP access (except index.php) |

**Features:**
- Gzip: text, css, json, js, xml (min 1000 bytes)
- Security headers: X-Frame-Options, X-Content-Type-Options, X-XSS-Protection
- FastCGI buffers: 128k / 4×256k

---

## PHP / Symfony Configuration

### PHPStan (`api/phpstan.neon`)
- Level: **6** (strict)
- Paths: `src/`, `modules/calendar/src/`, `modules/core/src/`
- Extensions: Doctrine, Symfony
- Excludes: `src/Kernel.php`

### Composer Autoload (`api/composer.json`)
```
App\          → src/
Maggie\Calendar\ → modules/calendar/src/
Maggie\Core\    → modules/core/src/
```

### Doctrine Config (`api/config/packages/doctrine.yaml`)
- Driver: PostgreSQL
- Test env: appends `_test` prefix to DB name
- Dev: auto-generate proxy classes
- Naming: underscore_number_aware
- Identity: PostgreSQL native ULID

### Messenger Config (`api/config/packages/messenger.yaml`)
- Transport `async`: RabbitMQ (via `MESSENGER_TRANSPORT_DSN`)
- Transport `failed`: Doctrine-backed dead letter queue
- Transport `sync`: In-process
- All calendar commands routed to `sync` (not async yet)
- Test env: `async` overridden to `in-memory://`

---

## Admin Configuration

### Vite (`admin/vite.config.ts`)
```typescript
export default defineConfig({
  plugins: [react()],
  base: '/admin/',
  server: {
    allowedHosts: ['maggie.local'],
  },
  test: {
    globals: true,
    environment: 'jsdom',
    include: ['src/**/*.{test,spec}.{ts,tsx}'],
    setupFiles: ['src/test/setup.ts'],
  },
})
```

### TypeScript (`admin/tsconfig.json`)
- Target: ES2022, JSX: react-jsx
- Strict mode, module resolution: bundler
- No unused locals/parameters

### Key Dependencies
- react 19.2, react-admin 5.14, @api-platform/admin 4.0.8
- @fullcalendar/* 6.1.20, rrule 2.8.1
- Localization: ra-i18n-polyglot, ra-language-french

---

## Agent Configuration

### pyproject.toml (`agent/pyproject.toml`)
```toml
[tool.pytest.ini_options]
testpaths = ["tests"]
asyncio_mode = "auto"
pythonpath = ["."]

[tool.ruff]
target-version = "py312"
line-length = 120

[tool.ruff.lint]
select = ["E", "F", "W", "I", "UP", "B", "SIM", "RUF"]
```

### Key Dependencies
- fastapi >=0.115, uvicorn[standard] >=0.34
- anthropic >=0.42 (Claude SDK)
- mcp >=1.0 (MCP client)
- httpx + httpx-sse, pydantic-settings, pyjwt
- Dev: pytest, pytest-asyncio, pytest-httpx, respx, ruff

### Agent Structure
```
agent/app/
├── main.py           # FastAPI entry (root_path: /agent)
├── config.py         # pydantic-settings configuration
├── api/              # Maggie API client
├── llm/              # Claude LLM orchestration
├── mcp/              # MCP client integration
├── memory/           # Agent memory/context
├── mercure/          # Real-time event publishing
└── personality/      # Agent personality config
```

---

## Mobile (Android) Configuration

### Gradle (`mobile/app/build.gradle.kts`)
- compileSdk: 35, minSdk: 29, targetSdk: 35
- JVM target: 17
- versionCode: 1, versionName: "0.1.0"

### Product Flavors
| Flavor | API URL | Mercure URL | App ID suffix |
|--------|---------|-------------|---------------|
| dev | `http://10.0.2.2` | `http://10.0.2.2/.well-known/mercure` | `.dev` |
| prod | `https://maggieai.fr` | `https://maggieai.fr/.well-known/mercure` | — |

`10.0.2.2` is the Android emulator's alias for the host machine's localhost.

### Signing
- Keystore: `../release.keystore` (relative to `app/`)
- Config in `keystore.properties`
- **Release SHA1:** `C5:AD:BF:94:9F:08:68:09:44:78:F1:F0:8B:A2:41:CA:B1:78:4C:DA` (registered in GCP for Google OAuth)
- **Always use `prodRelease` for device testing** — debug keystore SHA1 is NOT registered in Google Cloud Console

### Key Dependencies
- Compose UI + Material 3, Navigation Compose
- Ktor (HTTP client + SSE + JSON serialization)
- Room (local database with KSP)
- Koin (DI)
- Testing: JUnit, MockK, Turbine, Coroutines test

---

## Testing

### API Tests (PHPUnit)
```bash
task api:test                    # Run all tests
task api:test -- --filter=MyTest # Run specific test
task api:test:coverage           # Generate HTML coverage report
```
- Test database: `maggie_test` (configured in `api/.env.test`)
- Messenger transport: `sync://` (no RabbitMQ in tests)
- Fixtures: `api/src/DataFixtures/`

### Admin Tests (Vitest)
```bash
task admin:test                  # Run all tests
```
- Environment: jsdom
- Setup: `admin/src/test/setup.ts`
- Pattern: `src/**/*.{test,spec}.{ts,tsx}`

### Agent Tests (pytest)
```bash
task agent:test                  # Run all tests
task agent:test -- -k test_name  # Run specific test
```
- Async mode: auto (pytest-asyncio)
- HTTP mocking: respx + pytest-httpx

### Integration Tests
```bash
task test:integration
# or directly:
BASE_URL=http://maggie.local scripts/test-integration.sh
```
Validates all 7 services end-to-end: Docker status, Traefik, API, Event CRUD, MCP handshake, Agent chat, Mercure hub, Admin SPA, root redirect. **18 assertions.**

### Full CI (local)
```bash
task ci     # lint:all then test:all
```

---

## IDE Configuration

### EditorConfig (`.editorconfig`)
```ini
[*]
charset = utf-8
end_of_line = lf
indent_size = 4
indent_style = space
insert_final_newline = true
trim_trailing_whitespace = true

[{compose.yaml,compose.*.yaml}]
indent_size = 2

[*.md]
trim_trailing_whitespace = false
```

### PhpStorm / IntelliJ
- Xdebug: IDE key `PHPSTORM`, port 9003, trigger-based
- PHP interpreter: Docker PHP-FPM container
- PHPStan: use `api/phpstan.neon`
- Symfony plugin recommended

### VS Code
- Extensions: PHP Intelephense, Xdebug, Symfony, ESLint, Prettier, Python, Ruff

---

## Git Hooks

No pre-commit hooks configured. Linting runs in CI (GitHub Actions).

Run locally before push:
```bash
task lint:all    # PHPStan + Ruff + ESLint + tsc
task ci          # Full lint + test
```

---

## Troubleshooting

### Host PHP version mismatch
Host has PHP 8.1 but project needs 8.4+. **Never run `composer`, `php`, or `bin/console` on host.** Always use `task api:*` commands.

### Xdebug not triggering
- Ensure request includes `XDEBUG_TRIGGER=1` header or cookie
- Check `host.docker.internal` resolves (Linux may need `--add-host` or `extra_hosts`)
- IDE listening on port 9003

### Mercure health check
No `/healthz` endpoint. Test with:
```bash
curl http://maggie.local/.well-known/mercure
```

### Integration tests fail on DNS
If `maggie.local` doesn't resolve:
```bash
BASE_URL=http://127.0.0.1 task test:integration
```

### API Platform collection format
API Platform 4 uses `member` key (not `hydra:member`) in collection responses.

### Admin trailing slash
Admin SPA served at `/admin/` — trailing slash required. Root `/` redirects automatically.

### Traefik v3.6+ required
Traefik v3.3 Docker client is incompatible with Docker 29+ API v1.44+.

### Database reset
```bash
task down:volumes                                         # Remove all volumes
task up:dev                                               # Restart
task api:console -- doctrine:migrations:migrate --no-interaction  # Recreate schema
```
