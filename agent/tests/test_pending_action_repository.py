"""The actions waiting for the user's answer (MAG-4).

Real queries on an in-memory database (the `pending_db` fixture), with the Mercure
publication mocked: both halves matter, because a card that stays on screen after it
has been answered is a second deletion one click away.
"""

from datetime import UTC, datetime, timedelta

import pytest
from sqlalchemy import update as sa_update

from app.db.pending_action_model import PENDING_TTL, PendingAction, PendingActionStatus
from app.db.pending_action_repository import pending_action_repo

USER = "01HUSER"
OTHER_USER = "01HOTHER"


async def hold(**overrides) -> PendingAction:
    """One held action, with the arguments the tests vary."""
    return await pending_action_repo.create(
        overrides.pop("user_id", USER),
        overrides.pop("tool_name", "delete_event"),
        overrides.pop("arguments", {"eventId": "evt-1"}),
        source=overrides.pop("source", "chat"),
        context_id=overrides.pop("context_id", None),
    )


def write(session_factory, **fields) -> PendingAction:
    """An action put straight into the table, to set up a state `create` cannot reach."""
    action = PendingAction(**fields)
    session = session_factory()._session
    session.add(action)
    session.commit()
    session.refresh(action)
    session.close()
    return action


def move(session_factory, action_id: str, status: PendingActionStatus) -> None:
    """Move an action behind the repository's back, standing in for a concurrent writer."""
    session = session_factory()._session
    session.execute(
        sa_update(PendingAction).where(PendingAction.id == action_id).values(status=status)
    )
    session.commit()
    session.close()


class TestCreate:
    async def test_it_stores_the_call_with_its_arguments_frozen(self, pending_db):
        action = await hold(source="chat_stream", context_id="ctx-1")

        stored = await pending_action_repo.get_for_user(USER, str(action.id))
        assert stored is not None
        assert stored.tool_name == "delete_event"
        assert stored.arguments == {"eventId": "evt-1"}
        assert stored.source == "chat_stream"
        assert stored.context_id == "ctx-1"
        assert stored.status == PendingActionStatus.PENDING
        assert stored.result is None
        assert stored.decided_at is None

    async def test_it_gives_the_user_a_day_to_answer(self, pending_db):
        action = await hold()

        waited = action.expires_at - action.created_at
        assert abs(waited - PENDING_TTL) < timedelta(seconds=5)

    async def test_it_publishes_the_card_on_the_users_approvals_topic(self, pending_db):
        action = await hold()

        pending_db.published.assert_awaited_once()
        topic, payload = pending_db.published.await_args.args
        assert topic == f"/approvals/{USER}"
        assert payload["id"] == action.id
        assert payload["toolName"] == "delete_event"
        assert payload["status"] == "pending"

    async def test_a_publication_that_fails_does_not_lose_the_action(self, pending_db):
        pending_db.published.side_effect = RuntimeError("hub down")

        action = await hold()

        assert await pending_action_repo.get_for_user(USER, str(action.id)) is not None


class TestDeduplication:
    """« Ne réessaie pas » is a sentence in a prompt; two cards for one intention is a bug."""

    async def test_an_identical_call_reuses_the_action_already_waiting(self, pending_db):
        first = await hold()
        second = await hold()

        assert second.id == first.id
        assert pending_db.published.await_count == 1
        assert len(await pending_action_repo.find_pending(USER)) == 1

    async def test_different_arguments_are_a_different_action(self, pending_db):
        first = await hold(arguments={"eventId": "evt-1"})
        second = await hold(arguments={"eventId": "evt-2"})

        assert second.id != first.id
        assert len(await pending_action_repo.find_pending(USER)) == 2

    async def test_another_users_identical_call_is_their_own_action(self, pending_db):
        mine = await hold()
        theirs = await hold(user_id=OTHER_USER)

        assert theirs.id != mine.id

    async def test_an_answered_action_is_not_reused(self, pending_db):
        first = await hold()
        await pending_action_repo.decide(str(first.id), PendingActionStatus.DENIED)

        second = await hold()

        assert second.id != first.id

    async def test_an_overdue_action_is_not_reused(self, pending_db):
        stale = write(
            pending_db.session,
            user_id=USER,
            tool_name="delete_event",
            arguments={"eventId": "evt-1"},
            source="chat",
            status=PendingActionStatus.PENDING,
            created_at=datetime.now(UTC) - timedelta(days=2),
            expires_at=datetime.now(UTC) - timedelta(days=1),
        )

        fresh = await hold()

        assert fresh.id != stale.id


