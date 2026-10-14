import enum
import uuid
from datetime import UTC, datetime

from sqlalchemy import Column, DateTime, Enum, Index, String, Text

from app.db.base import AgentBase


class StyleProposalStatus(enum.StrEnum):
    """Where a proposed directive stands (MAG-23).

    Nothing is applied on its own: a proposal only becomes a behaviour directive once the
    owner accepts it. A refused one is kept, so the next synthesis does not offer it again.
    """

    PENDING = "pending"
    ACCEPTED = "accepted"
    REJECTED = "rejected"


class StyleProposal(AgentBase):
    """A behaviour directive the style synthesis drew from the owner's thumbs, awaiting their word."""

    __tablename__ = "agent_style_proposal"
    __table_args__ = (Index("idx_agent_style_proposal_user", "user_id", "status"),)

    id = Column(String(32), primary_key=True, default=lambda: uuid.uuid4().hex)
    user_id = Column(String(36), nullable=False)
    content = Column(Text, nullable=False)
    # Why it is proposed, in one line: the owner decides on it, so they have to see what it rests on.
    reason = Column(Text, nullable=True)
    status = Column(
        Enum(
            StyleProposalStatus,
            native_enum=False,
            length=16,
            values_callable=lambda enum_class: [member.value for member in enum_class],
        ),
        nullable=False,
        default=StyleProposalStatus.PENDING,
    )
    created_at = Column(DateTime(timezone=True), nullable=False, default=lambda: datetime.now(UTC))
    decided_at = Column(DateTime(timezone=True), nullable=True)

    def to_dict(self) -> dict:
        return {
            "id": self.id,
            "content": self.content,
            "reason": self.reason,
            "status": StyleProposalStatus(self.status).value,
            "createdAt": self.created_at.isoformat() if self.created_at else None,
            "decidedAt": self.decided_at.isoformat() if self.decided_at else None,
        }
