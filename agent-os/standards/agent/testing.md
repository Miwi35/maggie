# Agent Testing (pytest)

Every route, gateway or service touched owes:

- a happy path, asserting the response body or the returned object
- every error branch: upstream 4xx/5xx, timeout, malformed payload
- for a route: unauthenticated → 401, invalid body → 422
- for the tool loop: the MCP call is issued with the arguments the model asked for

Mock HTTP with `respx`; a unit test never calls Claude or the API for real.

See the Definition of Done in [global/testing](../global/testing.md) for the rest
(red-first bug fixes, the e2e journey). Chat journeys run on the deterministic fake LLM
(`LLM_PROVIDER=fake`, scenarios in `agent/fixtures/fake-llm/`, ADR-005, MAG-95): touching
the tool loop, the streaming gateway or `create_llm_client()` means checking those
fixtures still drive them (`task e2e:up && task e2e:seed && task e2e:smoke`, step 9 of
`e2e/smoke/smoke.sh`). See [global/e2e-environment](../global/e2e-environment.md), *The fake LLM*.

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

Plain `assert` — no unittest-style methods.

```python
assert response.status_code == 200
assert len(messages) == 3
assert result["key"] == "value"
```
