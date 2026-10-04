# Server Setup — Maggie v3

## Production Domain

**Domain:** `maggieai.fr`
**Protocol:** HTTPS (TLS via Let's Encrypt)

---

## VPS Architecture

```
                    ┌─────────────────────────────────────┐
                    │              VPS                      │
                    │                                       │
Internet ──443──►   │  Traefik (v3.6+)                     │
                    │    ├─ maggieai.fr → nginx:80          │
                    │    ├─ maggieai.fr/.well-known/mercure │
                    │    │    → mercure:80                   │
                    │    ├─ maggieai.fr/agent → agent:8001  │
                    │    └─ traefik.maggieai.fr → dashboard │
                    │                                       │
                    │  ┌──── internal network ────────┐    │
                    │  │ nginx ──► php-fpm:9000        │    │
                    │  │ php ──► database:5432         │    │
                    │  │ php ──► rabbitmq:5672         │    │
                    │  │ php ──► mercure:80            │    │
                    │  │ agent ──► nginx (MCP)         │    │
                    │  └──────────────────────────────┘    │
                    └─────────────────────────────────────┘
```

---

## Traefik Setup

**Location on VPS:** `/opt/traefik/`
**Config source:** `infra/traefik/docker-compose.yml`

### Traefik Configuration
- Image: `traefik:v3.6`
- Entrypoints:
  - `web` (port 80) — HTTP with automatic redirect to HTTPS
  - `websecure` (port 443) — HTTPS
- Docker provider: auto-discovers services via labels
- Let's Encrypt ACME via HTTP challenge
- Certificate storage: `/letsencrypt/acme.json` (persistent volume)

### Traefik Environment Variables (`/opt/traefik/.env`)
```bash
ACME_EMAIL=your-email@example.com
DASHBOARD_AUTH=user:$(htpasswd -nB user)  # Basic auth for dashboard
```

### Dashboard
- URL: `traefik.maggieai.fr`
- Protected by basic auth

---

## VPS Setup Procedure

**Script:** `infra/setup-vps.sh`

### Prerequisites
- Docker and Docker Compose on the VPS (Traefik runs in Compose; Maggie itself runs on k3s)
- SSH access configured

### Steps

1. **Create shared Docker network:**
   ```bash
   docker network create traefik-public
   ```

2. **Create directories:**
   ```bash
   mkdir -p /opt/traefik /opt/maggie
   ```

3. **Deploy Traefik:**
   - Copy `infra/traefik/docker-compose.yml` → `/opt/traefik/`
   - Create `/opt/traefik/.env` with ACME_EMAIL and DASHBOARD_AUTH
   - Start: `cd /opt/traefik && docker compose up -d`

4. **Configure Maggie:**
   - Copy `.env.prod.example` → `/opt/maggie/.env.prod`
   - Fill in all secrets (see Environment Variables below)

5. **Configure GitHub Actions Secrets:**
   - `VPS_HOST` — VPS IP or domain
   - `VPS_USER` — Deploy user
   - `VPS_SSH_KEY` — Private SSH key

6. **First deployment:**
   - Push to main, CI passes, approve CD deploy
   - Or manually, once `infra/k8s/` and `infra/scripts/deploy-k3s.sh` are in
     `/opt/maggie/`:
     ```bash
     sudo k3s kubectl create namespace maggie
     sudo k3s kubectl apply -f /opt/maggie/infra/k8s/secrets.yaml   # from secrets.example.yaml
     /opt/maggie/infra/scripts/deploy-k3s.sh <image-tag>
     ```

---

## Production Workloads (k3s, namespace `maggie`)

Manifests live in `infra/k8s/` and are applied with Kustomize. Shared services
(postgres, elasticsearch, rabbitmq) run in the `shared` namespace and are
reused by other projects on the VPS.

### Deployments (namespace `maggie`)

| Deployment | Image | Port |
|---------|-------|------|
| php | `ghcr.io/miwi35/maggie-php:${IMAGE_TAG}` | 9000 |
| nginx | `ghcr.io/miwi35/maggie-nginx:${IMAGE_TAG}` | 80 |
| agent | `ghcr.io/miwi35/maggie-agent:${IMAGE_TAG}` | 8001 |
| ciqual | `ghcr.io/miwi35/maggie-ciqual:${IMAGE_TAG}` | 8002 |
| worker | `maggie-php` (messenger consumer) | — |
| cron | `maggie-php` (scheduled commands) | — |
| mercure | `dunglas/mercure:v1.0.2` (pinned by digest) | 80 |

The cron pod runs `supercronic /etc/maggie/crontab` (from `.docker/php/crontab`) as
uid 1000, never as root and never with a redirection to `/proc/1/fd/1` (refused to a
non-root user: the job would silently never start, MAG-147). Each job's output and exit
status go to `kubectl logs deploy/cron`; `maggie:google-calendar:check-sync` runs every
15 min there and fails when a Google agenda was not synced for more than an hour.
`maggie:notification:check-reminders` runs every minute (a reminder is minute-precise): it is
the one job allowed to share a start minute with another, and the 384Mi limit counts it.
Test of the scheduler: `infra/scripts/tests/cron-image.test.sh` (CI job `cron-image`).

Postgres, Elasticsearch and RabbitMQ live in the `shared` namespace and are
shared with the other projects on the VPS.

### Ingress (routing rules)

Single `maggie` Ingress on `maggieai.fr`, TLS through the `letsencrypt`
cert resolver:

| Path | Service |
|--------|-------------|
| `/agent` | agent:8001 |
| `/ciqual` | ciqual:8002 |
| `/.well-known/mercure` | mercure:80 |
| `/` | nginx:80 (API, `/admin`, `/_mcp`) |

All routers:
- Entrypoint: `websecure` (HTTPS)
- TLS resolver: `letsencrypt`

### Networks
- **internal**: bridge, for inter-service communication
- **traefik-public**: external, shared with Traefik reverse proxy

### Volumes
- `postgres_data` — PostgreSQL data persistence
- `rabbitmq_data` — RabbitMQ data persistence

### Dependencies
- php depends on: database (healthy), rabbitmq (healthy)
- agent depends on: nginx

### Health Checks
- **database:** `pg_isready`
- **rabbitmq:** `rabbitmq-diagnostics ping`
- **php:** FastCGI ping every 30s
- **agent:** curl `/agent/docs` every 30s

---

## Environment Variables (`.env.prod`)

**Template:** `.env.prod.example`
**Location on VPS:** `/opt/maggie/.env.prod`

```bash
# === Symfony ===
APP_SECRET=<random-string>
TRUSTED_PROXIES=REMOTE_ADDR

# === Database ===
POSTGRES_USER=maggie
POSTGRES_PASSWORD=<strong-password>
POSTGRES_DB=maggie
DATABASE_URL=postgresql://${POSTGRES_USER}:${POSTGRES_PASSWORD}@database:5432/${POSTGRES_DB}?serverVersion=17

# === Mercure ===
MERCURE_URL=http://mercure/.well-known/mercure
MERCURE_PUBLIC_URL=https://maggieai.fr/.well-known/mercure
MERCURE_JWT_SECRET=<jwt-secret>

# === CORS ===
CORS_ALLOW_ORIGIN='^https://maggieai\.fr$'

# === RabbitMQ ===
RABBITMQ_DEFAULT_USER=maggie
RABBITMQ_DEFAULT_PASS=<strong-password>
MESSENGER_TRANSPORT_DSN=amqp://${RABBITMQ_DEFAULT_USER}:${RABBITMQ_DEFAULT_PASS}@rabbitmq:5672/%2f/messages

# === Agent ===
ANTHROPIC_API_KEY=sk-ant-api03-...
ANTHROPIC_MODEL=claude-haiku-4-5-20251001
MCP_SERVER_URL=http://nginx/_mcp

# === Deployment ===
IMAGE_TAG=latest    # Updated by CD pipeline to git SHA
```

---

## Database Management

### Migrations in Production
Run automatically during CD deployment:
```bash
sudo k3s kubectl -n maggie exec deploy/php -- bin/console doctrine:database:create --no-interaction --if-not-exists
sudo k3s kubectl -n maggie exec deploy/php -- bin/console doctrine:migrations:migrate --no-interaction
```

### Manual Migration
```bash
cd /opt/maggie
sudo k3s kubectl -n maggie exec deploy/php -- bin/console doctrine:migrations:migrate --no-interaction
```

### Doctrine Production Config
- `auto_generate_proxy_classes: false`
- Query cache: APCu
- Result cache: APCu
- Identity generation: PostgreSQL native

---

## Mercure Production Config

- Internal URL: `http://mercure/.well-known/mercure` (used by PHP to publish)
- Public URL: `https://maggieai.fr/.well-known/mercure` (used by clients to subscribe)
- CORS origin: `https://maggieai.fr`
- JWT auth: shared secret between PHP, the agent and Mercure (`MERCURE_JWT_SECRET`, at least 32 bytes — see `global/real-time.md`)

---

## RabbitMQ

- Image: `rabbitmq:3-management-alpine`
- Management UI: internal only (not exposed through Traefik)
- Used by Symfony Messenger for async message transport
- Current routing: all calendar commands use `sync` transport (not async yet)

---

## Security Considerations

### PHP
- `expose_php = Off`
- Secure session cookies (secure, httponly, samesite=Lax)
- Non-root container user

### Nginx
- Security headers (X-Frame-Options, X-Content-Type-Options, X-XSS-Protection)
- Gzip compression for performance

### Network
- All inter-service communication on `internal` network
- Only nginx, mercure, and agent exposed to Traefik
- Database and RabbitMQ completely internal
- TLS termination at Traefik with Let's Encrypt
- `TRUSTED_PROXIES=REMOTE_ADDR` for Traefik forwarded headers

### Agent
- Non-root container user
- 2 Uvicorn workers in production

---

## Rollback Procedure

Since images are tagged with git SHAs:
```bash
# On VPS
cd /opt/maggie
# Edit .env.prod, set IMAGE_TAG to previous SHA
sed -i 's/IMAGE_TAG=.*/IMAGE_TAG=<previous-sha>/' .env.prod
/opt/maggie/infra/scripts/deploy-k3s.sh <image-tag>
```

> Note: Database migrations are not automatically rolled back. Manual `doctrine:migrations:migrate prev` may be needed.

---

## Monitoring

Currently no dedicated monitoring. Health checks are:
- Traefik dashboard: `traefik.maggieai.fr`
- Agent health: `GET https://maggieai.fr/agent/health`
- API: `GET https://maggieai.fr/api`
- Admin: `GET https://maggieai.fr/admin/`
- Mercure: `GET https://maggieai.fr/.well-known/mercure` (returns hub info)

---

## Versioning

All components at version `0.1.0`:
- API: `api/composer.json`
- Agent: `agent/pyproject.toml`
- Admin: `admin/package.json`
- Mobile: `mobile/app/build.gradle.kts` (versionCode: 1, versionName: "0.1.0")
