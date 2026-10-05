# Elasticsearch Indexation — Shaping Decisions

## Decision: Bus Middleware over Doctrine Listener

Chose Messenger middleware over Doctrine PostPersist/PostUpdate/PostRemove listeners because:
- Consistent with existing `MercurePublishMiddleware` architecture
- Has access to handler result (entity) via `HandledStamp`
- Naturally scoped to command bus flow (not raw Doctrine flushes)
- No separate service tag registration needed

## Decision: CQRS Read from ES

Route all GET/GetCollection API Platform operations to Elasticsearch:
- ES documents contain ALL serializable fields (the document IS the read model)
- Entity hydration from `_source` without DB queries
- Relations use `EntityManager::getReference()` (proxy, no DB hit)
- Graceful fallback to Doctrine if ES is unavailable

## Decision: Docker Profile for ES

ES runs under `--profile search` to keep default `docker compose up` lightweight.
Developers who need search start with `task up:search`.

## Decision: French Text Analyzer

Default index settings include French analyzer (elision + stemmer) since the primary user's data is in French.

## Decision: User Scoping

Every document stores `userId`. ES queries always filter by current user ID (same model as `CurrentUserExtension` in Doctrine).

## Decision: Async Indexation

Index operations are dispatched as async messages to RabbitMQ. This prevents ES failures from breaking API responses and allows bulk processing.

**Revised (MAG-113):** indexation now runs inside the request (`sync` routing, `refresh=true` on each write). The web client refetches its list the moment a write returns and the list comes from ES, so a worker-side index (plus the 1 s refresh) made every change appear seconds late. `IndexManager` swallows ES failures, so an ES outage still does not break a response. Tests keep `async` in memory to assert the dispatch.
