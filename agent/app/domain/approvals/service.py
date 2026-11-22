"""Approving, denying and announcing a held action (MAG-5, MAG-336), without FastAPI."""

import json
import logging
from dataclasses import dataclass
from datetime import UTC, datetime
from typing import TYPE_CHECKING

from app.db.message_repository import message_repo
from app.db.pending_action_model import PendingAction, PendingActionStatus
from app.db.pending_action_repository import pending_action_repo
from app.domain.approvals.errors import ApprovalExpired, ApprovalNotFound, ApprovalNotPending, UnknownChannel
from app.llm.context_summary import context_summarizer
from app.llm.models import CHAT, effort_for, model_for
from app.llm.runner import run_tool_loop
from app.policy.decision import CHANNELS, record_decision

if TYPE_CHECKING:
    from app.llm.gateway import LLMGateway

logger = logging.getLogger(__name__)


@dataclass(frozen=True)
class Approved:
    action: PendingAction
    result: str


def _is_overdue(action: PendingAction) -> bool:
    expires_at = action.expires_at
    if expires_at.tzinfo is None:
        expires_at = expires_at.replace(tzinfo=UTC)
    return expires_at <= datetime.now(UTC)


def _is_error_result(result: str) -> bool:
    try:
        parsed = json.loads(result)
    except (TypeError, ValueError):
        return False
    return isinstance(parsed, dict) and "error" in parsed


def _check_channel(channel: str) -> str:
    """The surface the answer came from, `chat` unless the client says otherwise."""
    if channel not in CHANNELS:
        raise UnknownChannel(CHANNELS)
    return channel


async def _answerable(user_id: str, approval_id: str) -> PendingAction:
    """The user's action if it can still be answered.

    The owner is part of the query: someone else's action is as unknown as a made-up id.
    """
    action = await pending_action_repo.get_for_user(user_id, approval_id)
    if action is None:
        raise ApprovalNotFound()
    if action.status == PendingActionStatus.EXPIRED:
        raise ApprovalExpired()
    if action.status != PendingActionStatus.PENDING:
        raise ApprovalNotPending()
    if _is_overdue(action):
        raise ApprovalExpired()
    return action


class ApprovalService:
    def __init__(self, *, gateway: "LLMGateway"):
        self.gateway = gateway

    async def approve(self, approval_id: str, *, user_id: str, channel: str = "chat") -> Approved:
        """Run the held call with its frozen arguments and record the outcome. Does not announce.

        The row is claimed as approved *before* the call runs: two tabs clicking at once, or the
        scheduler expiring the action meanwhile, get `ApprovalNotPending` instead of a second
        execution. The one who wins the claim also leaves the answer in the thread (MAG-336).
        """
        channel = _check_channel(channel)
        action = await _answerable(user_id, approval_id)

        claimed = await pending_action_repo.decide(approval_id, PendingActionStatus.APPROVED)
        if claimed is None:
            raise ApprovalNotPending()
        await record_decision(claimed, channel)

        try:
            result = await self.gateway.tool_router.call_tool(
                action.tool_name, action.arguments or {}, user_id, source="approval"
            )
        except Exception as e:
            logger.error(f"Approved action {approval_id} failed to run: {e}")
            result = json.dumps({"error": f"Tool call failed: {e}"})

        status = PendingActionStatus.FAILED if _is_error_result(result) else PendingActionStatus.APPROVED
        settled = await pending_action_repo.settle(approval_id, status, result)
        return Approved(settled or claimed, result)

    async def deny(self, approval_id: str, *, user_id: str, channel: str = "chat") -> PendingAction:
        """Refuse the held call: nothing runs and the model is not called. The refusal stays in the thread (MAG-336)."""
        channel = _check_channel(channel)
        await _answerable(user_id, approval_id)

        denied = await pending_action_repo.decide(approval_id, PendingActionStatus.DENIED)
        if denied is None:
            raise ApprovalNotPending()
        await record_decision(denied, channel)
        return denied

    async def announce(self, approved: Approved) -> None:
        """Have Maggie say what the approved action did, in the thread it was asked in.

        Never raises: the action is already done and recorded, and a failed announcement must not
        look like a failed approval. Same ending as `execute_proaction` — the message is stored (and
        published on the chat topic by `message_repo`) in the thread, which is then re-summarized.
        """
        action, result = approved.action, approved.result
        if self.gateway.client is None:
            return

        prompt = (
            f"L'utilisateur a validé {action.tool_name}({json.dumps(action.arguments, ensure_ascii=False)}). "
            f"Résultat : {result}. Annonce-le brièvement."
        )
        try:
            system = await self.gateway._build_system_prompt(action.user_id, current_context_id=action.context_id)
            answer = await run_tool_loop(
                system,
                [{"role": "user", "content": prompt}],
                None,
                client=self.gateway.client,
                tool_router=self.gateway.tool_router,
                user_id=action.user_id,
                model=model_for(CHAT),
                effort=effort_for(CHAT),
                call_type="approval",
                source="approval",
                context_id=action.context_id,
            )
            if not answer["response"]:
                return
            await message_repo.create(
                user_id=action.user_id, role="assistant", content=answer["response"], context_id=action.context_id
            )
            if action.context_id:
                await context_summarizer.maybe_summarize(action.context_id)
        except Exception as e:
            logger.error(f"Announcement of approved action {action.id} failed: {e}")
