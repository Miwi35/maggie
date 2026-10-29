import uuid
from datetime import UTC, datetime

from sqlalchemy import Column, DateTime, Index, Integer, String, Text
from sqlalchemy.dialects.postgresql import JSONB

from app.db.base import AgentBase

PLANNING = "planning"
PROACTION = "proaction"

COMPLETED = "completed"
FAILED = "failed"


class JournalRun(AgentBase):
    """One run of the daily planning or of a proaction, kept to explain what Maggie did on her own (MAG-688).

    Before it, the planning's output went to the logs only and a failed proaction kept its error alone:
    nobody could tell why a digest did not come, or came wrong. Purged past
    `proaction_journal_retention_days`.
    """

    __tablename__ = "agent_journal_run"
    __table_args__ = (Index("idx_agent_journal_run_user_started", "user_id", "started_at"),)

    id = Column(String(32), primary_key=True, default=lambda: uuid.uuid4().hex)
    user_id = Column(String(36), nullable=False)
    kind = Column(String(16), nullable=False)  # planning, proaction
    status = Column(String(16), nullable=False)  # completed, failed
    # The day planned, on the user's wall clock; for a proaction, the day it ran.
    day = Column(String(10), nullable=False)
    started_at = Column(DateTime(timezone=True), nullable=False, default=lambda: datetime.now(UTC))
    duration_ms = Column(Integer, nullable=False, default=0)
    # A proaction's own: what it was asked, its summary and its id on the API — the id is what ties it to
    # the planning run that scheduled it, whose instructions are its origin.
    proaction_id = Column(String(36), nullable=True)
    prompt = Column(Text, nullable=True)
    summary = Column(Text, nullable=True)
    # A planning's own: the instructions `list_instructions` returned, and the proactions it scheduled.
    instructions = Column(JSONB, nullable=True)
    scheduled = Column(JSONB, nullable=True)
    # Every tool called, with its arguments, its result cut short, and the error the result carried.
    tool_calls = Column(JSONB, nullable=True)
    response = Column(Text, nullable=True)
    error = Column(Text, nullable=True)
    input_tokens = Column(Integer, nullable=False, default=0)
    output_tokens = Column(Integer, nullable=False, default=0)

    def to_dict(self) -> dict:
        return {
            "id": self.id,
            "kind": self.kind,
            "status": self.status,
            "day": self.day,
            "startedAt": self.started_at.isoformat() if self.started_at else None,
            "durationMs": int(self.duration_ms or 0),
            "proactionId": self.proaction_id,
            "prompt": self.prompt,
            "summary": self.summary,
            "instructions": list(self.instructions or []),
            "scheduled": list(self.scheduled or []),
            "toolCalls": list(self.tool_calls or []),
            "response": self.response,
            "error": self.error,
            "inputTokens": int(self.input_tokens or 0),
            "outputTokens": int(self.output_tokens or 0),
        }
