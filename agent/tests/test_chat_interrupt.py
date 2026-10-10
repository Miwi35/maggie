"""`POST /chat/interrupt` — the owner cut Maggie off (MAG-223), and what she is told about it next turn."""

from datetime import UTC, datetime, timedelta

import pytest
from sqlalchemy import select
from sqlalchemy.exc import IntegrityError

from app.db.message_repository import message_repo
from app.db.models import NOTHING_SAID, Message
from app.llm.history import build_history
from app.llm.streaming import answer_message_id


async def _write(session_factory, **fields) -> None:
    async with session_factory() as session:
        session.add(Message(**fields))
        await session.commit()


async def _rows(session_factory) -> list[Message]:
    async with session_factory() as session:
        result = await session.execute(select(Message).order_by(Message.created_at))
        return list(result.scalars().all())


def _ask(client, **body):
    return client.post("/chat/interrupt", json=body)


class TestInterruptEndpoint:
    def test_requires_auth(self, client):
        assert _ask(client, messageId="m1", spokenText="Alors").status_code in (401, 403)

    @pytest.mark.parametrize(
        "body",
        [
            {"messageId": "", "spokenText": "Alors"},
            {"messageId": "x" * 27, "spokenText": "Alors"},
            {"messageId": "m1", "spokenText": "a" * 20001},
            {"messageId": "m1", "spokenText": 12},
        ],
    )
    def test_refuses_bad_input(self, authed_client, chat_db, body):
        assert _ask(authed_client, **body).status_code in (400, 422)

    async def test_refuses_to_cut_a_message_the_user_said(self, authed_client, chat_db):
        await _write(chat_db.session, id="u1", user_id="test-user", role="user", content="Salut")

        assert _ask(authed_client, messageId="u1", spokenText="Sa").status_code == 400

        assert [(r.content, r.interrupted) for r in await _rows(chat_db.session)] == [("Salut", False)]

    async def test_another_users_answer_is_not_found_and_stays_whole(self, authed_client, chat_db):
        await _write(chat_db.session, id="a1", user_id="someone-else", role="assistant", content="Un long récit")

        assert _ask(authed_client, messageId="a1", spokenText="Un").status_code == 404

        assert [(r.content, r.interrupted) for r in await _rows(chat_db.session)] == [("Un long récit", False)]

    async def test_stream_cut_mid_answer_stores_what_was_shown_in_the_thread(self, authed_client, chat_db):
        """The stream was closed before the server stored anything: the answer is created from the client's text."""
        await _write(chat_db.session, id="u1", user_id="test-user", role="user", content="Raconte", context_id="ctx-1")

        response = _ask(authed_client, messageId="a1", spokenText="Il était une fois un")

        assert response.status_code == 200
        assert response.json()["interrupted"] is True
        rows = await _rows(chat_db.session)
        assert [(r.id, r.role, r.content, r.context_id, r.interrupted) for r in rows] == [
            ("u1", "user", "Raconte", "ctx-1", False),
            ("a1", "assistant", "Il était une fois un", "ctx-1", True),
        ]

    async def test_cut_after_the_answer_was_stored_shortens_it_in_place(self, authed_client, chat_db):
        """Voice: the whole answer is already in the history, but only its start was heard."""
        await _write(chat_db.session, id="u1", user_id="test-user", role="user", content="Raconte")
        await _write(
            chat_db.session,
            id="a1",
            user_id="test-user",
            role="assistant",
            content="Il était une fois un roi qui avait trois fils.",
        )

        response = _ask(authed_client, messageId="a1", spokenText="Il était une fois un roi")

        assert response.status_code == 200
        rows = await _rows(chat_db.session)
        assert [(r.id, r.content, r.interrupted) for r in rows if r.role == "assistant"] == [
            ("a1", "Il était une fois un roi", True)
        ]

    async def test_cut_before_a_word_leaves_a_row_that_says_so(self, authed_client, chat_db):
        await _write(chat_db.session, id="u1", user_id="test-user", role="user", content="Raconte", context_id="ctx-1")

        assert _ask(authed_client, spokenText="").status_code == 200

        assistant = [r for r in await _rows(chat_db.session) if r.role == "assistant"]
        assert [(r.content, r.context_id, r.interrupted) for r in assistant] == [(NOTHING_SAID, "ctx-1", True)]

    async def test_cut_before_a_word_takes_the_id_the_running_turn_will_store_under(self, authed_client, chat_db):
        """The turn outlives the stream: its later insert must be refused, not land beside the cut row."""
        await _write(chat_db.session, id="u1", user_id="test-user", role="user", content="Raconte")

        _ask(authed_client, spokenText="")

        assistant = [r for r in await _rows(chat_db.session) if r.role == "assistant"]
        assert [r.id for r in assistant] == [answer_message_id("u1")]
        with pytest.raises(IntegrityError):
            await message_repo.create(
                user_id="test-user",
                role="assistant",
                content="Il était une fois un roi.",
                message_id=answer_message_id("u1"),
            )
        assert [r.content for r in await _rows(chat_db.session) if r.role == "assistant"] == [NOTHING_SAID]

    async def test_is_idempotent(self, authed_client, chat_db):
        await _write(chat_db.session, id="u1", user_id="test-user", role="user", content="Raconte")

        _ask(authed_client, messageId="a1", spokenText="Il était")
        _ask(authed_client, messageId="a1", spokenText="Il était")

        assert [r.id for r in await _rows(chat_db.session) if r.role == "assistant"] == ["a1"]

    async def test_is_published_so_other_windows_follow(self, authed_client, chat_db):
        published = []

        async def record(topic, payload):
            published.append(payload)

        await _write(chat_db.session, id="u1", user_id="test-user", role="user", content="Raconte")
        from unittest.mock import patch

        with patch.object(message_repo.publisher, "publish", record):
            _ask(authed_client, messageId="a1", spokenText="Il était")

        assert [(p["id"], p["content"], p["interrupted"]) for p in published] == [("a1", "Il était", True)]


