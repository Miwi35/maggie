---
name: api-entities
description: "Doctrine entity conventions. Use when creating or modifying entities in api/ including ULID keys, MercurePublishable, ApiResource, Messenger commands, and modular bundle structure."
user-invocable: false
---

# Entity Conventions

**Use when** creating or changing an entity, its commands or a module bundle in `api/`.

**Read** `agent-os/standards/api/entities.md`; publication details: `agent-os/standards/backend/projection.md`.

## Gotchas

- ULID primary key, `#[ApiResource(mercure: true)]`, and `MercurePublishable` on every entity.
- Backed enums go in `src/Enum/`, never `src/Entity/` (the Doctrine mapping root).
- Create/Update/Delete commands use `{entity}Id`, are routed `sync` in `api/config/packages/messenger.yaml`, and `ProjectionMiddleware` publishes and indexes: a handler never calls the hub.
- Ownership: `OwnedByUserInterface` (direct) or `OwnedThroughInterface` (indirect).
- A new module is registered in `bundles.php`, `composer.json`, `phpunit.dist.xml` and `discovery.scan_dirs`.
