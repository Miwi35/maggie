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
  - "traefik.http.routers.{name}.rule=PathPrefix(`/{path}`)"
  - "traefik.http.routers.{name}.entrypoints=web"
  - "traefik.http.services.{name}.loadbalancer.server.port={port}"
```

## Route map
| Path | Service | Port |
|------|---------|------|
| `/api`, `/_mcp`, `/bundles` | nginx | 80 |
| `/admin` | node | 5173 |
| `/agent` | agent | 8001 |
| `/.well-known/mercure` | mercure | 80 |
| `/` | traefik (redirect → `/admin`) | — |

## Rules
- `exposedbydefault=false` — services must opt in with `traefik.enable=true`
- Only services needing external path-based routing get labels by default
- Internal services (db, rabbitmq) may get labels case-by-case for monitoring
- No direct port mappings on routed services (Traefik owns `:80`)
