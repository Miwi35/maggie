---
name: agent-testing
description: "pytest testing patterns for the Python Agent Hub. Use when writing or reviewing tests in agent/ including async tests, respx HTTP mocking, and conftest fixtures."
user-invocable: false
---

# Agent Testing (pytest)

## Required Coverage — nothing ships without it

**Every route, gateway or service touched** covers:

- happy path, asserting the response body or the returned object
- every error branch: upstream 4xx/5xx, timeout, malformed payload
- for routes: unauthenticated → 401, invalid body → 422
- for the tool loop: the MCP call is issued with the arguments the model asked for

Mock HTTP with `respx`; never call Claude or the API for real in a unit test.

**Bug fix → red first.** Write the failing test, run it, then fix. Both in the same PR.

**The feature also needs an e2e journey** — chat journeys run against the deterministic
fake LLM, `LLM_PROVIDER=fake`, scripted by the scenario files in
`agent/fixtures/fake-llm/` (ADR-005, MAG-95). Touching the tool loop, the streaming
gateway or `create_llm_client()` means checking those fixtures still drive them:
`task e2e:up && task e2e:seed && task e2e:smoke`, step 9. See
`agent-os/standards/global/testing.md` (Definition of Done) and
`agent-os/standards/global/e2e-environment.md` (The fake LLM).

## File Location & Naming

```
agent/tests/
  conftest.py          # shared fixtures
  test_history.py
  test_mcp_client.py
  test_routes.py
```

- File: `test_{module}.py`
- Class: `Test{Feature}` (no inheritance)
- Method: `test_{action}_{expected_result}`

## Async

`asyncio_mode = "auto"` in pyproject.toml — no `@pytest.mark.asyncio` needed.

## Mocking

```python
# HTTP calls — use respx
with respx.mock:
    respx.post(url).mock(return_value=httpx.Response(200, json={...}))
    result = await client.call()

# Internal deps — use unittest.mock
with patch('app.module.ClassName') as mock:
    mock.return_value.method = AsyncMock(return_value=data)
```

- `respx` for httpx HTTP mocking
- `AsyncMock` for coroutine mocks
- `tmp_path` fixture for temp file tests (YAML configs)

## Fixtures

Shared fixtures in `conftest.py`:

```python
@pytest.fixture
def client():
    from app.main import app
    return TestClient(app)
```

## Assertions

Plain `assert` — no unittest-style methods:

```python
assert response.status_code == 200
assert len(messages) == 3
assert result["key"] == "value"
```

## Reference

For full details, read `agent-os/standards/agent/testing.md`