class TestReads:
    async def test_get_for_user_finds_nothing_for_somebody_else(self, pending_db):
        action = await hold()

        assert await pending_action_repo.get_for_user(OTHER_USER, str(action.id)) is None

    async def test_get_for_user_finds_nothing_for_an_unknown_id(self, pending_db):
        await hold()

        assert await pending_action_repo.get_for_user(USER, "does-not-exist") is None

    async def test_find_pending_lists_only_the_users_unanswered_actions_oldest_first(self, pending_db):
        first = await hold(arguments={"eventId": "evt-1"})
        second = await hold(arguments={"eventId": "evt-2"})
        answered = await hold(arguments={"eventId": "evt-3"})
        await pending_action_repo.decide(str(answered.id), PendingActionStatus.APPROVED, "{}")
        await hold(user_id=OTHER_USER)

        pending = await pending_action_repo.find_pending(USER)

        assert [a.id for a in pending] == [first.id, second.id]


class TestDecide:
    async def test_it_writes_the_answer_and_the_result(self, pending_db):
        action = await hold()

        decided = await pending_action_repo.decide(str(action.id), PendingActionStatus.APPROVED, '{"deleted": true}')

        assert decided is not None
        assert decided.status == PendingActionStatus.APPROVED
        assert decided.result == '{"deleted": true}'
        assert decided.decided_at is not None

    async def test_it_publishes_the_answer_so_the_other_surface_drops_the_card(self, pending_db):
        action = await hold()
        pending_db.published.reset_mock()

        await pending_action_repo.decide(str(action.id), PendingActionStatus.DENIED)

        pending_db.published.assert_awaited_once()
        topic, payload = pending_db.published.await_args.args
        assert topic == f"/approvals/{USER}"
        assert payload["status"] == "denied"

    async def test_an_already_answered_action_cannot_be_answered_twice(self, pending_db):
        # Two tabs clicking Autoriser at the same instant must not run the call twice.
        action = await hold()
        await pending_action_repo.decide(str(action.id), PendingActionStatus.APPROVED, "{}")

        assert await pending_action_repo.decide(str(action.id), PendingActionStatus.DENIED) is None

    async def test_an_unknown_action_is_not_a_crash(self, pending_db):
        assert await pending_action_repo.decide("nope", PendingActionStatus.APPROVED) is None

    async def test_an_action_moved_out_of_pending_behind_our_back_is_not_decided(self, pending_db):
        # What the claim protects: the scheduler's sweep retires the action while the
        # user clicks Autoriser. Only `status == PENDING` is written, so the decision is
        # refused whole — MAG-5 must not replay a call on an action no longer waiting.
        # (Sequential here: SQLite on one connection cannot interleave. Atomicity itself
        # rests on the single conditional UPDATE, the shape `ProactionRepository.claim` uses.)
        action = await hold()
        move(pending_db.session, str(action.id), PendingActionStatus.EXPIRED)
        pending_db.published.reset_mock()

        assert await pending_action_repo.decide(str(action.id), PendingActionStatus.APPROVED, "{}") is None

        reread = await pending_action_repo.get_for_user(USER, str(action.id))
        assert reread is not None
        assert reread.status == PendingActionStatus.EXPIRED
        assert reread.result is None
        pending_db.published.assert_not_awaited()

    async def test_a_failed_run_is_a_decision_too(self, pending_db):
        action = await hold()

        decided = await pending_action_repo.decide(str(action.id), PendingActionStatus.FAILED, '{"error": "boom"}')

        assert decided is not None
        assert decided.status == PendingActionStatus.FAILED

    @pytest.mark.parametrize("status", [PendingActionStatus.PENDING, PendingActionStatus.EXPIRED])
    async def test_a_status_the_user_cannot_choose_is_refused(self, pending_db, status):
        action = await hold()

        with pytest.raises(ValueError):
            await pending_action_repo.decide(str(action.id), status)


