---
name: mercure
description: "Mercure real-time SSE conventions. Use when implementing real-time updates, Mercure topics, EventSource subscriptions, or server-to-server publishing."
user-invocable: false
---

# Mercure Real-Time (SSE)

**Use when** adding or changing a Mercure topic, a publication, an `EventSource` subscription or a client patch.

**Read** `agent-os/standards/global/real-time.md`; publication on the API side: `agent-os/standards/backend/projection.md`.

## Gotchas

- Every update is private (`private: true` in PHP, `private=on` in the agent), and every `EventSource` opens with `{ withCredentials: true }`.
- Mercure 1.0: subscribe with `match` or `match_urlpattern`, never `topic`; publishing still posts `topic`. Never use an untagged hub image.
- A topic outside `/users/{id}/` needs a grant in `MercureSubscriberTokenFactory`, or it goes silent; a literal `{userId}` in a topic is a dead subscription.
- Publish only what changed: differential payloads, `MercureActionPayload` for non-CRUD commands; clients patch inline and fall back to a refetch.
- `MERCURE_JWT_SECRET` is at least 32 bytes, or no update is ever published and nothing looks broken.
