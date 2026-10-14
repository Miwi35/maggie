---
name: api-mcp-tools
description: "MCP tool conventions for the Symfony API. Use when creating or modifying MCP tools, including CRUD tool sets, bus dispatch, repository queries, and error handling."
user-invocable: false
---

# MCP Tool Conventions

**Use when** creating or changing an MCP tool in `api/`.

**Read** `agent-os/standards/api/mcp-tools.md`: shapes, user scoping, write and read tools, new-entity checklist.

## Gotchas

- Default shape: one `manage_{entities}` tool with an `action` (list, create, update, delete); never mix it with split tools for the same entity.
- Always return a JSON string; unknown `action` and missing arguments return an error that names the problem.
- The user comes from `McpUserContext::requireUser()`, never from `findAll()[0]`; catch `MissingMcpUserException` and answer with a JSON error.
- Writes dispatch a command on the bus; reads filter by user through the repository.
- A module missing from `discovery.scan_dirs` in `config/packages/mcp.yaml` has its tools silently hidden: check with a real `tools/list`.
