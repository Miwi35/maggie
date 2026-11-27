"""The origin of a proaction asked in the chat: the owner's message, set by the code, never by the model (MAG-977)."""

import json
from types import SimpleNamespace
from unittest.mock import AsyncMock, MagicMock, patch
from zoneinfo import ZoneInfo

import pytest

from app.container import Container
from app.db.provider_state_repository import provider_state_repo
from app.llm.prompt_cache import build_system
from app.llm.tools import ToolRouter
from app.native_tools import build_native_registry
from app.policy.origin import OriginToolRouter, owner_message_origin
from tests.prompt_helpers import stub_system_prompt
from tests.test_chat_gateway import _gateway, _stubs
from tests.test_loop_unchanged import ScriptedClient, _ends, _text, _use, _uses

SCHEDULE_SCHEMA = {
    "type": "object",
    "properties": {
        "prompt": {"type": "string"},
        "scheduled_at": {"type": "string"},
        "summary": {"type": ["string", "null"], "default": None},
        "origin": {
            "type": "object",
            "properties": {"kind": {"type": "string"}, "ref": {"type": "string"}},
            "required": ["kind", "ref"],
        },
    },
    "required": ["prompt", "scheduled_at", "origin"],
}
MCP_TOOLS = [
    {"name": "schedule_proaction", "description": "Schedules", "inputSchema": SCHEDULE_SCHEMA},
    {"name": "get_events", "description": "Events", "inputSchema": {"type": "object", "properties": {}}},
]
ASKED = {"prompt": "Ajoute du lait à la liste de courses", "scheduled_at": "2037-01-15T08:30:00+01:00"}
FORGED = {"kind": "owner_message", "ref": "a-message-the-model-picked"}
SCHEDULED = json.dumps({"success": True, "proaction": {"id": "p1"}})


def _router() -> ToolRouter:
    return ToolRouter(natives=build_native_registry())


def _mcp(mcp) -> None:
    mcp.list_tools = AsyncMock(return_value=MCP_TOOLS)
    mcp.call_tool = AsyncMock(return_value=SCHEDULED)


def _scheduled(mcp) -> list[dict]:
    return [c.args[1] for c in mcp.call_tool.await_args_list if c.args[0] == "schedule_proaction"]


async def test_the_model_is_never_offered_origin():
    with patch("app.llm.tools.mcp_client") as mcp:
        _mcp(mcp)
        for source in (None, "proaction", "a2a"):
            tools = {t["name"]: t for t in await _router().get_tool_definitions(source)}
            if source == "a2a":
                assert "schedule_proaction" not in tools
                continue
            schema = tools["schedule_proaction"]["input_schema"]
            assert "origin" not in schema["properties"]
            assert schema["properties"] == {k: v for k, v in SCHEDULE_SCHEMA["properties"].items() if k != "origin"}
            assert schema["required"] == ["prompt", "scheduled_at"]
            assert tools["get_events"]["input_schema"] == MCP_TOOLS[1]["inputSchema"]
    # What the API offers is left untouched.
    assert "origin" in SCHEDULE_SCHEMA["properties"]


@pytest.mark.parametrize("source", ["chat", "proaction", "planning", "a2a", "subagent:researcher", "approval"])
async def test_an_origin_written_by_the_model_never_reaches_the_api(source):
    router = OriginToolRouter(_router(), owner_message_origin("msg-1")) if source == "chat" else _router()
    with patch("app.llm.tools.mcp_client") as mcp:
        _mcp(mcp)
        await router.call_tool("schedule_proaction", {**ASKED, "origin": FORGED}, user_id="user-1", source=source)

    sent = _scheduled(mcp)
    if source == "a2a":
        # A peer never schedules: the call is refused before it leaves.
        assert sent == []
    elif source == "chat":
        assert sent == [{**ASKED, "origin": {"kind": "owner_message", "ref": "msg-1"}}]
    else:
        assert sent == [ASKED]


async def test_a_streamed_turn_schedules_with_the_owner_message_as_origin():
    client = ScriptedClient(
        [
            _uses(_use("t1", "schedule_proaction", **ASKED)),
            _ends(_text("C'est programmé.")),
        ]
    )
    gateway = Container().streaming_gateway
    gateway.client = client
    gateway.tool_router = _router()
    gateway.system_prompt = stub_system_prompt(build_system("STABLE", "VOLATILE"))
    resolution = {"id": "ctx-1", "label": "Courses", "action": "matched"}
    with (
        patch("app.llm.tools.mcp_client") as mcp,
        patch("app.llm.streaming.route_message", AsyncMock(return_value=resolution)),
        patch("app.llm.streaming.open_threads", AsyncMock(return_value=[])),
        patch("app.llm.streaming.build_history", AsyncMock(return_value=[{"role": "user", "content": "lait"}])),
        patch("app.llm.streaming.resolve_user_timezone", AsyncMock(return_value=ZoneInfo("Europe/Paris"))),
        patch("app.llm.streaming.message_repo") as messages, patch("app.llm.streaming.message_service", messages),
        patch("app.llm.streaming.context_repo") as contexts,
        patch("app.llm.streaming.context_summarizer", MagicMock(maybe_summarize=AsyncMock())),
        patch.object(provider_state_repo, "mark_ok", AsyncMock()),
        patch.object(provider_state_repo, "mark_down", AsyncMock()),
    ):
        _mcp(mcp)
        messages.create = AsyncMock(side_effect=lambda **kw: SimpleNamespace(id=kw.get("message_id")))
        messages.checkpoint_turn = AsyncMock()
        messages.announce = AsyncMock()
        contexts.append_tool_call = AsyncMock()
        async for _event in gateway.chat_stream("Dans 30 minutes, ajoute du lait", "user-1", "msg-owner-1"):
            pass

    assert _scheduled(mcp) == [{**ASKED, "origin": {"kind": "owner_message", "ref": "msg-owner-1"}}]


async def test_a_blocking_chat_turn_schedules_with_the_owner_message_as_origin():
    async def run(_system, _messages, _tools, *, tool_router, user_id, source, context_id=None, **_rest):
        # The model's call, as the loop hands it to the router it was given.
        await tool_router.call_tool("schedule_proaction", ASKED, user_id=user_id, source=source, context_id=context_id)
        return {"response": "C'est programmé.", "tool_calls": []}

    gateway = _gateway()
    gateway.tool_router = _router()
    history = AsyncMock(return_value=[{"role": "user", "content": "lait"}])
    with _stubs(run, history), patch("app.llm.tools.mcp_client") as mcp:
        _mcp(mcp)
        await gateway.chat("Dans 30 minutes, ajoute du lait", "user-1", exclude_message_id="msg-owner-2")

    assert _scheduled(mcp) == [{**ASKED, "origin": {"kind": "owner_message", "ref": "msg-owner-2"}}]
