# Production Build — Maggie v3

## Overview

Three Docker images are built for production, all using multi-stage Dockerfiles:
- **PHP** (`ghcr.io/miwi35/maggie-php`) — Symfony API + MCP server
- **Nginx** (`ghcr.io/miwi35/maggie-nginx`) — Reverse proxy + compiled React admin SPA + API static assets
- **Agent** (`ghcr.io/miwi35/maggie-agent`) — Python FastAPI agent hub

Registry: GitHub Container Registry (GHCR)
Tags: `:<git-sha>` + `:latest`

---

## PHP Image (`.docker/php/Dockerfile`, target: `prod`)

### Build Stages
1. **base** — PHP 8.4-fpm-alpine with system packages + PHP extensions
2. **dev** — Adds Xdebug, uses php.ini-development
3. **prod** — Production-optimized

### Production Stage Details
- Uses `php.ini-production`
- Loads OPcache production config (`.docker/php/conf.d/opcache.prod.ini`)
- Copies application source (chown 1000:1000)
- Runs `composer install --no-dev --no-scripts --no-progress --optimize-autoloader`
- Runs Composer post-install scripts + `cache:warmup`
- Health check: FastCGI ping every 30s (60s start period)
- Non-root user: `app`

### System Packages & PHP Extensions
- **Packages:** acl, fcgi, git, curl, libpng, libjpeg, freetype, libzip, icu, postgresql, rabbitmq-c, su-exec
- **PHP Extensions:** pdo, pdo_pgsql, pgsql, gd, intl, zip, opcache, bcmath
- **PECL:** apcu, amqp

### PHP Production Config (`.docker/php/conf.d/app.ini`)
```ini
memory_limit = 256M
max_execution_time = 60
upload_max_filesize = 64M
post_max_size = 64M
realpath_cache_size = 4096K
realpath_cache_ttl = 600
session.use_strict_mode = 1
session.cookie_secure = 1
session.cookie_httponly = 1
session.cookie_samesite = Lax
date.timezone = UTC
expose_php = Off
apc.enable_cli = 1
```

### OPcache Production Config (`.docker/php/conf.d/opcache.prod.ini`)
```ini
opcache.enable = 1
opcache.memory_consumption = 256
opcache.interned_strings_buffer = 16
opcache.max_accelerated_files = 20000
opcache.validate_timestamps = 0    # No file change checking
opcache.revalidate_freq = 0
```

---

## Nginx Image (`.docker/nginx/Dockerfile`, target: `prod`)

### Build Stages
1. **dev** — nginx:alpine with config templates
2. **admin-build** — node:22-alpine, builds React admin SPA
3. **prod** — nginx:alpine with compiled assets

### Admin Build Stage
- Copies `admin/package.json` + `package-lock.json` → `npm ci`
- Sets `VITE_API_URL=/api`
- Runs `npm run build` → output in `/build/dist/`

### Production Stage
- Copies nginx config templates from `.docker/nginx/templates/`
- Copies compiled admin SPA from admin-build stage → `/var/www/admin/`
- Copies API public directory from PHP source image → `/var/www/api/public/`
- Requires PHP image as build arg: `--build-arg PHP_IMAGE=ghcr.io/miwi35/maggie-php:latest`

### Nginx Routing (`.docker/nginx/templates/default.conf.template`)
| Path | Target |
|------|--------|
| `/` | 302 redirect → `/admin/` |
| `/admin/` | React SPA (alias `/var/www/admin/`, try_files → index.html) |
| `/api` | Symfony API (FastCGI to php:9000) |
| `/bundles` | Symfony static assets |
| `/_mcp` | MCP Server (FastCGI to php:9000) |

### Nginx Features
- Gzip compression (text, css, json, js)
- Security headers: X-Frame-Options, X-Content-Type-Options, X-XSS-Protection
- FastCGI buffer: 128k / 4×256k

---

## Agent Image (`.docker/python/Dockerfile`, target: `prod`)

### Build Stages
1. **base** — python:3.12-slim with uv package manager
2. **dev** — Installs with `uv pip install --system -e '.[dev]'`, runs with `--reload`
3. **prod** — Production-optimized

### Production Stage Details
- Copies agent source (chown app:app)
- Runs `uv pip install --system .` (no dev deps)
- Non-root user: `app`
- Health check: curl to `/agent/docs` every 30s (30s start period)
- Command: `uvicorn app.main:app --host 0.0.0.0 --port 8001 --workers 2`

---

## Local Build Commands (Taskfile.yml)

```bash
# Build all production images locally
task deploy:build

# Tag and push to GHCR
task deploy:push
```

- `deploy:build` builds PHP, Agent, Nginx images with `:latest` tag
- `deploy:push` tags with short git SHA + `:latest`, pushes to `ghcr.io/miwi35/maggie-*`

---

## Admin Build (Vite)

**Config:** `admin/vite.config.ts`
```typescript
export default defineConfig({
  plugins: [react()],
  base: '/admin/',    // Production base path
})
```

- Build command: `npm run build` (tsc -b && vite build)
- Output: `admin/dist/`
- Built inside Nginx Dockerfile during image build (not a separate image)

---

## Mobile Build (Android)

**Config:** `mobile/app/build.gradle.kts`

### Product Flavors
| Flavor | API URL | Mercure URL | App ID Suffix |
|--------|---------|-------------|---------------|
| dev | `http://10.0.2.2` | `http://10.0.2.2/.well-known/mercure` | `.dev` |
| prod | `https://maggieai.fr` | `https://maggieai.fr/.well-known/mercure` | (none) |

### Signing
- Keystore: `../release.keystore`
- Key alias: `maggie`
- Release build: signed, minifyEnabled=false
- SDK: compileSdk 35, minSdk 29, targetSdk 35

### CI
- GitHub Actions workflow: the `mobile-unit` job of `.github/workflows/ci.yml`
- Trigger: push/PR with changes to `mobile/**`
- Runs: `./gradlew :app:testDebugUnitTest` (Java 17)
