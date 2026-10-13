"""Stop Maggie and correct the message, as on Claude (MAG-320)."""

import asyncio
import json
from datetime import UTC, datetime, timedelta
from unittest.mock import AsyncMock, patch

import pytest
from sqlalchemy import select

from app.db.message_repository import message_repo
from app.db.models import NOTHING_SAID, Message
from app.llm import replace
from app.llm.history import build_history
from app.llm.replace import carried_over_note, done_actions, pending_approvals, withdraw_turn
from app.llm.streaming import StreamingGateway, answer_message_id
from app.llm.tool_blocks import record
from app.llm.turns import TurnRunner
from tests.test_streaming import TestWhatTheToolsSaidIsStored

EVENT_CREATED = '{"id": "evt-1", "title": "Test à elle"}'
CALLS = [
    {"id": "toolu_1", "name": "create_event", "input": {"title": "Test à elle"}, "result": EVENT_CREATED},
    {"id": "toolu_2", "name": "get_event", "input": {"id": "x"}, "result": '{"error": "Introuvable"}'},
    {
        "id": "toolu_3",
        "name": "delete_event",
        "input": {"id": "evt-0"},
        "result": json.dumps({"status": "pending_approval", "approval_id": "appr-1"}),
    },
]


async def _write(session_factory, **fields) -> None:
    async with session_factory() as session:
        session.add(Message(**fields))
        await session.commit()


async def _rows(session_factory) -> list[Message]:
    async with session_factory() as session:
        result = await session.execute(select(Message).order_by(Message.created_at))
        return list(result.scalars().all())


async def _turn(chat_db, *, user_id="test-user", blocks=None, answer="Fait.", interrupted=False):
    """A question of the user and its answer, stored the way the streamed path stores them."""
    now = datetime.now(UTC)
    await _write(chat_db.session, id="u1", user_id=user_id, role="user", content="Crée un événement test à elle",
                 context_id="ctx-1", created_at=now - timedelta(minutes=1))
    await _write(chat_db.session, id=answer_message_id("u1"), user_id=user_id, role="assistant", content=answer,
                 context_id="ctx-1", blocks=blocks, interrupted=interrupted, created_at=now - timedelta(seconds=50))


class TestWhatAReplacedTurnLeavesBehind:
    def test_only_the_calls_that_went_through_are_listed(self):
        actions = done_actions(record(CALLS))

        assert actions == ['create_event({"title":"Test à elle"})']

    def test_a_held_call_is_an_approval_to_abandon_not_an_action(self):
        assert pending_approvals(record(CALLS)) == ["appr-1"]

    @pytest.mark.parametrize("blocks", [None, [], "x", [{"role": "assistant"}], [1, 2]])
    def test_blocks_that_are_not_rounds_say_nothing(self, blocks):
        assert done_actions(blocks) == []
        assert pending_approvals(blocks) == []

    def test_the_list_is_bounded(self):
        many = [{"id": f"t{i}", "name": "add_item", "input": {"name": "x" * 500}, "result": "{}"} for i in range(30)]

        actions = done_actions(record(many))

        assert len(actions) == 10
        assert all(len(a) <= 200 for a in actions)

    def test_a_turn_that_did_nothing_adds_no_note(self):
        assert carried_over_note([]) is None

    def test_the_note_names_the_actions_and_says_they_were_not_undone(self):
        note = carried_over_note(['create_event({"title":"Test"})'])

        assert 'create_event({"title":"Test"})' in note
        assert "pas été défaites" in note


class TestTheModelIsToldOnTheCorrectedMessage:
    async def test_the_note_rides_the_message_being_answered_and_no_other(self, chat_db):
        now = datetime.now(UTC)
        await _write(chat_db.session, id="u0", user_id="u", role="user", content="Salut", created_at=now - timedelta(minutes=5))
        await _write(chat_db.session, id="a0", user_id="u", role="assistant", content="Bonjour", created_at=now - timedelta(minutes=4))
        await _write(chat_db.session, id="u2", user_id="u", role="user", content="Crée un événement test à Ael", created_at=now)

        turns = await build_history("u", current_message_id="u2", carried_over="[Message corrigé : x]")

        assert turns[-1]["content"] == "Crée un événement test à Ael\n\n[Message corrigé : x]"
        assert "Message corrigé" not in turns[0]["content"]


