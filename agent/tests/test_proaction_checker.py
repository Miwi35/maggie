"""An ordinary proaction's write waits for the checker, or for the owner (MAG-943)."""

import json
from types import SimpleNamespace
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from app.container import Container
from app.llm.gateway import LLMGateway
from app.memory.agent_memory import MemoryBlocks
from app.policy.checker import OwnerWords
from app.trips import REPLAN_SUMMARY

ADD_MILK = {"label": "Lait"}
COVERED = OwnerWords("instruction", "i-1", "Chaque lundi, ajoute du lait à la liste de courses")
QUOTE = "ajoute du lait à la liste de courses"


class Calls:
    def __init__(self):
        self.order: list[str] = []
        self.mcp = AsyncMock(side_effect=lambda *a, **k: self.order.append("tool") or '{"ok": true}')
        response = SimpleNamespace(
            content=[SimpleNamespace(text=json.dumps({"authorized": True, "quote": QUOTE}))],
            usage=SimpleNamespace(input_tokens=1, output_tokens=1),
        )

        async def create(**_):
            self.order.append("checker")
            return response

        self.checker = MagicMock(create=AsyncMock(side_effect=create))


def gateway(calls: Calls) -> LLMGateway:
    gw = Container().llm_gateway
    gw.client = MagicMock(messages=calls.checker)
    gw.personality = MagicMock(get_system_prompt=AsyncMock(return_value="Tu es Maggie."))
    gw.agent_memory = MagicMock(blocks=AsyncMock(return_value=MemoryBlocks()))
    gw.tool_router = MagicMock(get_tool_definitions=AsyncMock(return_value=[]), call_tool=calls.mcp)
    gw._resolve_proaction_context = AsyncMock(return_value=None)
    return gw


async def run_proaction(calls: Calls, words: list[OwnerWords], **options) -> list[str]:
    """Runs a proaction whose model tries to add milk; returns what the tool answered."""
    answers: list[str] = []

    async def loop(*_a, tool_router, **_k):
        answers.append(await tool_router.call_tool("add_grocery_item", ADD_MILK, user_id="user-1", source="proaction"))
        return {"response": "", "tool_calls": []}

    with (
        patch("app.llm.gateway.run_tool_loop", new=loop),
        patch("app.llm.contexts.context_repo") as contexts,
        patch("app.llm.gateway.behavior_directives_section", AsyncMock(return_value="")),
        patch("app.policy.checker.client_for", new=AsyncMock(side_effect=lambda _u, c: c)),
        patch("app.policy.checker.planning_words", new=AsyncMock(return_value=words)),
    ):
        contexts.find_active = AsyncMock(return_value=[])
        await gateway(calls).proaction("Ajoute du lait à la liste de courses", "user-1", **options)
    return answers


async def test_an_uncovered_write_in_a_proaction_is_held_and_never_runs(pending_db):
    calls = Calls()

    answers = await run_proaction(calls, [])

    assert json.loads(answers[0])["status"] == "pending_approval"
    calls.mcp.assert_not_awaited()
    pending_db.published.assert_awaited_once()
    topic, payload = pending_db.published.await_args.args
    assert topic.endswith("approvals") or "approvals" in topic
    assert payload["summary"] == "Action en attente de validation — « Lait »"


async def test_a_write_an_instruction_covers_runs_after_the_checker():
    calls = Calls()

    answers = await run_proaction(calls, [COVERED])

    assert calls.order == ["checker", "tool"]
    assert answers == ['{"ok": true}']


@pytest.mark.parametrize(
    "options",
    [
        {"silent": True},
        {"summary": REPLAN_SUMMARY},
        {"summary": "Digest du matin"},
        {"dry_run": True},
    ],
)
async def test_planning_trip_and_digest_runs_are_not_checked(options):
    calls = Calls()

    await run_proaction(calls, [COVERED], **options)

    assert "checker" not in calls.order


async def test_a_dry_run_proaction_never_calls_the_checker():
    calls = Calls()

    await run_proaction(calls, [COVERED], dry_run=True)

    calls.checker.create.assert_not_awaited()
    calls.mcp.assert_not_awaited()

