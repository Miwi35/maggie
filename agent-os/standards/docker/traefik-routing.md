# Traefik Routing

Traefik is the edge router. Routing rules live as Docker labels in `docker-compose.yml`, not in config files.

## Why Traefik
- Dashboard at `:8080` for monitoring routers/services
- Label-based routing: add a service → add labels → done
- Nginx remains only as PHP-FPM gateway (FastCGI)

## Label pattern
```yaml
labels:
  - "traefik.enable=true"
  - "traefik.http.routers.maggie-{name}.rule=PathPrefix(`/{path}`)"
  - "traefik.http.routers.maggie-{name}.entrypoints=websecure"
  - "traefik.http.routers.maggie-{name}.tls=true"
  - "traefik.http.services.maggie-{name}.loadbalancer.server.port={port}"
  - "maggie.stack=dev"
```

Dev serves HTTPS on `:443` (`websecure`); `web` (`:80`) only redirects to it. Traefik only sees containers labelled `maggie.stack=dev` (`--providers.docker.constraints`), so it never claims the per-worktree e2e stacks.

## Route map
| Path | Service | Port |
|------|---------|------|
| `/api`, `/_mcp`, `/bundles`, `/privacy`, `/terms` | nginx | 80 |
| `/admin` | node | 5173 |
| `/agent` | agent | 8001 |
| `/ciqual` | ciqual | 8002 |
| `/.well-known/mercure` | mercure | 80 |
| `/` | traefik (redirect → `/admin`) | — |

## Rules
- `exposedbydefault=false` — services must opt in with `traefik.enable=true`
- Only services needing external path-based routing get labels by default
- Internal services (db, rabbitmq) may get labels case-by-case for monitoring
- No direct port mappings on routed services (Traefik owns `:80` and `:443`)
- Router names are prefixed `maggie-*` to avoid conflicts with other projects

## Production
- Domain `maggieai.fr`, HTTPS via Let's Encrypt (Traefik ACME)
- The k3s cluster routes with an Ingress (`infra/k8s/ingress.yaml`), not Docker labels: a new path-routed service needs both
- Shared proxy for the VPS: `infra/traefik/docker-compose.yml`