class TestWithdrawTurn:
    async def test_the_question_and_its_answer_are_removed_and_announced(self, chat_db):
        await _turn(chat_db)

        note = await withdraw_turn("test-user", "u1")

        assert note is None
        assert await _rows(chat_db.session) == []
        payload = chat_db.chat_published.await_args.args[1]
        assert payload["deleted"] is True
        assert sorted(payload["messageIds"]) == sorted(["u1", answer_message_id("u1")])

    async def test_what_the_answers_tools_did_is_carried_and_its_cards_abandoned(self, chat_db):
        await _turn(chat_db, blocks=record(CALLS))

        with patch.object(replace.pending_action_repo, "decide", AsyncMock()) as decide:
            note = await withdraw_turn("test-user", "u1")

        assert 'create_event({"title":"Test à elle"})' in note
        decide.assert_awaited_once()
        assert decide.await_args.args[0] == "appr-1"

    async def test_only_the_last_message_can_be_replaced(self, chat_db):
        await _turn(chat_db)
        await _write(chat_db.session, id="u2", user_id="test-user", role="user", content="Et un autre")

        with pytest.raises(Exception) as refused:
            await withdraw_turn("test-user", "u1")

        assert refused.value.status_code == 400
        assert len(await _rows(chat_db.session)) == 3

    async def test_another_users_message_is_not_found_and_stays(self, chat_db):
        await _turn(chat_db, user_id="someone-else")

        with pytest.raises(Exception) as refused:
            await withdraw_turn("test-user", "u1")

        assert refused.value.status_code == 404
        assert len(await _rows(chat_db.session)) == 2

    async def test_an_answer_of_maggie_is_not_a_message_to_replace(self, chat_db):
        await _turn(chat_db)

        with pytest.raises(Exception) as refused:
            await withdraw_turn("test-user", answer_message_id("u1"))

        assert refused.value.status_code == 400

    async def test_a_message_already_replaced_is_nothing_to_take_back(self, chat_db):
        assert await withdraw_turn("test-user", "gone") is None


def _stream(client, **body):
    async def gateway_run(message, user_id, user_msg_id, **kwargs):
        gateway_run.calls.append({"message": message, "id": user_msg_id, **kwargs})
        yield {"type": "RUN_STARTED", "runId": "run-1"}
        yield {"type": "RUN_FINISHED", "runId": "run-1"}

    gateway_run.calls = []
    with patch("app.api.routes.streaming_gateway") as gateway:
        gateway.chat_stream = gateway_run
        response = client.post("/chat/stream", json=body)
    return response, gateway_run.calls


class TestReplacingThroughTheStream:
    def test_requires_auth(self, client):
        response = client.post("/chat/stream", json={"message": "Bonjour", "replaces": "u1"})

        assert response.status_code in (401, 403)

    @pytest.mark.parametrize("replaces", ["", "x" * 27, 12])
    def test_refuses_a_bad_id(self, authed_client, chat_db, replaces):
        response, _ = _stream(authed_client, message="Bonjour", replaces=replaces)

        assert response.status_code == 422

    async def test_the_corrected_message_takes_the_place_of_the_old_turn(self, authed_client, chat_db):
        await _turn(chat_db, blocks=record(CALLS[:1]))

        response, calls = await asyncio.to_thread(
            _stream, authed_client, message="Crée un événement test à Ael", replaces="u1"
        )
        await TurnRunner().idle()

        assert response.status_code == 200
        rows = await _rows(chat_db.session)
        assert [(r.role, r.content) for r in rows] == [("user", "Crée un événement test à Ael")]
        assert calls[0]["message"] == "Crée un événement test à Ael"
        assert 'create_event({"title":"Test à elle"})' in calls[0]["carried_over"]

    async def test_the_same_words_sent_again_are_a_new_turn_not_the_old_one_replayed(self, authed_client, chat_db):
        await _turn(chat_db)

        _, calls = await asyncio.to_thread(
            _stream, authed_client, message="Crée un événement test à elle", replaces="u1"
        )

        assert len(calls) == 1
        assert calls[0]["carried_over"] is None

    async def test_refused_when_it_is_not_the_last_message(self, authed_client, chat_db):
        await _turn(chat_db)
        await _write(chat_db.session, id="u2", user_id="test-user", role="user", content="Plus récent")

        response, calls = await asyncio.to_thread(_stream, authed_client, message="Autre", replaces="u1")

        assert response.status_code == 400
        assert calls == []
        assert len(await _rows(chat_db.session)) == 3


class TestStoppingATurn:
    async def test_stopping_cancels_the_running_turn_and_settles_its_message(self, chat_db):
        message = await message_repo.create(
            user_id="u", role="user", content="Raconte", turn_lease_until=datetime.now(UTC) + timedelta(seconds=30)
        )
        seen = {}

        class Slow:
            async def chat_stream(self, message_text, user_id, user_msg_id, stopped=None, **_kwargs):
                seen["stopped"] = stopped
                yield {"type": "RUN_STARTED", "runId": "r"}
                await asyncio.sleep(30)

        runner = TurnRunner()
        runner.start(Slow(), user_id="u", message_id=message.id, message="Raconte", follow=False)
        await asyncio.sleep(0.05)

        assert await runner.stop(message.id) is True

        await runner.idle()
        stored = await message_repo.get(message.id)
        assert stored.turn_status is None
        assert seen["stopped"]() is False  # forgotten once it is over

    async def test_stopping_a_turn_that_is_not_running_does_nothing(self):
        assert await TurnRunner().stop("nothing") is False

    async def test_a_shutdown_is_not_a_stop(self, chat_db):
        message = await message_repo.create(
            user_id="u", role="user", content="Raconte", turn_lease_until=datetime.now(UTC) + timedelta(seconds=30)
        )

        class Slow:
            async def chat_stream(self, *_args, **_kwargs):
                yield {"type": "RUN_STARTED", "runId": "r"}
                await asyncio.sleep(30)

        runner = TurnRunner()
        runner.start(Slow(), user_id="u", message_id=message.id, message="Raconte", follow=False)
        await asyncio.sleep(0.05)

        await runner.shutdown()

        stored = await message_repo.get(message.id)
        assert stored.turn_status == "running"


