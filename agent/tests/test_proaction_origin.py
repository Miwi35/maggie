"""The checker also reads the owner's message a proaction was asked in (MAG-977)."""

import json
from types import SimpleNamespace
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from app.container import Container
from app.llm.gateway import LLMGateway
from app.memory.agent_memory import MemoryBlocks
from app.policy.checker import AuthorizedBy, OwnerWords
from app.proactions.api import Proaction
from app.queue.proaction_consumer import read_message
from tests.prompt_helpers import system_prompt_with
from tests.test_proaction import API_PROACTION, _consume

ADD_MILK = {"label": "Lait"}
ORIGIN = {"kind": "owner_message", "ref": "01JMSG0000000000000000000A"}
ASKED = "Dans 30 minutes, ajoute du lait à la liste de courses"
WARN_ME = "Préviens-moi si l'enveloppe Loisirs dépasse 80 %"
QUOTE = "ajoute du lait à la liste de courses"
INSTRUCTION = OwnerWords("instruction", "i-1", "Chaque soir, fais le point sur les comptes")


def owner_message(content: str = ASKED, *, user_id: str = "user-1", role: str = "user"):
    return SimpleNamespace(id=ORIGIN["ref"], user_id=user_id, role=role, content=content)


class Run:
    """A proaction whose model tries to add milk, its checker answering `quote`."""

    def __init__(self, quote: str = QUOTE):
        self.order: list[str] = []
        self.answers: list[str] = []
        self.decisions: list[dict] = []
        self.mcp = AsyncMock(side_effect=lambda *a, **k: self.order.append("tool") or '{"ok": true}')
        response = SimpleNamespace(
            content=[SimpleNamespace(text=json.dumps({"authorized": True, "quote": quote}))],
            usage=SimpleNamespace(input_tokens=1, output_tokens=1),
        )

        async def create(**_):
            self.order.append("checker")
            return response

        self.checker = MagicMock(create=AsyncMock(side_effect=create))

    def gateway(self) -> LLMGateway:
        gw = Container().llm_gateway
        gw.client = MagicMock(messages=self.checker)
        gw.system_prompt = system_prompt_with(
            personality=MagicMock(get_system_prompt=AsyncMock(return_value="Tu es Maggie.")),
            agent_memory=MagicMock(blocks=AsyncMock(return_value=MemoryBlocks())),
        )
        gw.tool_router = MagicMock(get_tool_definitions=AsyncMock(return_value=[]), call_tool=self.mcp)
        gw._resolve_proaction_context = AsyncMock(return_value=None)
        return gw

    async def __call__(self, *, message=None, words=(), get=None, **options):
        async def loop(*_a, tool_router, **_k):
            self.answers.append(
                await tool_router.call_tool("add_grocery_item", ADD_MILK, user_id="user-1", source="proaction")
            )
            self.decisions = tool_router.decisions
            return {"response": "", "tool_calls": []}

        with (
            patch("app.llm.gateway.run_tool_loop", new=loop),
            patch("app.llm.contexts.context_repo") as contexts,
            patch("app.prompt_sources.behavior_directives_section", AsyncMock(return_value="")),
            patch("app.policy.checker.client_for", new=AsyncMock(side_effect=lambda _u, c: c)),
            patch("app.policy.checker.planning_words", new=AsyncMock(return_value=list(words))),
            patch("app.policy.checker.message_repo") as messages,
        ):
            contexts.find_active = AsyncMock(return_value=[])
            messages.get = get or AsyncMock(return_value=message)
            await self.gateway().proaction(ASKED, "user-1", **options)
            self.get = messages.get
        return self

    def sent(self) -> dict:
        return json.loads(self.checker.create.await_args.kwargs["messages"][0]["content"])


