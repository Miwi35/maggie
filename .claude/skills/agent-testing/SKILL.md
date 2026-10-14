---
name: agent-testing
description: "pytest testing patterns for the Python Agent Hub. Use when writing or reviewing tests in agent/ including async tests, respx HTTP mocking, and conftest fixtures."
user-invocable: false
---

# Agent Testing (pytest)

**Use when** writing or reviewing a test in `agent/`.

**Read** `agent-os/standards/agent/testing.md`; what a ticket owes overall: `agent-os/standards/global/testing.md`.

## Gotchas

- A route, gateway or service owes its happy path and every error branch (upstream 4xx/5xx, timeout, 401, 422).
- HTTP is mocked with `respx`; a unit test never calls Claude or the API for real.
- `asyncio_mode = "auto"`: no `@pytest.mark.asyncio`.
- Touching the tool loop, the streaming gateway or `create_llm_client()`: check the fake-LLM scenarios in `agent/fixtures/fake-llm/` still drive them.
- A bug fix starts with a red test.
