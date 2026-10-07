"""One discussion, one thread (MAG-341).

On 7 Oct., from 18:13 to 18:21, the owner had a single discussion with Maggie about how she
learns. The router filed it in five threads, one per message, and every new thread started
without the history of the one before — so Maggie forgot what she had been told a minute
earlier. These tests replay that discussion through the real router, the real repositories
and a model stand-in that behaves the way the fast model did that evening.
"""

import json
import re
from datetime import UTC, datetime, timedelta
from unittest.mock import AsyncMock, MagicMock, patch

import pytest
from sqlalchemy import select

from app.db.context_repository import context_repo
from app.db.message_repository import message_repo
from app.db.models import Message
from app.llm.contexts import route_message
from app.llm.fake import DEFAULT_FIXTURES_DIR, FakeAnthropicClient
from app.llm.history import build_history

OWNER = "user-1"

# What the owner said, 7 Oct., one to two minutes apart.
DISCUSSION = [
    "tu m'as rappelé dans le chat mais en général quand je demande un rappel je veux une notification de ta part",
    "et où l'as-tu noté du coup",
    "noter où, parce que tu as accès à une banque d'instructions…",
    "est-ce que si tu avais une liste de directives tu pourrais y insérer ce genre de demande",
    "ben oui mais tu n'as pas accès à la page mais tu as accès à la création de compétence",
]

# The threads the router opened for them that evening, in order.
LABELS_OF_THE_EVENING = [
    "Où noter l'information",
    "Insertion demande dans directives",
    "Question sur contenu page",
    "Accès outils disponibles",
    "Recherche événement code 18774883",
    "Courses",
]

START = datetime(2026, 10, 7, 16, 13, tzinfo=UTC)


class RouterAsOnTheEvening:
    """The fast model as the router used it: it can only link what it is shown.

    None of these follow-ups names its subject — « et où l'as-tu noté du coup » shares no
    word with any thread label. A model shown the labels alone has nothing to tie it to and
    opens a new thread, which is what happened five times in a row. Shown the exchange the
    message follows, it sees the link and answers with the thread that exchange was in.
    """

    def __init__(self):
        self.labels = iter(LABELS_OF_THE_EVENING)
        self.previous: tuple[str, str] | None = None  # (what was said last, the thread it is in)
        self.calls: list[tuple[str, str]] = []

    def said(self, text: str, context_id: str) -> None:
        self.previous = (text, context_id)

    async def create(self, *, model, max_tokens, system, messages):
        prompt = messages[0]["content"]
        self.calls.append((system, prompt))
        answer: dict = {"context_id": None, "label": next(self.labels)}
        if self.previous is not None:
            text, context_id = self.previous
            shown = text[:40] in prompt.rsplit("Message :", 1)[0]
            if shown and f'id="{context_id}"' in prompt:
                answer = {"context_id": context_id}
        response = MagicMock()
        response.content = [MagicMock(text=json.dumps(answer, ensure_ascii=False))]
        response.usage.input_tokens = 100
        response.usage.output_tokens = 10
        return response


def _client(model) -> MagicMock:
    client = MagicMock()
    client.messages.create = model.create
    return client


async def _store(chat_db, role: str, content: str, at: datetime, context_id: str | None = None) -> Message:
    message = await message_repo.create(user_id=OWNER, role=role, content=content, context_id=context_id)
    async with chat_db.session() as session:
        stored = (await session.execute(select(Message).where(Message.id == message.id))).scalar_one()
        stored.created_at = at
        await session.commit()
        await session.refresh(stored)
    return stored


async def _turn(chat_db, model, text: str, at: datetime) -> dict:
    """The user says `text` at `at`, the router places it, and Maggie answers 30 s later."""
    asked = await _store(chat_db, "user", text, at)
    with patch("app.llm.contexts._now", lambda: at + timedelta(seconds=5), create=True):
        resolution = await route_message(_client(model), text, OWNER, message_id=asked.id)
    assert resolution is not None, "the router gave up"
    await _store(chat_db, "assistant", f"Réponse à : {text}", at + timedelta(seconds=30), resolution["id"])
    model.said(text, resolution["id"])
    return resolution


