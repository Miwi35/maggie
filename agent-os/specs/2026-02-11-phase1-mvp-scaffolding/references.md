# Phase 1 MVP — References

## PsychedCMS Patterns

Source: `~/perso/psyched-cms/`

### Docker Infrastructure
- `docker-compose.yml` — Service definitions for php, nginx, node, database, mercure, rabbitmq
- `.docker/php/Dockerfile` — Multi-stage PHP-FPM build (base → dev → prod)
- `.docker/php/docker-entrypoint.sh` — Entrypoint with Composer install, migrations, fixtures
- `.docker/php/conf.d/app.ini` — PHP runtime config
- `.docker/php/conf.d/xdebug.ini` — Xdebug dev config
- `.docker/php/conf.d/opcache.prod.ini` — OPcache production config
- `.docker/nginx/Dockerfile` — Nginx with template-based config
- `.docker/nginx/templates/default.conf.template` — Path-based routing
- `.docker/node/Dockerfile` — Node.js dev server

### Configuration
- `.env` — Environment variables template
- `Taskfile.yml` — Task automation with sub-taskfiles
- `api/Taskfile.yml` — API-specific tasks
- `admin/Taskfile.yml` — Admin-specific tasks

### Symfony API
- `api/config/packages/api_platform.yaml` — API Platform config
- `api/config/packages/doctrine.yaml` — Doctrine ORM config
- `api/config/packages/mercure.yaml` — Mercure hub config
- `api/config/packages/messenger.yaml` — Symfony Messenger config
- `api/config/packages/nelmio_cors.yaml` — CORS config

### React Admin
- `admin/src/App.tsx` — HydraAdmin setup
- `admin/package.json` — Dependencies
- `admin/vite.config.ts` — Vite build config

## Hilo Project Patterns

Source: `~/perso/hilo/`

### Android Patterns
- Kotlin + Jetpack Compose + Material 3
- Ktor HTTP client for API communication
- Koin dependency injection
- MVVM architecture with ViewModels
- Navigation Compose for screen routing

## External References

### symfony/mcp-bundle
- GitHub: https://github.com/symfony/mcp-bundle
- `#[McpTool]` attribute for tool registration
- HTTP transport at configurable path
- Auto-discovery of tool classes

### Google Calendar API Data Model
- Event resource: summary, description, location, start, end, recurrence, reminders
- RRULE (RFC 5545) for recurrence patterns
- Exception instances via recurringEventId + originalStartTime

### simshaun/recurr
- PHP library for RRULE parsing and occurrence expansion
- Packagist: `simshaun/recurr`

### Python MCP SDK
- GitHub: https://github.com/modelcontextprotocol/python-sdk
- StreamableHTTP client transport
- Tool definition and execution
