import enum
import uuid
from datetime import UTC, datetime, timedelta

from sqlalchemy import Column, DateTime, Enum, Index, String, Text
from sqlalchemy.dialects.postgresql import JSONB

from app.db.proaction_model import AgentBase
from app.policy.summary import action_label

# How long the user has to answer. After that the action is `expired` rather than
# run: approving « supprime l'événement de demain » three days later would act on
# a sentence whose meaning has moved on.
PENDING_TTL = timedelta(hours=24)


class PendingActionStatus(enum.StrEnum):
    PENDING = "pending"
    APPROVED = "approved"
    DENIED = "denied"
    EXPIRED = "expired"
    FAILED = "failed"


def _expiry() -> datetime:
    return datetime.now(UTC) + PENDING_TTL


class PendingAction(AgentBase):
    """A tool call the policy holds back until the user answers it (MAG-4).

    The arguments are frozen here: what is run on approval is what Maggie asked
    for, not what she would ask for now.
    """

    __tablename__ = "agent_pending_action"
    __table_args__ = (
        Index("idx_agent_pending_action_user_status", "user_id", "status"),
        Index("idx_agent_pending_action_status_expires", "status", "expires_at"),
    )

    id = Column(String(26), primary_key=True, default=lambda: str(uuid.uuid4().hex[:26]))
    user_id = Column(String(36), nullable=False)
    tool_name = Column(String(100), nullable=False)
    arguments = Column(JSONB, nullable=False, default=dict)
    source = Column(String(50), nullable=False)
    context_id = Column(String(32), nullable=True)
    status = Column(
        Enum(PendingActionStatus, native_enum=False, length=20),
        nullable=False,
        default=PendingActionStatus.PENDING,
    )
    # What the card says, built when the action is held (`app.policy.summary`). Null on the
    # actions held before MAG-7: they are served with the label of their action instead.
    summary = Column(Text, nullable=True)
    result = Column(Text, nullable=True)
    created_at = Column(DateTime(timezone=True), nullable=False, default=lambda: datetime.now(UTC))
    decided_at = Column(DateTime(timezone=True), nullable=True)
    expires_at = Column(DateTime(timezone=True), nullable=False, default=_expiry)

    def to_dict(self) -> dict:
        return {
            "id": self.id,
            "userId": self.user_id,
            "toolName": self.tool_name,
            "arguments": self.arguments,
            "summary": self.summary or action_label(self.tool_name, self.arguments),
            "source": self.source,
            "contextId": self.context_id,
            "status": self.status.value if self.status else None,
            "result": self.result,
            "createdAt": self.created_at.isoformat() if self.created_at else None,
            "decidedAt": self.decided_at.isoformat() if self.decided_at else None,
            "expiresAt": self.expires_at.isoformat() if self.expires_at else None,
        }