@pytest.fixture()
def evening(chat_db):
    """The coffee reminder of 17:57 — the thread the evening started from."""

    async def _setup(model) -> str:
        coffee = await context_repo.create(OWNER, "Rappel café dans 1 minute")
        await _store(chat_db, "user", "rappelle-moi le café dans 1 minute", START - timedelta(minutes=16), coffee.id)
        await _store(chat_db, "assistant", "C'est noté, je vous le rappelle.", START - timedelta(minutes=15), coffee.id)
        model.said("rappelle-moi le café dans 1 minute", coffee.id)
        return coffee.id

    return _setup


class TestOneDiscussionStaysInOneThread:
    async def test_the_discussion_of_the_evening_is_one_thread(self, chat_db, evening):
        model = RouterAsOnTheEvening()
        await evening(model)

        threads = []
        for minute, text in zip((0, 2, 4, 6, 8), DISCUSSION, strict=True):
            resolution = await _turn(chat_db, model, text, START + timedelta(minutes=minute))
            threads.append(resolution["id"])

        assert len(set(threads)) == 1, f"one discussion was split in {len(set(threads))} threads"
        async with chat_db.session() as session:
            asked = (await session.execute(select(Message).where(Message.role == "user"))).scalars().all()
        tagged = {m.context_id for m in asked if m.content in DISCUSSION}
        assert tagged == {threads[0]}

    async def test_a_change_of_subject_opens_a_thread_and_keeps_the_last_turns(self, chat_db, evening):
        model = RouterAsOnTheEvening()
        await evening(model)
        for minute, text in zip((0, 2, 4, 6, 8), DISCUSSION, strict=True):
            discussion = await _turn(chat_db, model, text, START + timedelta(minutes=minute))

        at = START + timedelta(minutes=9)
        asked = await _store(chat_db, "user", "changeons de sujet : mes courses", at)
        with patch("app.llm.contexts._now", lambda: at + timedelta(seconds=5), create=True):
            resolution = await route_message(_client(model), asked.content, OWNER, message_id=asked.id)

        assert resolution["action"] == "created"
        assert resolution["id"] != discussion["id"]

        turns = await build_history(OWNER, context_id=resolution["id"], current_message_id=asked.id)
        sent = "\n".join(turn["content"] for turn in turns)
        # The last two exchanges of the discussion it leaves are still in front of the model.
        for text in DISCUSSION[-2:]:
            assert text in sent
            assert f"Réponse à : {text}" in sent
        assert turns[-1] == {"role": "user", "content": "changeons de sujet : mes courses"}
        assert not any(turn["content"].startswith("[fil") for turn in turns if turn["role"] == "assistant")


BIRTHDAY = [
    "Je prépare l'anniversaire de Lucie samedi",
    "et du coup, on commence par quoi ?",
    "tu te souviens pour qui c'était ?",
]


class TestTheShippedScenarios:
    """The fake-llm scenarios of the one-discussion journey, through the real router and history.

    What the e2e journey asserts, without the stack: three linked messages a minute apart
    land in one thread, and the third is answered from the first. The global window is cut
    to two messages, as on the e2e stack, so the first message can only reach the third
    answer through the thread.
    """

    async def test_three_linked_messages_stay_in_one_thread(self, chat_db):
        client = FakeAnthropicClient(fixtures_dir=DEFAULT_FIXTURES_DIR)
        # A discussion already in progress, as in the journey: the tests before it talked.
        greeting = await context_repo.create(OWNER, "Conversation e2e")
        await _store(chat_db, "user", "Bonjour Maggie", START - timedelta(minutes=2), greeting.id)
        await _store(chat_db, "assistant", "Bonjour !", START - timedelta(minutes=2), greeting.id)

        labels, answers = [], []
        for minute, text in enumerate(BIRTHDAY):
            at = START + timedelta(minutes=minute)
            asked = await _store(chat_db, "user", text, at)
            with patch("app.llm.contexts._now", lambda at=at: at + timedelta(seconds=5), create=True):
                resolution = await route_message(client, text, OWNER, message_id=asked.id)
            labels.append(resolution["label"])
            with patch("app.llm.history.settings") as window:
                window.context_history_messages = 40
                window.recent_history_messages = 2
                turns = await build_history(OWNER, context_id=resolution["id"], current_message_id=asked.id)
            reply = await client.messages.create(model="fake", max_tokens=100, system="Tu es Maggie.", messages=turns)
            answer = "".join(block.text for block in reply.content)
            answers.append(answer)
            await _store(chat_db, "assistant", answer, at + timedelta(seconds=30), resolution["id"])

        assert labels == ["Anniversaire e2e"] * 3
        assert answers[2] == "Bien sûr : c'est pour l'anniversaire de Lucie, samedi."


