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
- User-scoped: `/users/{userId}/api/{resource}/{id}`
- Agent: `/chat/{userId}`, `/contexts/{userId}`, `/proactions/{userId}`, `/instructions/{userId}`, `/skills/{userId}`

## Every update is private
A public update ignores the subscriber's token claims. Publish with `private: true` (PHP) or `private=on` (agent); list any new topic outside `/users/{id}/` in `MercureSubscriberTokenFactory`; open every `EventSource` with `{ withCredentials: true }`.

## Client pattern
Use native `EventSource` API. No library needed.
