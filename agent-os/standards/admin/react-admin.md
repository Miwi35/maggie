# React Admin

Uses `@api-platform/admin` (HydraAdmin) with Vite.

## Patterns
- `ResourceGuesser` for standard CRUD screens
- Custom components for complex views (agenda, chat)
- Mercure SSE via native `EventSource` API, not a library
- Vite `base: '/admin/'` for Traefik path routing
- `allowedHosts: ['maggie.local']` in Vite config

## Mercure subscription
```tsx
const url = new URL(MERCURE_URL, window.location.origin)
url.searchParams.append('topic', '/api/events/{id}')
const eventSource = new EventSource(url.toString(), { withCredentials: true }) // private updates need the cookie
```

- Use `VITE_MERCURE_PUBLIC_URL` env var, fallback to `http://maggie.local/.well-known/mercure`