def _thread(context_id: str, label: str, minutes_ago: int = 1) -> MagicMock:
    ctx = MagicMock()
    ctx.id = context_id
    ctx.label = label
    ctx.summary = None
    ctx.updated_at = datetime.now(UTC) - timedelta(minutes=minutes_ago)
    return ctx


def _previous(context_id: str | None, minutes_ago: float, content: str = "Il me faut de la farine") -> MagicMock:
    row = MagicMock()
    row.id = f"msg-{minutes_ago}"
    row.role = "user"
    row.content = content
    row.context_id = context_id
    row.created_at = datetime.now(UTC) - timedelta(minutes=minutes_ago)
    return row


def _answering(answer: str | Exception) -> MagicMock:
    client = MagicMock()
    if isinstance(answer, Exception):
        client.messages.create = AsyncMock(side_effect=answer)
        return client
    response = MagicMock()
    response.content = [MagicMock(text=answer)]
    response.usage.input_tokens = 10
    response.usage.output_tokens = 5
    client.messages.create = AsyncMock(return_value=response)
    return client


async def _route(client, text: str, previous: list, threads: list | None = None):
    created = MagicMock()
    created.id = "ctx-new"
    threads = threads if threads is not None else [_thread("ctx-courses", "Courses"), _thread("ctx-budget", "Budget", 30)]
    with (
        patch("app.llm.contexts.context_repo") as contexts,
        patch("app.llm.contexts.message_repo") as messages,
    ):
        contexts.find_active = AsyncMock(return_value=threads)
        contexts.create = AsyncMock(return_value=created)
        contexts.touch = AsyncMock()
        # Oldest first, like the repository.
        messages.find_recent = AsyncMock(return_value=[*previous, _current()])
        messages.update_context = AsyncMock()
        resolution = await route_message(client, text, "user-1", message_id="msg-current")
    return resolution, contexts


def _current() -> MagicMock:
    row = MagicMock()
    row.id = "msg-current"
    row.role = "user"
    row.content = "le message routé"
    row.context_id = None
    row.created_at = datetime.now(UTC)
    return row


class TestTheTimeThreshold:
    async def test_a_message_soon_after_the_previous_one_offers_the_discussion_first(self):
        client = _answering('{"context_id": "ctx-courses"}')

        resolution, _ = await _route(client, "et du beurre aussi", [_previous("ctx-courses", 2)])

        assert resolution["id"] == "ctx-courses"
        prompt = client.messages.create.await_args.kwargs["messages"][0]["content"]
        assert "Il me faut de la farine" in prompt
        assert 'id="ctx-courses"' in prompt and "← fil en cours" in prompt

    async def test_past_the_threshold_the_router_places_it_freely(self):
        client = _answering('{"context_id": null, "label": "Budget du mois"}')

        resolution, _ = await _route(client, "où en est mon budget", [_previous("ctx-courses", 20)])

        assert resolution["action"] == "created"
        prompt = client.messages.create.await_args.kwargs["messages"][0]["content"]
        assert "← fil en cours" not in prompt
        assert "Il me faut de la farine" not in prompt

    async def test_a_previous_message_in_a_closed_thread_leaves_the_router_free(self):
        client = _answering('{"context_id": null, "label": "Neuf"}')

        resolution, _ = await _route(client, "bonjour", [_previous("ctx-closed", 1)])

        assert resolution["action"] == "created"

    async def test_a_history_that_will_not_load_leaves_the_router_free(self):
        client = _answering('{"context_id": "ctx-budget"}')
        with (
            patch("app.llm.contexts.context_repo") as contexts,
            patch("app.llm.contexts.message_repo") as messages,
        ):
            contexts.find_active = AsyncMock(return_value=[_thread("ctx-budget", "Budget")])
            contexts.touch = AsyncMock()
            messages.find_recent = AsyncMock(side_effect=RuntimeError("no database"))
            messages.update_context = AsyncMock()

            resolution = await route_message(client, "où en est mon budget", "user-1", message_id="msg-current")

        assert resolution["id"] == "ctx-budget"


