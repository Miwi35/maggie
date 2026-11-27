"""The system prompt sent to the model must not change when its builder moves (MAG-952).

Every public entry point that talks to the model is run on frozen sources; the `system` of its first call is
compared, block by block, to `fixtures/system_prompts.json`. Recorded on the code of the two builders that
existed before the single one (`LLMGateway._build_system_prompt`, `StreamingGateway._build_system_prompt`).
"""

import importlib
import json
from datetime import UTC, datetime
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from app import user_timezone
from app.container import Container
from app.db.context_model import ContextStatus, ConversationContext
from app.db.context_repository import context_repo
from app.db.instruction_repository import instruction_repo
from app.db.message_repository import message_repo
from app.db.personality_repository import personality_repo
from app.domain.approvals.service import Approved
from app.day_context import resolve_day_context
from app.llm.last_exchange import last_exchange_section
from app.mcp.client import mcp_client
from app.memory.agent_memory import AgentMemory, MemoryBlocks
from app.skills.index import skill_index
from app.user_timezone import resolve_user_timezone, resolve_work_hours

SNAPSHOT = Path(__file__).parent / "fixtures" / "system_prompts.json"
NOW = datetime(2026, 11, 22, 10, 30, tzinfo=UTC)
TOOLS = [
    {"name": "get_events", "description": "Events", "input_schema": {"type": "object", "properties": {}}},
    {"name": "search_recipes", "description": "Recipes", "input_schema": {"type": "object", "properties": {}}},
]
THREAD = ConversationContext(
    id="ctx-1", user_id="user-1", label="Courses", status=ContextStatus.ACTIVE, summary="La liste de la semaine.", tool_calls_log=[]
)
CLOCK_MODULES = (
    "app.personality.engine",
    "app.llm.gateway",
    "app.llm.streaming",
    "app.runtime.system_prompt",
    "app.prompt_sources",
)
SOURCE_MODULES = ("app.llm.gateway", "app.llm.streaming", "app.prompt_sources")
REAL_SOURCES = {
    "last_exchange_section": last_exchange_section,
    "resolve_user_timezone": resolve_user_timezone,
    "resolve_work_hours": resolve_work_hours,
    "resolve_day_context": resolve_day_context,
}


class FrozenDatetime(datetime):
    @classmethod
    def now(cls, tz=None):
        return NOW.astimezone(tz) if tz else NOW


def _module(name: str):
    try:
        return importlib.import_module(name)
    except ModuleNotFoundError:
        return None


class Recorder:
    """A model client that answers « ok » and keeps the `system` of every call."""

    def __init__(self):
        self.systems: list = []
        final = MagicMock(stop_reason="end_turn", content=[SimpleNamespace(type="text", text="ok")])
        final.usage = MagicMock(input_tokens=1, output_tokens=1, cache_creation_input_tokens=0, cache_read_input_tokens=0)
        self.final = final
        self.messages = SimpleNamespace(create=self._create, stream=self._stream)

    async def _create(self, **kwargs):
        self.systems.append(kwargs["system"])
        return self.final

    def _stream(self, **kwargs):
        self.systems.append(kwargs["system"])
        final = self.final

        class Stream:
            async def __aenter__(self):
                return self

            async def __aexit__(self, *exc):
                return False

            def __aiter__(self):
                return self

            async def __anext__(self):
                raise StopAsyncIteration

            async def get_final_message(self):
                return final

        return Stream()


async def _mcp(name, arguments=None, **kwargs):
    if name == "get_user_timezone":
        return json.dumps(
            {
                "timezone": "Europe/Paris",
                "workStartTime": "09:00",
                "workEndTime": "18:00",
                "workDays": [1, 2, 3, 4, 5],
                "email": "owner@example.com",
            }
        )
    if name == "get_day_context":
        day = arguments["date"]
        return json.dumps({"date": day, "weekend": day == "2026-11-22", "periods": []})
    return json.dumps({})


