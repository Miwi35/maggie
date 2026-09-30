---
name: admin-react
description: "React Admin component patterns. Use when creating or modifying React components in admin/ including HydraAdmin, ResourceGuesser, Vite config, and Mercure SSE subscriptions."
user-invocable: false
---

# Admin React Patterns

## Stack

- `@api-platform/admin` (HydraAdmin) with Vite, React 19, react-admin 5
- Vite `base: '/admin/'` for Traefik path routing
- `allowedHosts: ['maggie.local']` in Vite config
- Localization: `ra-i18n-polyglot`, `ra-language-french`
- API Platform 4 uses `member` key (not `hydra:member`) in collection responses

## Patterns

- `ResourceGuesser` for standard CRUD screens
- Custom components for complex views (agenda, chat, cookbook)

## Mercure SSE

Use native `EventSource` API — no library:

```tsx
const url = new URL(MERCURE_URL)
url.searchParams.append('topic', '/api/events/{id}')
const eventSource = new EventSource(url.toString())
```

Use `VITE_MERCURE_PUBLIC_URL` env var, fallback to `http://maggie.local/.well-known/mercure`.

## HTTP Client

```tsx
const httpClient = (url: URL, options: HttpClientOptions = {}) => {
  const token = localStorage.getItem('token')
  if (token) {
    options.user = { authenticated: true, token: `Bearer ${token}` }
  }
  return fetchHydra(url, options)
}
```

Every API request includes `Authorization: Bearer <jwt>`.

## Reference

For full details, read `agent-os/standards/admin/react-admin.md`
