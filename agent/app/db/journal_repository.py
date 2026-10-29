"""The journal of what Maggie did on her own (MAG-688): every daily planning and every proaction run.

What a run did is read from the tool loop's own record (`tool_calls`): the instructions the planning
read are what `list_instructions` returned, the proactions it scheduled what `schedule_proaction`
answered. Nothing is asked of the model to keep it.
"""

import json
from datetime import datetime
from typing import Any

from sqlalchemy import delete, select

from app.db.agent_engine import agent_session
from app.db.journal_model import COMPLETED, FAILED, PLANNING, PROACTION, JournalRun

RESULT_MAX_CHARS = 2000  # a tool result is kept this long: enough to read the error or the first items
INSTRUCTIONS_TOOL = "list_instructions"
SCHEDULE_TOOL = "schedule_proaction"


def _parsed(result: Any) -> Any:
    if not isinstance(result, str):
        return result
    try:
        return json.loads(result)
    except ValueError:
        return None


def tool_error(result: Any) -> str | None:
    """The error a tool answered with (`{"error": …}`, the shape every tool fails in), or None."""
    data = _parsed(result)
    if isinstance(data, dict) and data.get("error"):
        return str(data["error"])
    return None


def journal_tool_calls(tool_calls: list[dict]) -> list[dict]:
    """The calls as the journal keeps them: arguments, the result cut short, and the error it carried."""
    kept = []
    for call in tool_calls:
        result = call.get("result")
        text = result if isinstance(result, str) else json.dumps(result, ensure_ascii=False, default=str)
        kept.append(
            {
                "name": call.get("name"),
                "input": call.get("input") or {},
                "result": text[:RESULT_MAX_CHARS] + ("…" if len(text) > RESULT_MAX_CHARS else ""),
                "error": tool_error(result),
            }
        )
    return kept


def instructions_read(tool_calls: list[dict]) -> list[dict]:
    """The instructions the run read, each once, in the order `list_instructions` gave them."""
    seen: dict[str, dict] = {}
    for call in tool_calls:
        if call.get("name") != INSTRUCTIONS_TOOL:
            continue
        data = _parsed(call.get("result"))
        for item in data if isinstance(data, list) else []:
            if isinstance(item, dict) and item.get("content"):
                key = str(item.get("id") or item["content"])
                seen.setdefault(key, {"id": item.get("id"), "content": item["content"], "kind": item.get("kind")})
    return list(seen.values())


def proactions_scheduled(tool_calls: list[dict]) -> list[dict]:
    """The proactions the run did schedule: the calls the API took, not the ones it refused."""
    scheduled = []
    for call in tool_calls:
        if call.get("name") != SCHEDULE_TOOL:
            continue
        data = _parsed(call.get("result"))
        proaction = data.get("proaction") if isinstance(data, dict) else None
        if not isinstance(proaction, dict):
            continue
        arguments = call.get("input") or {}
        scheduled.append(
            {
                "id": str(proaction.get("id")) if proaction.get("id") else None,
                "prompt": proaction.get("prompt") or arguments.get("prompt"),
                "summary": proaction.get("summary") or arguments.get("summary"),
                "scheduledAt": proaction.get("scheduledAt") or arguments.get("scheduled_at"),
            }
        )
    return scheduled


def _error(result: dict | None, error: str | None) -> str | None:
    """The error the run raised, or the one the gateway answered with instead of raising (`error`)."""
    if error is not None:
        return error
    answered = (result or {}).get("error")
    return str(answered) if answered else None


def _tokens(result: dict | None) -> tuple[int, int]:
    usage = (result or {}).get("usage") or {}
    return int(usage.get("input_tokens", 0)), int(usage.get("output_tokens", 0))


class JournalRepository:
    async def _store(self, run: JournalRun) -> JournalRun:
        async with agent_session() as session:
            session.add(run)
            await session.commit()
            await session.refresh(run)
            return run

    async def record_planning(
        self,
        user_id: str,
        day: str,
        started_at: datetime,
        duration_ms: int,
        result: dict | None = None,
        error: str | None = None,
    ) -> JournalRun:
        """Keep one planning run: what it read and scheduled from `result`, or the error it raised."""
        tool_calls = (result or {}).get("tool_calls", [])
        input_tokens, output_tokens = _tokens(result)
        error = _error(result, error)
        return await self._store(
            JournalRun(
                user_id=user_id,
                kind=PLANNING,
                status=FAILED if error is not None else COMPLETED,
                day=day,
                started_at=started_at,
                duration_ms=duration_ms,
                instructions=instructions_read(tool_calls),
                scheduled=proactions_scheduled(tool_calls),
                tool_calls=journal_tool_calls(tool_calls),
                response=(result or {}).get("response"),
                error=error,
                input_tokens=input_tokens,
                output_tokens=output_tokens,
            )
        )

    async def record_proaction(
        self,
        user_id: str,
        proaction_id: str,
        prompt: str,
        summary: str | None,
        day: str,
        started_at: datetime,
        duration_ms: int,
        result: dict | None = None,
        error: str | None = None,
    ) -> JournalRun:
        """Keep one proaction run: its calls, duration and tokens, then its message or its error."""
        tool_calls = (result or {}).get("tool_calls", [])
        input_tokens, output_tokens = _tokens(result)
        error = _error(result, error)
        return await self._store(
            JournalRun(
                user_id=user_id,
                kind=PROACTION,
                status=FAILED if error is not None else COMPLETED,
                day=day,
                started_at=started_at,
                duration_ms=duration_ms,
                proaction_id=proaction_id,
                prompt=prompt,
                summary=summary,
                tool_calls=journal_tool_calls(tool_calls),
                response=(result or {}).get("response"),
                error=error,
                input_tokens=input_tokens,
                output_tokens=output_tokens,
            )
        )

    async def find_by_user(self, user_id: str, since: datetime) -> list[JournalRun]:
        """The user's runs started since `since`, newest first."""
        async with agent_session() as session:
            result = await session.execute(
                select(JournalRun)
                .where(JournalRun.user_id == user_id, JournalRun.started_at >= since)
                .order_by(JournalRun.started_at.desc())
            )
            return list(result.scalars().all())

    async def purge(self, before: datetime) -> int:
        """Remove every run started before `before`; how many went."""
        async with agent_session() as session:
            result = await session.execute(delete(JournalRun).where(JournalRun.started_at < before))
            await session.commit()
            return int(result.rowcount or 0)


journal_repo = JournalRepository()
