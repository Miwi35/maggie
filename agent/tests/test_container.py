import ast
from pathlib import Path
from unittest.mock import AsyncMock, MagicMock, patch

import anthropic
import httpx
import pytest

import app
import app.api.routes as routes
from app.config import settings
from app.container import Container, container
from app.llm.fake import FakeAnthropicClient

APP_DIR = Path(app.__file__).parent


def _files_calling(name: str) -> set[str]:
    found = set()
    for path in APP_DIR.rglob("*.py"):
        for node in ast.walk(ast.parse(path.read_text())):
            if isinstance(node, ast.Call):
                fn = node.func
                called = fn.id if isinstance(fn, ast.Name) else fn.attr if isinstance(fn, ast.Attribute) else None
                if called == name:
                    found.add(path.relative_to(APP_DIR).as_posix())
    return found


def test_create_llm_client_is_called_only_by_the_container():
    assert _files_calling("create_llm_client") == {"container.py"}


@pytest.mark.parametrize("name", ["LLMGateway", "StreamingGateway", "ToolRouter"])
def test_gateways_and_tool_router_are_built_only_by_the_container(name):
    assert _files_calling(name) == {"container.py"}


def test_the_anthropic_client_is_built_only_by_the_factory():
    assert _files_calling("AsyncAnthropic") == {"llm/client.py"}


def test_the_client_has_an_explicit_timeout():
    with patch.object(settings, "llm_provider", "anthropic"), patch.object(settings, "anthropic_api_key", "test"):
        assert Container().llm_client.timeout == anthropic.Timeout(600.0, connect=5.0)


def test_the_timeout_is_the_sdk_own_type():
    """The SDK may ship its own httpx (httpx2 from 0.84) and refuses a foreign `httpx.Timeout` (MAG-884 deploy)."""

    class SdkTimeout(httpx.Timeout):
        pass

    with (
        patch.object(settings, "llm_provider", "anthropic"),
        patch.object(settings, "anthropic_api_key", "test"),
        patch.object(anthropic, "Timeout", SdkTimeout),
    ):
        assert type(Container().llm_client.timeout) is SdkTimeout


def test_the_timeout_is_the_setting():
    with (
        patch.object(settings, "llm_provider", "anthropic"),
        patch.object(settings, "anthropic_api_key", "test"),
        patch.object(settings, "llm_timeout_seconds", 42),
    ):
        assert Container().llm_client.timeout.read == 42


def test_one_client_and_one_tool_router_for_both_gateways():
    with patch.object(settings, "llm_provider", "fake"):
        c = Container()
        assert c.llm_gateway.client is c.streaming_gateway.client is c.llm_client
        assert c.llm_gateway.tool_router is c.streaming_gateway.tool_router is c.tool_router


def test_each_part_is_built_once():
    with patch("app.container.create_llm_client") as create, patch("app.container.llm_configured", return_value=True):
        c = Container()
        for _ in range(2):
            c.llm_client, c.tool_router, c.llm_gateway, c.streaming_gateway  # noqa: B018
        create.assert_called_once()


def test_no_client_without_a_model():
    with patch.object(settings, "llm_provider", "anthropic"), patch.object(settings, "anthropic_api_key", ""):
        c = Container()
        assert c.llm_client is None
        assert c.llm_gateway.client is None


def test_the_fake_provider_gives_the_fake_client():
    with patch.object(settings, "llm_provider", "fake"):
        assert isinstance(Container().llm_client, FakeAnthropicClient)


def test_override_injects_the_client_into_the_gateways_and_restores_it():
    with patch.object(settings, "llm_provider", "fake"):
        c = Container()
        original_gateway, original_client = c.llm_gateway, c.llm_client
        fake = MagicMock()
        with c.override(llm_client=fake):
            assert c.llm_gateway.client is fake
            assert c.streaming_gateway.client is fake
        assert c.llm_gateway is original_gateway
        assert c.llm_client is original_client


def test_override_refuses_an_unknown_part():
    with pytest.raises(TypeError), container.override(gateway=MagicMock()):
        pass


def test_the_background_users_share_the_container_client():
    from app.llm.context_summary import context_summarizer
    from app.llm.style_synthesis import style_synthesizer
    from app.memory.encoder import memory_encoder
    from app.memory.night import memory_night

    for user in (context_summarizer, style_synthesizer, memory_encoder, memory_night):
        assert user.client is container.llm_client


@pytest.mark.asyncio
async def test_delegation_and_dictation_use_the_container_client():
    from app.agents.delegate import handle_delegate
    from app.llm.transcription import _cleanup_with_llm

    fake = MagicMock()
    fake.messages.create = AsyncMock(return_value=MagicMock(content=[MagicMock(text="texte propre")], usage=MagicMock(input_tokens=1, output_tokens=1)))
    with container.override(llm_client=fake):
        assert await _cleanup_with_llm("texte sale") == "texte propre"
        fake.messages.create.assert_awaited_once()

        definition = MagicMock(tools=[], model_id="m", max_iterations=1)
        registry = MagicMock()
        registry.get.return_value = definition
        loop = AsyncMock(return_value={"response": "ok", "tool_calls": []})
        router = MagicMock()
        router.get_tool_definitions = AsyncMock(return_value=[])
        with (
            container.override(tool_router=router),
            patch("app.agents.delegate.subagent_registry", registry),
            patch("app.agents.delegate.run_tool_loop", loop),
            patch("app.agents.delegate.llm_configured", return_value=True),
            patch("app.agents.delegate.select_tools", return_value=[]),
            patch("app.agents.delegate._system_prompt", AsyncMock(return_value="s")),
        ):
            await handle_delegate({"agent": "x", "task": "t"}, "u1")
        assert loop.call_args.kwargs["client"] is fake


def test_main_takes_only_the_router_from_the_routes():
    tree = ast.parse((APP_DIR / "main.py").read_text())
    imports = [n for n in ast.walk(tree) if isinstance(n, ast.ImportFrom) and n.module == "app.api.routes"]
    assert len(imports) == 1
    assert [a.name for a in imports[0].names] == ["router"]
    assert not hasattr(routes, "llm_gateway")
    assert not hasattr(routes, "streaming_gateway")
