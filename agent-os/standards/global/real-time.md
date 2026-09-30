# Real-Time: Mercure

All real-time updates use **Mercure** (SSE), not WebSockets.

## Why
- Native API Platform integration: `#[ApiResource(mercure: true)]` auto-publishes
- SSE simpler than WebSocket lifecycle (no heartbeat, reconnect built-in)

## URLs
- Internal (server-to-server): `http://mercure/.well-known/mercure`
- Public (client-facing): `http://maggie.local/.well-known/mercure` (via Traefik)

## Topic conventions
- Entity updates: `/api/{resource}/{id}` (auto from API Platform)
- Agent chat: `/agent/chat/{user_id}`

## Client pattern
Use native `EventSource` API. No library needed.
