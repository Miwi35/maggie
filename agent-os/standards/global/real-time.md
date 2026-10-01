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
- Agent: `/chat/{userId}`, `/contexts/{userId}`, `/proactions/{userId}`, `/instructions/{userId}`, `/skills/{userId}` — `userId` is the API user's ULID (the `sub` claim of the JWT the agent authenticates; the `user_id` the mobile sends is ignored). Spelled once in `agent/app/mercure/topics.py`, published in `agent/contract/mercure-topics.json`, read by the mobile and admin contract tests.
- A client topic never carries a literal `{userId}`: `MercureService.subscribe` passes it through unsubstituted, so the subscription connects and receives nothing.

## Every update is private
A public update ignores the subscriber's token claims. Publish with `private: true` (PHP) or `private=on` (agent); list any new topic outside `/users/{id}/` in `MercureSubscriberTokenFactory`; open every `EventSource` with `{ withCredentials: true }`.

## `MERCURE_JWT_SECRET` is at least 32 bytes
HS256 refuses a key under 256 bits, and the publisher swallows the error: the request succeeds, the entity is written, **no update is ever published** — nothing looks broken. The API therefore refuses every request and console command (worker included) when the secret is shorter than 32 bytes (`MercureSecretGuard`), and `MercurePublishMiddleware` logs an unsignable secret as `critical`. Generate with `openssl rand -hex 32`; the API, the agent and the hub must share the same value, so redeploy the three together. Example values in `.env.prod.example` and `infra/k8s/secrets.example.yaml` are already long enough — replace them, never shorten them.

## Client pattern
Use native `EventSource` API. No library needed.
