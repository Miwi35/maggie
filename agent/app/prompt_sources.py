"""What the system prompt reads, wired to the domain modules (MAG-952).

The only module that imports the personality, the skills, the memory, the directives, the threads, the last
exchange, the timezone, the work hours and the day context for the prompt: `app/runtime/system_prompt.py`
composes, this one only delegates.
"""

from datetime import date, datetime

from app.day_context import resolve_day_context
from app.llm.contexts import active_contexts_section
from app.llm.directives import behavior_directives_section
from app.llm.last_exchange import last_exchange_section
from app.memory.agent_memory import AgentMemory, MemoryBlocks
from app.personality.engine import PersonalityEngine, current_datetime_line
from app.skills.index import skill_index
from app.user_timezone import resolve_user_timezone, resolve_work_hours
from app.work_hours import work_hours_line


class MaggiePromptSources:
    def __init__(self, personality: PersonalityEngine | None = None, agent_memory: AgentMemory | None = None):
        self.personality = personality or PersonalityEngine()
        self.agent_memory = agent_memory or AgentMemory()

    async def base(self, user_id: str, capabilities: str) -> str:
        return await self.personality.get_system_prompt(user_id, capabilities=capabilities)

    async def skills_index(self) -> str:
        await skill_index.refresh()
        return skill_index.get_skills_index()

    def moment_skills(self, moment: str) -> str:
        return skill_index.skills_for_moment(moment)

    async def timezone(self, user_id: str):
        return await resolve_user_timezone(user_id)

    async def memory(
        self, user_id: str, about: str, *, source: str, context_id: str | None, today: date, planned: bool = False
    ) -> MemoryBlocks:
        return await self.agent_memory.blocks(
            user_id, about, source=source, context_id=context_id, today=today, planned=planned
        )

    async def directives(self, user_id: str) -> str:
        return await behavior_directives_section(user_id)

    async def contexts(self, user_id: str, current_context_id: str | None, contexts: list | None) -> str:
        return await active_contexts_section(user_id, current_context_id, contexts=contexts)

    async def last_exchange(self, user_id: str, *, exclude_message_id: str | None, tz) -> str:
        return await last_exchange_section(user_id, exclude_message_id=exclude_message_id, tz=tz)

    async def work_hours(self, user_id: str, now: datetime) -> str:
        return work_hours_line(await resolve_work_hours(user_id), now)

    async def day_context(self, user_id: str, day: date) -> str | None:
        return await resolve_day_context(user_id, day)

    def datetime_line(self, tz) -> str:
        return current_datetime_line(tz=tz)

    def spoken_rules(self) -> str:
        return self.personality.get_spoken_prompt()

    def driving_rules(self) -> str:
        return self.personality.get_driving_prompt()

    def warm_up(self, user_id: str) -> None:
        self.agent_memory.catch_up_soon(user_id)
