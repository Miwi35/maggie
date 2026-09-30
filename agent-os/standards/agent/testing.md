# Agent Testing (pytest)

Every route, gateway or service touched owes a happy path and every error branch (401,
422, upstream failure, timeout). See the Definition of Done in
[global/testing](../global/testing.md) for the rest, including the e2e journey on the
deterministic fake LLM.

## File Location & Naming

```
agent/tests/
  conftest.py          # shared fixtures
  test_conversation_memory.py
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
