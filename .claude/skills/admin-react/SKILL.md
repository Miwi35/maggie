---
name: admin-react
description: "React Admin component patterns. Use when creating or modifying React components in admin/ including HydraAdmin, ResourceGuesser, Vite config, and Mercure SSE subscriptions."
user-invocable: false
---

# Admin React Patterns

**Use when** creating or changing a React component, a resource screen, the Vite config or a Mercure subscription in `admin/`.

**Read** `agent-os/standards/admin/react-admin.md`; for payload handling, `agent-os/standards/global/real-time.md`.

## Gotchas

- `ResourceGuesser` for standard CRUD; a custom component only for complex views (agenda, chat, cookbook).
- API Platform 4 collections use the `member` key, not `hydra:member`.
- Mercure: native `EventSource` with `{ withCredentials: true }`, URL built by `hooks/mercureUrl.ts` (`match`, never `topic`).
- Vite `base` is `/admin/` and `allowedHosts` includes `maggie.local`; changing either breaks Traefik routing.
- Every API request carries `Authorization: Bearer <jwt>` through the `httpClient` in `App.tsx`.
