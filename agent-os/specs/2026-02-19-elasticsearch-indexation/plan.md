# Elasticsearch Indexation — Implementation Plan

## Context

Maggie v3 currently has no full-text search. All data retrieval uses Doctrine ORM queries with API Platform filters. As the platform grows with multiple modules (calendar, cookbook, notification), unified search is needed for:

- Full-text search in admin and mobile UIs
- Analytics/aggregations for dashboards
- MCP search tool so the AI agent can find data via natural language queries

## Approach

- `elastic/elasticsearch-php` official client
- Async indexation via Messenger/RabbitMQ
- PHP attributes for mapping definitions
- Bus middleware (mirroring `MercurePublishMiddleware`)
- CQRS: read from ES (GetCollection/Get), write to Doctrine (Post/Patch/Delete)
- Graceful fallback to Doctrine when ES is unavailable

## Tasks

1. Save spec documentation
2. Docker infrastructure (ES service, env vars, Taskfile shortcuts)
3. Core abstractions (attributes, interfaces, services, middleware, CLI commands)
4. CQRS read providers (hydrator, collection/item providers, filter translator, paginator)
5. Annotate entities (all modules)
6. MCP search tool
7. Tests
