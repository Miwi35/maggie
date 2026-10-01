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
- A client topic never carries a literal `{userId}`: nothing substitutes it, so the subscription connects and receives nothing.

## Hub version: pinned by tag and digest, never untagged
Every Mercure image reference is `dunglas/mercure:<tag>@sha256:<digest>` (today `v1.0.2`) — `docker-compose.yml`, `docker-compose.e2e.yml`, `infra/k8s/mercure-deployment.yaml`, `api/compose.yaml`. An untagged image follows `latest`: the 0.x → 1.0 release renamed the subscribe parameter and the token format, and everything real-time died at once (MAG-97, MAG-142). A bump is a ticket of its own: read the release notes, move the hub and every client in one PR, run `task e2e:web`, and check no unpinned reference is left (`grep -rn "dunglas/mercure" .`).

## Mercure 1.0 protocol
- **Subscribe**: `match=<topic>` for an exact topic, `match_urlpattern=<pattern>` for a URL Pattern (`:id` for one segment, `*` for the rest, slashes included). The 0.x `topic` parameter (and its `{id}` / `{+topic}` templates) answers `400`. The admin builds the URL in `hooks/mercureUrl.ts`, the mobile app in `MercureService.buildSubscriptionUrl`, both turning `{id}` into `:id`. **Publishing** still posts a `topic` form field.
- **Tokens** are OAuth 2.0 JWT access tokens (RFC 9068 + RFC 9396), signed HS256 with `MERCURE_JWT_SECRET`: header `typ: at+jwt`; claims `iss` = `maggie` (the hub's `MERCURE_TRUSTED_ISSUERS`), `aud` = `MERCURE_PUBLIC_URL`, `exp` (required), `sub`; grants in `authorization_details` (`type: https://mercure.rocks/authorization-detail`, `actions: [publish|subscribe]`, `topics: [{match, match_type?}]`). The 0.x `mercure.publish` / `mercure.subscribe` claim is refused with `401`. Minted in one place per language: `Maggie\Core\Mercure\MercureAccessToken` (API; the bundle's publisher uses `MercurePublisherTokenProvider`) and `agent/app/mercure/publisher.py`.
- **Subscriber grant** (`MercureSubscriberTokenFactory`): exact `match` for each agent topic, plus one `urlpattern` `/users/{id}/*` — a multi-segment topic is covered.
- **Hub env** (all three stacks): `MERCURE_TRUSTED_ISSUERS=maggie`, and in `MERCURE_EXTRA_DIRECTIVES` `resource_identifier <MERCURE_PUBLIC_URL>` — the hub sits behind a proxy over plain HTTP, so it cannot derive the public URL a token's `aud` should name — and `cookie_name mercureAuthorization`, because the 1.0 default `__Secure-mercure_access_token` is refused by the browser over the plain-HTTP e2e stack.

## Every update is private
A public update ignores the subscriber's token grants. Publish with `private: true` (PHP) or `private=on` (agent); list any new topic outside `/users/{id}/` in `MercureSubscriberTokenFactory` (as an exact `match`); open every `EventSource` with `{ withCredentials: true }`.

## `MERCURE_JWT_SECRET` is at least 32 bytes
HS256 refuses a key under 256 bits, and the publisher swallows the error: the request succeeds, the entity is written, **no update is ever published** — nothing looks broken. The API therefore refuses every request and console command (worker included) when the secret is shorter than 32 bytes (`MercureSecretGuard`), and `MercurePublishMiddleware` logs an unsignable secret as `critical`. Generate with `openssl rand -hex 32`; the API, the agent and the hub must share the same value, so redeploy the three together. Example values in `.env.prod.example` and `infra/k8s/secrets.example.yaml` are already long enough — replace them, never shorten them.

## Client pattern
Use native `EventSource` API. No library needed.