class TestHistoryAfterAnInterruption:
    async def _history(self, chat_db, content: str):
        now = datetime.now(UTC)
        await _write(
            chat_db.session,
            id="u1",
            user_id="u",
            role="user",
            content="Raconte-moi une histoire",
            context_id="ctx-1",
            created_at=now - timedelta(minutes=3),
        )
        await _write(
            chat_db.session,
            id="a1",
            user_id="u",
            role="assistant",
            content=content,
            context_id="ctx-1",
            interrupted=True,
            created_at=now - timedelta(minutes=2),
        )
        await _write(
            chat_db.session,
            id="u2",
            user_id="u",
            role="user",
            content="Plutôt une blague",
            context_id="ctx-1",
            created_at=now - timedelta(minutes=1),
        )
        return await build_history("u", context_id="ctx-1", current_message_id="u2")

    async def test_the_answer_is_what_was_said_followed_by_the_marker(self, chat_db):
        turns = await self._history(chat_db, "Il était une fois un roi")

        assistant = turns[1]
        assert assistant["role"] == "assistant"
        assert assistant["content"].startswith("Il était une fois un roi")
        assert "coupé la parole" in assistant["content"]
        assert "ne reprends pas" in assistant["content"].lower()
        assert turns[-1] == {"role": "user", "content": "Plutôt une blague"}

    async def test_an_answer_cut_before_a_word_says_nothing_was_said(self, chat_db):
        turns = await self._history(chat_db, NOTHING_SAID)

        assistant = turns[1]["content"]
        assert NOTHING_SAID not in assistant
        assert "coupé la parole" in assistant
        assert "avant" in assistant

    async def test_an_answer_that_was_not_cut_is_left_alone(self, chat_db):
        now = datetime.now(UTC)
        await _write(
            chat_db.session, id="u1", user_id="u", role="user", content="Salut", created_at=now - timedelta(minutes=2)
        )
        await _write(
            chat_db.session,
            id="a1",
            user_id="u",
            role="assistant",
            content="Bonjour !",
            created_at=now - timedelta(minutes=1),
        )

        turns = await build_history("u", current_message_id="zz")

        assert turns[1] == {"role": "assistant", "content": "Bonjour !"}
