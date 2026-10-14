---
name: new-entity
description: "Complete checklist for adding a new API entity. Use when creating a new Doctrine entity with all required MCP tools, Messenger commands, Mercure publication, and tests."
user-invocable: true
argument-hint: "[entity-name] [module-name]"
---

# New Entity Checklist

Adding entity `$0` in module `$1`: follow the **New Entity Checklist** of `agent-os/standards/api/mcp-tools.md` step by step, with the conventions of `agent-os/standards/api/entities.md` and the tests of `agent-os/standards/api/testing.md`.

## Where things go

- `api/modules/$1/src/Entity/$0.php`: ULID, `MercurePublishable`, `OwnedByUserInterface` or `OwnedThroughInterface`
- `api/modules/$1/src/Message/`, `MessageHandler/`, `UseCase/`: `Create$0`, `Update$0`, `Delete$0` (commands carry `{$0}Id`)
- `api/modules/$1/src/Mcp/Tool/`: `Manage{$0}sTool` (or the split set for a rich entity)
- `api/modules/$1/tests/`: `Mcp/$0ToolsTest.php` and a projection test, with fixtures in `fixtures/`

## Gotchas

- Commands are routed `sync` in `api/config/packages/messenger.yaml`, not in the module.
- Nothing to register for Mercure or Elasticsearch: `ProjectionMiddleware` does it, asserted by dispatching the commands.
- The migration is additive and newer than the latest on `main` (`lock:migration`).
- Verify with `task api:lint` and the targeted tests (`task wt:*` in a worktree).
