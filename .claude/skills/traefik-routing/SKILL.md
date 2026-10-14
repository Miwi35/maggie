---
name: traefik-routing
description: "Traefik routing configuration. Use when modifying docker-compose labels, route rules, path-based routing, or adding new services to the Traefik edge router."
user-invocable: false
---

# Traefik Routing

**Use when** changing docker-compose labels, route rules, or adding a path-routed service.

**Read** `agent-os/standards/docker/traefik-routing.md`: label pattern, route map, rules, production.

## Gotchas

- Dev routing is Docker labels in `docker-compose.yml`; a router is `maggie-{name}`, on `websecure` with `tls=true`.
- Traefik only sees containers labelled `maggie.stack=dev`, so it never claims the per-worktree e2e stacks.
- `exposedbydefault=false`: a service opts in with `traefik.enable=true`.
- Production is a k3s Ingress (`infra/k8s/ingress.yaml`): a new path-routed service needs both.
- No direct port mapping on a routed service; Traefik owns `:80` and `:443`.
