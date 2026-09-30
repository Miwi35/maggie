---
name: traefik-routing
description: "Traefik routing configuration. Use when modifying docker-compose labels, route rules, path-based routing, or adding new services to the Traefik edge router."
user-invocable: false
---

# Traefik Routing

Traefik is the edge router. Routing rules live as Docker labels in `docker-compose.yml`.

## Why Traefik

- Dashboard at `:8080` for monitoring routers/services
- Label-based routing: add a service → add labels → done
- Nginx remains only as PHP-FPM gateway (FastCGI)

## Label Pattern

```yaml
labels:
  - "traefik.enable=true"
  - "traefik.http.routers.maggie-{name}.rule=PathPrefix(`/{path}`)"
  - "traefik.http.routers.maggie-{name}.entrypoints=web"
  - "traefik.http.services.maggie-{name}.loadbalancer.server.port={port}"
```

## Route Map

| Path | Service | Port |
|------|---------|------|
| `/api`, `/_mcp`, `/bundles` | nginx | 80 |
| `/admin` | node | 5173 |
| `/agent` | agent | 8001 |
| `/.well-known/mercure` | mercure | 80 |
| `/` | traefik (redirect → `/admin`) | — |

## Rules

- `exposedbydefault=false` — services must opt in with `traefik.enable=true`
- Router names prefixed `maggie-*` to avoid conflicts with other projects
- No direct port mappings on routed services (Traefik owns `:80`)
- Internal services (db, rabbitmq) may get labels case-by-case for monitoring

## Production

- Domain: `maggieai.fr`, HTTPS via Let's Encrypt (Traefik ACME)
- Networks: `internal` (inter-service), `traefik-public` (external routing)
- VPS layout: `/opt/traefik/` (shared proxy), `/opt/maggie/` (app)

## Reference

For full details, read `agent-os/standards/docker/traefik-routing.md`