@pytest.fixture
def frozen():
    user_timezone.known_timezones.clear()
    user_timezone.read_at.clear()
    user_timezone.known_work_hours.clear()
    router = MagicMock()
    router.get_tool_definitions = AsyncMock(return_value=TOOLS)
    recorder = Recorder()
    memory = MemoryBlocks(notice="\n\nAvis mémoire.", profile="PROFIL: aime le café.", recalled="RAPPEL: carte 1.")
    directive = SimpleNamespace(content="Tutoie-moi.")
    last = SimpleNamespace(created_at=datetime(2026, 11, 22, 8, 0, tzinfo=UTC), context_id="ctx-1")
    patches = [
        patch.object(personality_repo, "get", AsyncMock(return_value=None)),
        patch.object(instruction_repo, "find_by_user", AsyncMock(return_value=[directive])),
        patch.object(context_repo, "find_active", AsyncMock(return_value=[THREAD])),
        patch.object(context_repo, "get", AsyncMock(return_value=THREAD)),
        patch.object(message_repo, "find_last", AsyncMock(return_value=last)),
        patch.object(message_repo, "find_unanswered", AsyncMock(return_value=[])),
        patch.object(mcp_client, "call_tool", AsyncMock(side_effect=_mcp)),
        patch.object(skill_index, "refresh", AsyncMock()),
        patch.object(skill_index, "get_skills_index", MagicMock(return_value="\n\nINDEX DES COMPÉTENCES")),
        patch.object(skill_index, "skills_for_moment", MagicMock(side_effect=lambda moment: f"COMPÉTENCES {moment}")),
        patch.object(AgentMemory, "blocks", AsyncMock(return_value=memory)),
        patch.object(AgentMemory, "catch_up_soon", MagicMock()),
        patch("app.llm.gateway.route_message", AsyncMock(return_value={"id": "ctx-1", "label": "Courses", "action": "matched"})),
        patch("app.llm.streaming.route_message", AsyncMock(return_value={"id": "ctx-1", "label": "Courses", "action": "matched"})),
        patch("app.llm.gateway.build_history", AsyncMock(return_value=[{"role": "user", "content": "salut"}])),
        patch("app.llm.streaming.build_history", AsyncMock(return_value=[{"role": "user", "content": "salut"}])),
        patch("app.llm.streaming.message_repo", AsyncMock()),
        patch("app.llm.streaming.message_service", AsyncMock()),
        patch("app.runtime.loop.record_llm_usage"),
        patch("app.domain.approvals.service.message_repo", AsyncMock()),
        patch("app.domain.approvals.service.message_service", AsyncMock()),
        patch("app.domain.approvals.service.context_summarizer", MagicMock(maybe_summarize=AsyncMock())),
    ]
    for name in CLOCK_MODULES:
        if (module := _module(name)) is not None and hasattr(module, "datetime"):
            patches.append(patch.object(module, "datetime", FrozenDatetime))
    # The conftest fakes these by name in the modules that import them; this test reads the real ones.
    for name in SOURCE_MODULES:
        module = _module(name)
        for source, real in REAL_SOURCES.items():
            if module is not None and hasattr(module, source):
                patches.append(patch.object(module, source, real))
    for p in patches:
        p.start()
    container = Container()
    with container.override(llm_client=recorder, tool_router=router):
        yield container, recorder
    for p in reversed(patches):
        p.stop()


async def _stream(container, **kwargs):
    async for _ in container.streaming_gateway.chat_stream("salut", "user-1", "msg-1", **kwargs):
        pass


async def _announce(container):
    action = SimpleNamespace(
        id="act-1", user_id="user-1", tool_name="create_event", arguments={"title": "Dentiste"}, context_id="ctx-1"
    )
    await container.approvals.announce(Approved(action=action, result='{"ok": true}'))


CASES = {
    "chat_stream_written": lambda c: _stream(c),
    "chat_stream_spoken": lambda c: _stream(c, spoken=True),
    "chat_stream_in_car": lambda c: _stream(c, spoken=True, in_car=True),
    "chat_not_streamed": lambda c: c.llm_gateway.chat("salut", "user-1", exclude_message_id="msg-1"),
    "chat_a2a": lambda c: c.llm_gateway.chat("salut", "user-1", source="a2a"),
    "proaction_reminder": lambda c: c.llm_gateway.proaction("Rappelle-moi le dentiste", "user-1"),
    "proaction_check": lambda c: c.llm_gateway.proaction(
        "Surveille le prix du vol",
        "user-1",
        kind="check",
        signaled_topics=[{"topic": "vol-paris", "at": "2026-11-20", "delivery": "chat", "notice": "baisse de 20 €"}],
    ),
    "proaction_trip": lambda c: c.llm_gateway.proaction("Replanifie", "user-1", summary="Replanifier les trajets"),
    "proaction_vocal_digest": lambda c: c.llm_gateway.proaction(
        "Le digest du matin", "user-1", spoken=True, blocked_tools=frozenset({"send_voice_message"})
    ),
    "planning": lambda c: c.llm_gateway.proaction("Planifie", "user-1", silent=True, about="Les événements du jour"),
    "approval_announcement": _announce,
}


@pytest.mark.parametrize("case", CASES)
async def test_the_system_prompts_sent_to_the_model_are_unchanged(frozen, case):
    container, recorder = frozen

    await CASES[case](container)

    assert recorder.systems, "the model was never called"
    recorded = {case: recorder.systems[0]}
    existing = json.loads(SNAPSHOT.read_text()) if SNAPSHOT.exists() else {}
    if case not in existing:
        SNAPSHOT.write_text(json.dumps(existing | recorded, indent=2, ensure_ascii=False) + "\n")
        return
    assert recorded[case] == existing[case]
