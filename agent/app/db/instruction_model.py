import enum
import uuid
from datetime import UTC, datetime

from sqlalchemy import Column, DateTime, Enum, Index, String, Text

from app.db.proaction_model import AgentBase


class InstructionKind(enum.StrEnum):
    """What a directive is for — and therefore who reads it (MAG-22).

    `PLANNING` says *when* Maggie may act on her own (« résume-moi la journée à 9h »,
    « ne me dérange pas après 21h ») and is read once a day, by the proaction planning.
    `BEHAVIOR` says *how* she answers (« tutoie-moi », « moins d'emojis ») and is read
    on every turn, from the system prompt — a preference nobody injects is a preference
    the user stated and never saw applied.
    """

    PLANNING = "planning"
    BEHAVIOR = "behavior"


class Instruction(AgentBase):
    """A guideline stored by the user: a planning rule, or a behaviour preference."""

    __tablename__ = "instruction"
    # Left on `user_id` alone: a user has a handful of directives, so the `kind` filter
    # costs nothing to apply on top, and renaming the index would only ever take effect
    # on a database created from scratch.
    __table_args__ = (Index("idx_instruction_user", "user_id"),)

    id = Column(String(32), primary_key=True, default=lambda: uuid.uuid4().hex)
    user_id = Column(String(36), nullable=False)
    content = Column(Text, nullable=False)
    # Stored as the member *value*, not its name: the column is added to an existing
    # table by an `ALTER … DEFAULT 'planning'` (see `run_migrations`), and the rows it
    # backfills have to read back as this enum.
    kind = Column(
        Enum(
            InstructionKind,
            native_enum=False,
            length=20,
            values_callable=lambda enum_class: [member.value for member in enum_class],
        ),
        nullable=False,
        default=InstructionKind.PLANNING,
    )
    created_at = Column(DateTime(timezone=True), nullable=False, default=lambda: datetime.now(UTC))
    updated_at = Column(
        DateTime(timezone=True),
        nullable=False,
        default=lambda: datetime.now(UTC),
        onupdate=lambda: datetime.now(UTC),
    )

    def to_dict(self) -> dict:
        return {
            "id": self.id,
            "userId": self.user_id,
            "content": self.content,
            "kind": InstructionKind(self.kind).value if self.kind else InstructionKind.PLANNING.value,
            "createdAt": self.created_at.isoformat() if self.created_at else None,
            "updatedAt": self.updated_at.isoformat() if self.updated_at else None,
        }
