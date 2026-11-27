"""Approving, denying and announcing a held action, without a route (MAG-885).

Real queries on an in-memory database (`pending_db`), Mercure publication mocked, a fake gateway.
"""

import json
from datetime import UTC, datetime, timedelta
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from tests.prompt_helpers import stub_system_prompt
from app.container import Container
from app.db.pending_action_model import PendingActionStatus
from app.db.pending_action_repository import pending_action_repo
from app.domain.approvals.errors import (
    ApprovalExpired,
    ApprovalNotFound,
    ApprovalNotPending,
    UnknownChannel,
)
from app.domain.approvals.service import Approved, ApprovalService
from tests.test_pending_action_repository import OTHER_USER, USER, hold, write

SVC = "app.domain.approvals.service"


@pytest.fixture()
def gateway():
    fake = MagicMock()
    fake.tool_router.call_tool = AsyncMock(return_value='{"deleted": true}')
    return fake


@pytest.fixture()
def service(gateway):
    return ApprovalService(gateway=gateway, system_prompt=stub_system_prompt([]))


@pytest.fixture()
def recorded():
    with patch(f"{SVC}.record_decision", AsyncMock()) as record:
        yield record


@pytest.fixture()
def loop():
    with patch(f"{SVC}.run_tool_loop", AsyncMock(return_value={"response": "Fait.", "tool_calls": []})) as run:
        yield run


async def stored(action_id):
    return await pending_action_repo.get_for_user(USER, str(action_id))


class TestApprove:
    async def test_approve_runs_the_frozen_arguments_once_and_settles_the_result(
        self, pending_db, service, gateway, recorded
    ):
        action = await hold()

        approved = await service.approve(str(action.id), user_id=USER)

        gateway.tool_router.call_tool.assert_awaited_once_with(
            "delete_event", {"eventId": "evt-1"}, USER, source="approval"
        )
        row = await stored(action.id)
        assert row.status == PendingActionStatus.APPROVED
        assert row.result == '{"deleted": true}'
        assert approved.result == '{"deleted": true}'
        assert approved.action.status == PendingActionStatus.APPROVED

    async def test_approving_twice_runs_the_call_once(self, pending_db, service, gateway, recorded):
        action = await hold()

        await service.approve(str(action.id), user_id=USER)
        with pytest.raises(ApprovalNotPending):
            await service.approve(str(action.id), user_id=USER)

        assert gateway.tool_router.call_tool.await_count == 1

    async def test_an_error_result_settles_as_failed(self, pending_db, service, gateway, recorded):
        gateway.tool_router.call_tool = AsyncMock(return_value=json.dumps({"error": "Event not found"}))
        action = await hold()

        await service.approve(str(action.id), user_id=USER)

        assert (await stored(action.id)).status == PendingActionStatus.FAILED

    async def test_a_raising_call_settles_as_failed(self, pending_db, service, gateway, recorded):
        gateway.tool_router.call_tool = AsyncMock(side_effect=RuntimeError("boom"))
        action = await hold()

        await service.approve(str(action.id), user_id=USER)

        row = await stored(action.id)
        assert row.status == PendingActionStatus.FAILED
        assert "boom" in row.result

    async def test_an_overdue_action_is_expired_and_runs_nothing(self, pending_db, service, gateway, recorded):
        action = write(
            pending_db.session,
            user_id=USER,
            tool_name="delete_event",
            arguments={},
            source="chat",
            status=PendingActionStatus.PENDING,
            expires_at=datetime.now(UTC) - timedelta(minutes=1),
        )

        with pytest.raises(ApprovalExpired):
            await service.approve(str(action.id), user_id=USER)

        gateway.tool_router.call_tool.assert_not_awaited()
        assert (await stored(action.id)).status == PendingActionStatus.PENDING
        recorded.assert_not_awaited()

    async def test_an_action_the_scheduler_expired_is_expired(self, pending_db, service, gateway, recorded):
        action = write(
            pending_db.session,
            user_id=USER,
            tool_name="delete_event",
            arguments={},
            source="chat",
            status=PendingActionStatus.EXPIRED,
            expires_at=datetime.now(UTC) + timedelta(hours=1),
        )

        with pytest.raises(ApprovalExpired):
            await service.approve(str(action.id), user_id=USER)

        gateway.tool_router.call_tool.assert_not_awaited()

    async def test_another_users_action_is_not_found(self, pending_db, service, gateway, recorded):
        action = await hold(user_id=OTHER_USER)

        with pytest.raises(ApprovalNotFound):
            await service.approve(str(action.id), user_id=USER)

        gateway.tool_router.call_tool.assert_not_awaited()

    async def test_an_unknown_channel_decides_nothing(self, pending_db, service, gateway, recorded):
        action = await hold()

        with pytest.raises(UnknownChannel, match="Unknown channel, one of chat, notification, voice"):
            await service.approve(str(action.id), user_id=USER, channel="fax")
        with pytest.raises(UnknownChannel):
            await service.deny(str(action.id), user_id=USER, channel="fax")

        assert (await stored(action.id)).status == PendingActionStatus.PENDING
        recorded.assert_not_awaited()

    async def test_approve_publishes_the_claim_then_the_result(self, pending_db, service, recorded):
        action = await hold()
        pending_db.published.reset_mock()

        await service.approve(str(action.id), user_id=USER)

        topics = [call.args[0] for call in pending_db.published.await_args_list]
        statuses = [call.args[1]["status"] for call in pending_db.published.await_args_list]
        assert topics == [f"/approvals/{USER}"] * 2
        assert statuses == ["approved", "approved"]
        assert pending_db.published.await_args_list[1].args[1]["result"] == '{"deleted": true}'

    async def test_approve_does_not_call_the_model(self, pending_db, service, recorded, loop):
        action = await hold()

        await service.approve(str(action.id), user_id=USER)

        loop.assert_not_awaited()

    async def test_the_decision_line_is_written_with_the_channel(self, pending_db, service, recorded):
        action = await hold()

        await service.approve(str(action.id), user_id=USER, channel="voice")

        claimed, channel = recorded.await_args.args
        assert str(claimed.id) == str(action.id)
        assert channel == "voice"