class TestSettle:
    async def test_it_records_the_result_of_a_claimed_action(self, pending_db):
        action = await hold()
        await pending_action_repo.decide(str(action.id), PendingActionStatus.APPROVED)
        pending_db.published.reset_mock()

        settled = await pending_action_repo.settle(str(action.id), PendingActionStatus.APPROVED, '{"deleted": true}')

        assert settled is not None
        assert settled.status == PendingActionStatus.APPROVED
        assert settled.result == '{"deleted": true}'
        pending_db.published.assert_awaited_once()

    async def test_a_run_that_errored_ends_as_failed(self, pending_db):
        action = await hold()
        await pending_action_repo.decide(str(action.id), PendingActionStatus.APPROVED)

        settled = await pending_action_repo.settle(str(action.id), PendingActionStatus.FAILED, '{"error": "boom"}')

        assert settled is not None
        assert settled.status == PendingActionStatus.FAILED

    async def test_an_action_nobody_claimed_is_not_settled(self, pending_db):
        action = await hold()

        assert await pending_action_repo.settle(str(action.id), PendingActionStatus.APPROVED, "{}") is None

        reread = await pending_action_repo.get_for_user(USER, str(action.id))
        assert reread is not None
        assert reread.status == PendingActionStatus.PENDING

    async def test_a_refused_action_is_not_settled(self, pending_db):
        action = await hold()
        await pending_action_repo.decide(str(action.id), PendingActionStatus.DENIED)

        assert await pending_action_repo.settle(str(action.id), PendingActionStatus.APPROVED, "{}") is None

    @pytest.mark.parametrize("status", [PendingActionStatus.DENIED, PendingActionStatus.EXPIRED])
    async def test_a_run_cannot_end_as_something_else(self, pending_db, status):
        action = await hold()

        with pytest.raises(ValueError):
            await pending_action_repo.settle(str(action.id), status, "{}")


class TestExpireOverdue:
    def _overdue(self, session_factory, **overrides) -> PendingAction:
        return write(
            session_factory,
            user_id=overrides.pop("user_id", USER),
            tool_name="delete_event",
            arguments={"eventId": overrides.pop("event_id", "evt-old")},
            source="chat",
            status=overrides.pop("status", PendingActionStatus.PENDING),
            created_at=datetime.now(UTC) - timedelta(days=2),
            expires_at=datetime.now(UTC) - timedelta(minutes=1),
        )

    async def test_an_unanswered_action_past_its_deadline_expires(self, pending_db):
        stale = self._overdue(pending_db.session)

        assert await pending_action_repo.expire_overdue() == 1

        reread = await pending_action_repo.get_for_user(USER, str(stale.id))
        assert reread is not None
        assert reread.status == PendingActionStatus.EXPIRED
        assert reread.decided_at is not None

    async def test_it_publishes_so_the_card_leaves_the_screen(self, pending_db):
        self._overdue(pending_db.session)

        await pending_action_repo.expire_overdue()

        pending_db.published.assert_awaited_once()
        topic, payload = pending_db.published.await_args.args
        assert topic == f"/approvals/{USER}"
        assert payload["status"] == "expired"

    async def test_an_action_still_within_its_day_is_left_alone(self, pending_db):
        fresh = await hold()
        pending_db.published.reset_mock()

        assert await pending_action_repo.expire_overdue() == 0

        reread = await pending_action_repo.get_for_user(USER, str(fresh.id))
        assert reread is not None
        assert reread.status == PendingActionStatus.PENDING
        pending_db.published.assert_not_awaited()

    async def test_an_already_answered_action_is_not_reopened_as_expired(self, pending_db):
        answered = self._overdue(pending_db.session, status=PendingActionStatus.APPROVED)

        assert await pending_action_repo.expire_overdue() == 0

        reread = await pending_action_repo.get_for_user(USER, str(answered.id))
        assert reread is not None
        assert reread.status == PendingActionStatus.APPROVED

    async def test_it_sweeps_every_user(self, pending_db):
        self._overdue(pending_db.session, event_id="evt-a")
        self._overdue(pending_db.session, user_id=OTHER_USER, event_id="evt-b")

        assert await pending_action_repo.expire_overdue() == 2
        assert pending_db.published.await_count == 2

    async def test_nothing_overdue_publishes_nothing(self, pending_db):
        assert await pending_action_repo.expire_overdue() == 0
        pending_db.published.assert_not_awaited()
