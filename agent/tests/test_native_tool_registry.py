import ast
import json
from pathlib import Path
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from app.agents.registry import SubagentDefinition, SubagentRegistry
from app.llm.tools import ToolRouter
from app.native_tools import build_native_registry
from app.policy.engine import PolicyEngine
from app.runtime.tools.registry import NativeTool, NativeToolRegistry, ToolCallContext


async def _handler(arguments, ctx):
    return "ok"


def _tool(name: str, offered=lambda: True, **schema) -> NativeTool:
    return NativeTool(definition={"name": name, "input_schema": schema}, handler=_handler, offered=offered)


def test_registering_a_name_twice_is_refused():
    registry = NativeToolRegistry()
    registry.register(_tool("a"))
    with pytest.raises(ValueError, match="already registered"):
        registry.register(_tool("a"))


def test_definitions_keep_registration_order_and_skip_tools_not_offered():
    registry = NativeToolRegistry()
    registry.register(_tool("b"))
    registry.register(_tool("hidden", offered=lambda: False))
    registry.register(NativeTool(definition=lambda: {"name": "lazy"}, handler=_handler))
    registry.register(_tool("a"))

    assert [d["name"] for d in registry.definitions()] == ["b", "lazy", "a"]
    assert registry.names() == {"b", "hidden", "lazy", "a"}


def test_required_fields_read_the_input_schema():
    registry = NativeToolRegistry()
    registry.register(_tool("a", required=["x"]))
    registry.register(_tool("b"))

    assert registry.required_fields("a") == ["x"]
    assert registry.required_fields("b") == []
    assert registry.required_fields("unknown") == []


@pytest.mark.asyncio
async def test_with_handler_copies_the_registry_and_swaps_one_handler():
    registry = NativeToolRegistry()
    registry.register(_tool("a"))

    async def other(arguments, ctx):
        return "other"

    copy = registry.with_handler("a", other)

    assert await copy.get("a").handler({}, None) == "other"
    assert await registry.get("a").handler({}, None) == "ok"


APP = Path(__file__).parent.parent / "app"

RESEARCHER = SubagentDefinition(
    name="researcher", description="Cherche", model="haiku", tools=["get_events"], prompt="Tu cherches."
)


def _router(name: str, handler) -> ToolRouter:
    return ToolRouter(natives=build_native_registry().with_handler(name, handler))


def test_delegate_is_offered_only_when_subagents_are_loaded():
    registry = build_native_registry()

    with patch("app.agents.tool.subagent_registry", SubagentRegistry()):
        assert "delegate" not in [d["name"] for d in registry.definitions()]
        assert "delegate" in registry.names()
    with patch("app.agents.tool.subagent_registry", SubagentRegistry(agents={"researcher": RESEARCHER})):
        assert "delegate" in [d["name"] for d in registry.definitions()]


async def test_a_deny_rule_stops_a_native_handler():
    handler = AsyncMock(return_value="ran")
    policy = PolicyEngine.from_mapping({"default": "allow", "rules": [{"tools": ["list_skills"], "mode": "deny"}]})

    with patch("app.llm.tools.policy_engine", policy):
        result = await _router("list_skills", handler).call_tool("list_skills", {}, user_id="u")

    handler.assert_not_awaited()
    assert "error" in json.loads(result)


async def test_ask_owner_receives_its_source():
    handler = AsyncMock(return_value="{}")

    with patch("app.native_tools.handle_ask_owner", handler):
        await ToolRouter(natives=build_native_registry()).call_tool(
            "ask_owner", {"text": "?", "options": ["a", "b"]}, user_id="u", source="proaction"
        )

    handler.assert_awaited_once_with({"text": "?", "options": ["a", "b"]}, "u", "proaction")


async def test_send_voice_message_receives_its_thread():
    handler = AsyncMock(return_value="{}")

    with patch("app.native_tools.handle_send_voice_message", handler):
        await ToolRouter(natives=build_native_registry()).call_tool(
            "send_voice_message", {"text": "Bonjour"}, user_id="u", context_id="ctx-1"
        )

    handler.assert_awaited_once_with({"text": "Bonjour"}, "u", "ctx-1")


async def test_a_memory_tool_records_the_thread_before_running():
    order: list[str] = []

    async def record(user_id, context_id, **_):
        order.append("thread")

    async def memory_search(arguments, user_id):
        order.append("tool")
        return "{}"

    with (
        patch("app.native_tools.conversations.record_thread", side_effect=record) as recorded,
        patch.dict("app.native_tools.MEMORY_HANDLERS", {"memory_search": memory_search}),
    ):
        await ToolRouter(natives=build_native_registry()).call_tool(
            "memory_search", {"requete": "x"}, user_id="u", context_id="ctx-1"
        )

    assert order == ["thread", "tool"]
    recorded.assert_awaited_once_with("u", "ctx-1")


async def test_a_failing_native_handler_answers_tool_call_failed():
    handler = AsyncMock(side_effect=RuntimeError("boom"))

    result = await _router("list_skills", handler).call_tool("list_skills", {}, user_id="u")

    assert json.loads(result) == {"error": "Tool call failed: boom"}


async def test_a_native_call_missing_a_required_field_gets_invalid_input():
    handler = AsyncMock(return_value="ran")

    result = await _router("get_skill", handler).call_tool("get_skill", {}, user_id="u")

    handler.assert_not_awaited()
    assert "name" in result and "missing" in result


async def test_a_native_tool_without_a_user_is_refused():
    handler = AsyncMock(return_value="ran")

    result = await _router("list_skills", handler).call_tool("list_skills", {}, user_id=None)

    handler.assert_not_awaited()
    assert json.loads(result) == {"error": "user_id required for native tools"}


async def test_the_handler_gets_the_call_context():
    seen: list[ToolCallContext] = []

    async def handler(arguments, ctx):
        seen.append(ctx)
        return "{}"

    await _router("list_skills", handler).call_tool("list_skills", {}, user_id="u", source="proaction", context_id="c")

    assert seen == [ToolCallContext(user_id="u", source="proaction", context_id="c")]


DOMAIN_MODULES = (
    "app.agents",
    "app.memory",
    "app.skills",
    "app.db.instruction_model",
    "app.db.instruction_repository",
    "app.bilan_consigne",
    "app.coach_method",
    "app.tts",
    "app.llm.message_reply",
    "app.llm.questions",
    "app.llm.time_tool",
)


def _imported_modules(path: Path) -> set[str]:
    modules: set[str] = set()
    for node in ast.walk(ast.parse(path.read_text())):
        if isinstance(node, ast.Import):
            modules |= {alias.name for alias in node.names}
        elif isinstance(node, ast.ImportFrom) and node.module and node.level == 0:
            modules.add(node.module)
            modules |= {f"{node.module}.{alias.name}" for alias in node.names}
    return modules


def _is_under(module: str, prefix: str) -> bool:
    return module == prefix or module.startswith(prefix + ".")


def test_the_tool_router_imports_no_domain_module():
    paths = [APP / "llm" / "tools.py", *sorted((APP / "runtime").rglob("*.py"))]

    offenders = {
        f"{path.relative_to(APP)} -> {module}"
        for path in paths
        for module in _imported_modules(path)
        if any(_is_under(module, domain) for domain in DOMAIN_MODULES)
    }

    assert not offenders


def test_no_llm_module_imports_app_agents():
    offenders = {
        str(path.relative_to(APP))
        for path in (APP / "llm").rglob("*.py")
        if any(_is_under(module, "app.agents") for module in _imported_modules(path))
    }

    assert not offenders
