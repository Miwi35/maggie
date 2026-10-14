# React Admin

Uses `@api-platform/admin` (HydraAdmin) with Vite.

## Stack
- React 19, react-admin 5, Vite
- Localization: `ra-i18n-polyglot` + `ra-language-french`
- API Platform 4 collections use the `member` key, not `hydra:member`
- Every API request carries `Authorization: Bearer <jwt>` (`httpClient` in `App.tsx`, see [google-oauth](google-oauth.md))

## Patterns
- `ResourceGuesser` for standard CRUD screens
- Custom components for complex views (agenda, chat, cookbook)
- Mercure SSE via native `EventSource` API, not a library
- Vite `base: '/admin/'` for Traefik path routing
- `allowedHosts: ['maggie.local']` in Vite config

## Mercure subscription
```tsx
const url = mercureUrl(MERCURE_URL, ['/chat/' + userId]) // hooks/mercureUrl.ts: `match` / `match_urlpattern`, never `topic`
const eventSource = new EventSource(url.toString(), { withCredentials: true }) // private updates need the cookie
```

- Use `VITE_MERCURE_PUBLIC_URL` env var, fallback to `http://maggie.local/.well-known/mercure`
- Payload handling (`useMercure` hook, patching state, refetch fallback): [global/real-time](../global/real-time.md)