class TestDeny:
    async def test_deny_runs_nothing_and_publishes_the_refusal(self, pending_db, service, gateway, recorded, loop):
        action = await hold()
        pending_db.published.reset_mock()

        denied = await service.deny(str(action.id), user_id=USER, channel="notification")

        assert denied.status == PendingActionStatus.DENIED
        gateway.tool_router.call_tool.assert_not_awaited()
        loop.assert_not_awaited()
        pending_db.published.assert_awaited_once()
        assert pending_db.published.await_args.args[1]["status"] == "denied"
        assert recorded.await_args.args[1] == "notification"


class TestAnnounce:
    async def approved_action(self, pending_db):
        action = await hold(context_id="ctx-1")
        return Approved(action, '{"deleted": true}')

    async def test_announce_says_the_result_in_the_original_thread(self, pending_db, service, gateway, loop):
        approved = await self.approved_action(pending_db)
        with patch(f"{SVC}.message_repo") as messages, patch(f"{SVC}.message_service", messages), patch(f"{SVC}.context_summarizer") as summarizer:
            messages.create = AsyncMock()
            summarizer.maybe_summarize = AsyncMock()

            await service.announce(approved)

        args, kwargs = loop.call_args
        assert args[2] is None
        assert "L'utilisateur a validé delete_event" in args[1][0]["content"]
        assert kwargs["source"] == "approval"
        messages.create.assert_awaited_once_with(user_id=USER, role="assistant", content="Fait.", context_id="ctx-1")
        summarizer.maybe_summarize.assert_awaited_once_with("ctx-1")

    async def test_announce_never_raises(self, pending_db, service, loop):
        loop.side_effect = RuntimeError("API down")
        approved = await self.approved_action(pending_db)
        with patch(f"{SVC}.message_repo") as messages, patch(f"{SVC}.message_service", messages):
            messages.create = AsyncMock()

            await service.announce(approved)

        messages.create.assert_not_awaited()

    async def test_announce_without_a_model_does_nothing(self, pending_db, service, gateway, loop):
        gateway.client = None

        await service.announce(await self.approved_action(pending_db))

        loop.assert_not_awaited()


def test_the_container_builds_the_service_on_its_gateway():
    fresh = Container()
    assert fresh.approvals is fresh.approvals
    assert fresh.approvals.gateway is fresh.llm_gateway

    fake = MagicMock()
    with fresh.override(llm_gateway=fake):
        assert fresh.approvals.gateway is fake
    assert fresh.approvals.gateway is fresh.llm_gateway