def test_a_claimed_proaction_carries_its_origin_through_the_queue():
    claimed = Proaction.from_api(API_PROACTION | {"status": "running", "origin": ORIGIN})

    queued = read_message(json.dumps(claimed.to_dict()).encode())

    assert claimed.origin == ORIGIN
    assert queued is not None and queued.origin == ORIGIN
    # Those scheduled before have none, and the queue carries none.
    assert "origin" not in Proaction.from_api(API_PROACTION).to_dict()
    assert Proaction.from_api(API_PROACTION | {"origin": "m1"}).origin is None


async def test_the_run_receives_the_origin_of_its_proaction():
    claimed = API_PROACTION | {"status": "running", "origin": ORIGIN}

    consumed = await _consume({"response": "", "tool_calls": []}, body=json.dumps(claimed).encode())

    assert consumed.gateway.proaction.await_args.kwargs["origin"] == ORIGIN


async def test_a_write_the_origin_message_covers_runs_after_the_checker():
    run = await Run()(message=owner_message(), origin=ORIGIN)

    assert run.order == ["checker", "tool"]
    assert run.answers == ['{"ok": true}']
    assert run.decisions == [
        {"name": "add_grocery_item", "authorized_by": AuthorizedBy("owner_message", ORIGIN["ref"], QUOTE)}
    ]
    run.get.assert_awaited_once_with(ORIGIN["ref"])

    # The message comes before the instructions.
    run = await Run()(message=owner_message(), words=[INSTRUCTION], origin=ORIGIN)
    assert run.sent()["mots_du_proprietaire"] == [
        {"ref": ORIGIN["ref"], "texte": ASKED},
        {"ref": "i-1", "texte": INSTRUCTION.text},
    ]


async def test_a_warn_me_message_authorizes_no_write(pending_db):
    # The checker says yes, quoting words the message does not hold.
    run = await Run(quote=QUOTE)(message=owner_message(WARN_ME), origin=ORIGIN)

    assert json.loads(run.answers[0])["status"] == "pending_approval"
    run.mcp.assert_not_awaited()
    assert run.decisions[0]["authorized_by"].kind == "none"
    pending_db.published.assert_awaited_once()
    topic, payload = pending_db.published.await_args.args
    assert "approvals" in topic
    assert payload["summary"] == "Ajouter « Lait » à la liste de courses ? Je n'ai pas retrouvé votre demande."


@pytest.mark.parametrize(
    ("origin", "message"),
    [
        (ORIGIN, owner_message(user_id="user-2")),
        (ORIGIN, owner_message(role="assistant")),
        (ORIGIN, None),
        ({"kind": "instruction", "ref": "i-9"}, owner_message()),
    ],
    ids=["another-user", "maggie", "deleted", "instruction"],
)
async def test_an_origin_the_owner_did_not_write_adds_no_words(pending_db, origin, message):
    run = await Run()(message=message, origin=origin)

    # No words at all: the checker is not even asked, the write waits for the owner.
    run.checker.create.assert_not_awaited()
    run.mcp.assert_not_awaited()
    assert json.loads(run.answers[0])["status"] == "pending_approval"


async def test_a_proaction_without_origin_is_checked_as_before(pending_db):
    covered = OwnerWords("instruction", "i-1", "Chaque lundi, ajoute du lait à la liste de courses")

    run = await Run()(words=[covered])

    run.get.assert_not_awaited()
    assert run.order == ["checker", "tool"]
    assert run.decisions[0]["authorized_by"] == AuthorizedBy("instruction", "i-1", QUOTE)
    assert run.sent()["mots_du_proprietaire"] == [{"ref": "i-1", "texte": covered.text}]


async def test_an_unreadable_origin_message_still_reads_the_instructions():
    covered = OwnerWords("instruction", "i-1", "Chaque lundi, ajoute du lait à la liste de courses")

    run = await Run()(words=[covered], get=AsyncMock(side_effect=RuntimeError("db down")), origin=ORIGIN)

    assert run.order == ["checker", "tool"]
    assert run.sent()["mots_du_proprietaire"] == [{"ref": "i-1", "texte": covered.text}]