class TestAStoppedTurnKeepsWhatItDid:
    @staticmethod
    async def _run(gw, stopped, release):
        with (
            patch("app.llm.contexts.context_repo") as contexts,
            patch("app.llm.streaming.context_repo") as thread,
            patch("app.llm.streaming.message_repo") as messages,
            patch("app.llm.streaming.build_history", AsyncMock(return_value=[{"role": "user", "content": "Crée"}])),
            patch("app.llm.streaming.skill_index") as skills,
            patch("app.llm.streaming.context_summarizer"),
        ):
            contexts.find_active = AsyncMock(return_value=[])
            thread.append_tool_call = AsyncMock(return_value=None)
            skills.get_skills_index.return_value = ""
            skills.refresh = AsyncMock()
            messages.create = AsyncMock()

            async def consume():
                async for _ in gw.chat_stream("Crée", "user-1", "msg-1", stopped=stopped):
                    pass

            task = asyncio.create_task(consume())
            await asyncio.sleep(0.05)
            task.cancel()
            await asyncio.sleep(0.05)
            release.set()
            with pytest.raises(asyncio.CancelledError):
                await task
            return messages.create

    @staticmethod
    def _gateway():
        release = asyncio.Event()
        gw = TestWhatTheToolsSaidIsStored._gateway("create_event")

        async def slow_tool(*_args, **_kwargs):
            await release.wait()
            return EVENT_CREATED

        gw.tool_router.call_tool = slow_tool
        return gw, release

    async def test_a_tool_already_acting_is_let_finish_and_recorded_on_an_interrupted_answer(self):
        gw, release = self._gateway()

        create = await self._run(gw, lambda: True, release)

        create.assert_awaited_once()
        kwargs = create.await_args.kwargs
        assert kwargs["interrupted"] is True
        assert kwargs["content"] == NOTHING_SAID
        assert kwargs["message_id"] == answer_message_id("msg-1")
        assert done_actions(kwargs["blocks"]) == ["create_event({\"includeDeferred\":false})"]

    async def test_a_cancellation_that_is_not_the_users_keeps_nothing(self):
        gw, release = self._gateway()

        create = await self._run(gw, lambda: False, release)

        create.assert_not_awaited()

    async def test_a_reading_is_not_an_action_to_keep_or_delete(self):
        assert done_actions(record([{"id": "t", "name": "get_event", "input": {}, "result": EVENT_CREATED}])) == []


class TestAStopThatOutlastsItsWait:
    async def test_the_turn_still_knows_it_was_stopped_when_its_tool_finally_returns(self, chat_db):
        message = await message_repo.create(
            user_id="u", role="user", content="Raconte", turn_lease_until=datetime.now(UTC) + timedelta(seconds=30)
        )
        verdict = {}

        class SlowTool:
            async def chat_stream(self, *_args, stopped=None, **_kwargs):
                yield {"type": "RUN_STARTED", "runId": "r"}
                try:
                    await asyncio.sleep(30)
                except asyncio.CancelledError:
                    await asyncio.sleep(0.2)  # a tool already acting, slower than the wait
                    verdict["stopped"] = stopped()
                    raise

        runner = TurnRunner()
        runner.start(SlowTool(), user_id="u", message_id=message.id, message="Raconte", follow=False)
        await asyncio.sleep(0.05)

        with patch("app.llm.turns.STOP_WAIT_SECONDS", 0.05):
            assert await runner.stop(message.id) is True
        await runner.idle()

        assert verdict == {"stopped": True}
        assert (await message_repo.get(message.id)).turn_status is None


class TestInterruptStopsTheTurn:
    async def test_a_turn_still_running_is_stopped_and_its_waiting_cards_abandoned(self, authed_client, chat_db):
        await _turn(chat_db, blocks=record(CALLS), interrupted=True)

        with (
            patch("app.api.routes.turn_runner.stop", AsyncMock(return_value=True)) as stop,
            patch("app.api.routes.abandon_approvals", AsyncMock()) as abandon,
        ):
            response = authed_client.post("/chat/interrupt", json={"spokenText": "Je regarde"})

        assert response.status_code == 200
        stop.assert_awaited_once_with("u1")
        assert abandon.await_args.args[0] == record(CALLS)
        assert [r.content for r in await _rows(chat_db.session) if r.role == "assistant"] == ["Je regarde"]

    async def test_a_turn_that_is_not_running_is_left_to_the_voice_path(self, authed_client, chat_db):
        await _turn(chat_db)

        with (
            patch("app.api.routes.turn_runner.stop", AsyncMock(return_value=False)),
            patch("app.api.routes.abandon_approvals", AsyncMock()) as abandon,
        ):
            response = authed_client.post("/chat/interrupt", json={"spokenText": "Fait"})

        assert response.status_code == 200
        abandon.assert_not_awaited()