class TestInDoubtItStays:
    @pytest.mark.parametrize(
        "answer",
        [
            "je ne sais pas trop",
            '{"context_id": "ctx-made-up"}',
            '{"context_id": null}',
            '{"context_id": "ctx-courses", "label": "Courses"}',
        ],
    )
    async def test_an_answer_that_settles_nothing_keeps_the_discussion(self, answer):
        resolution, contexts = await _route(_answering(answer), "et du coup ?", [_previous("ctx-courses", 1)])

        assert resolution["action"] == "matched"
        assert resolution["id"] == "ctx-courses"
        contexts.create.assert_not_awaited()

    async def test_a_router_that_fails_keeps_the_discussion(self):
        resolution, _ = await _route(_answering(RuntimeError("overloaded")), "et du coup ?", [_previous("ctx-courses", 1)])

        assert resolution["id"] == "ctx-courses"

    async def test_a_message_the_router_finds_unrelated_opens_a_thread(self):
        resolution, contexts = await _route(
            _answering('{"context_id": null, "label": "Météo demain"}'),
            "quel temps fera-t-il demain",
            [_previous("ctx-courses", 1)],
        )

        assert resolution["action"] == "created"
        contexts.create.assert_awaited_once_with("user-1", "Météo demain")

    async def test_the_router_can_still_go_back_to_another_thread(self):
        resolution, _ = await _route(
            _answering('{"context_id": "ctx-budget"}'), "reprends mon budget", [_previous("ctx-courses", 1)]
        )

        assert resolution["id"] == "ctx-budget"


class TestAnExplicitRequestChangesTheThread:
    @pytest.mark.parametrize(
        "text",
        [
            "changeons de sujet : mes courses",
            "Bon, on change de sujet. Mon budget ?",
            "Autre chose : la météo",
            "parlons d'autre chose",
            "rien à voir, mais il fait beau",
            "Nouveau sujet : le jardin",
        ],
    )
    async def test_it_leaves_the_discussion(self, text):
        client = _answering('{"context_id": null, "label": "Autre sujet"}')

        resolution, _ = await _route(client, text, [_previous("ctx-courses", 1)])

        assert resolution["action"] == "created"
        prompt = client.messages.create.await_args.kwargs["messages"][0]["content"]
        # The thread the user is leaving is not on offer.
        assert 'id="ctx-courses"' not in prompt

    async def test_it_can_go_back_to_an_older_thread(self):
        resolution, _ = await _route(
            _answering('{"context_id": "ctx-budget"}'), "changeons de sujet : mon budget", [_previous("ctx-courses", 1)]
        )

        assert resolution["id"] == "ctx-budget"

    async def test_a_router_that_fails_still_opens_a_thread(self):
        resolution, _ = await _route(
            _answering(RuntimeError("overloaded")), "changeons de sujet : le jardin", [_previous("ctx-courses", 1)]
        )

        assert resolution["action"] == "created"

    async def test_the_router_cannot_keep_the_thread_the_user_left(self):
        resolution, _ = await _route(
            _answering('{"context_id": "ctx-courses"}'), "changeons de sujet : le jardin", [_previous("ctx-courses", 1)]
        )

        assert resolution["action"] == "created"

    @pytest.mark.parametrize(
        "text",
        [
            "tu peux ajouter autre chose ?",
            "il n'y a rien à voir dans mon agenda ?",
            "c'est un autre sujet qui me tracasse, la farine",
        ],
    )
    async def test_the_words_in_the_middle_of_a_sentence_are_not_a_request(self, text):
        client = _answering('{"context_id": "ctx-courses"}')

        resolution, _ = await _route(client, text, [_previous("ctx-courses", 1)])

        assert resolution["id"] == "ctx-courses"


class TestTheDecisionIsLogged:
    async def test_staying_says_why(self, caplog):
        with caplog.at_level("INFO", logger="app.llm.contexts"):
            await _route(_answering('{"context_id": "ctx-courses"}'), "et du beurre", [_previous("ctx-courses", 2)])

        assert re.search(r"stayed in 'Courses'.*discussion", caplog.text)

    async def test_leaving_says_why(self, caplog):
        with caplog.at_level("INFO", logger="app.llm.contexts"):
            await _route(
                _answering('{"context_id": null, "label": "Jardin"}'),
                "changeons de sujet : le jardin",
                [_previous("ctx-courses", 2)],
            )

        assert re.search(r"opened 'Jardin'.*asked to change", caplog.text)
