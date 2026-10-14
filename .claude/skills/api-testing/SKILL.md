---
name: api-testing
description: "PHPUnit testing patterns for the Symfony API. Use when writing or reviewing tests for entities, MCP tools, Mercure publication, repositories, and middleware."
user-invocable: false
---

# API Testing (PHPUnit)

**Use when** writing or reviewing a test in `api/`.

**Read** `agent-os/standards/api/testing.md`; what a ticket owes overall: `agent-os/standards/global/testing.md`.

## Gotchas

- Tests live in the module (`api/modules/{module}/tests/`), namespace `Maggie\{Module}\Tests`; a module missing from `composer.json` or `phpunit.dist.xml` runs zero tests.
- MCP tool: no user bound, bad input, happy path asserting DB state, Mercure, Elasticsearch. Endpoint: 401 and 400 first.
- `setUp` calls `resetMercure()` and `resetAsyncTransport()`, or assertions leak between tests.
- Fixtures: no `createdAt`/`updatedAt`, and `$this->em()->clear()` before a tool reads fixture data.
- A bug fix starts with a red test.
