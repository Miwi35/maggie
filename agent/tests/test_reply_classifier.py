"""The owner's dictated answer to a validation is read by an isolated classifier (MAG-893)."""

import asyncio
from types import SimpleNamespace
from unittest.mock import AsyncMock, patch

import pytest

from app.config import settings
from app.llm.fake import DEFAULT_FIXTURES_DIR, FakeAnthropicClient
from app.policy import reply_classifier
from app.policy.reply_classifier import SYSTEM_PROMPT, classify_approval_reply


def client_answering(text: str):
    response = SimpleNamespace(
        content=[SimpleNamespace(text=text)], usage=SimpleNamespace(input_tokens=1, output_tokens=1)
    )
    client = AsyncMock()
    client.messages.create = AsyncMock(return_value=response)
    return client


async def classify(client, said: str = "oui") -> str:
    with patch("app.policy.reply_classifier.client_for", new=AsyncMock(side_effect=lambda _u, c: c)):
        return await classify_approval_reply(client, said, "user-1")


@pytest.fixture
def fake():
    return FakeAnthropicClient(fixtures_dir=DEFAULT_FIXTURES_DIR)


async def test_a_plain_yes_is_approve(fake):
    assert await classify(fake, "oui, vas-y") == "approve"


async def test_a_plain_no_is_deny(fake):
    assert await classify(fake, "non, laisse tomber") == "deny"


async def test_a_yes_with_a_condition_is_other(fake):
    assert await classify(fake, "oui mais à 15 h") == "other"


async def test_it_sees_only_the_owners_sentence():
    client = client_answering('{"answer": "approve"}')

    await classify(client, "oui, vas-y")

    kwargs = client.messages.create.await_args.kwargs
    assert kwargs["messages"] == [{"role": "user", "content": "oui, vas-y"}]
    assert kwargs["system"] == SYSTEM_PROMPT
    assert "tools" not in kwargs


async def test_it_runs_on_sonnet_at_low_effort():
    client = client_answering('{"answer": "deny"}')

    await classify(client)

    kwargs = client.messages.create.await_args.kwargs
    assert kwargs["model"] == settings.anthropic_model
    assert kwargs["output_config"] == {"effort": "low"}


@pytest.mark.parametrize("text", ["peut-être", '{"answer": "maybe"}', "[]", '{"answer": null}'])
async def test_an_unreadable_answer_is_other(text):
    assert await classify(client_answering(text)) == "other"


async def test_a_failing_call_is_other():
    client = AsyncMock()
    client.messages.create = AsyncMock(side_effect=RuntimeError("boom"))

    assert await classify(client) == "other"


async def test_a_slow_call_is_other():
    async def slow(**_):
        await asyncio.sleep(1)

    client = AsyncMock()
    client.messages.create = slow

    with patch.object(reply_classifier, "_TIMEOUT_S", 0.01):
        assert await classify(client) == "other"


async def test_no_client_is_other():
    assert await classify(None) == "other"
