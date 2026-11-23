"""The one builder of Maggie's system prompt (MAG-952).

A static base, the same for every assistant, and the owner's customizations, each injected at its own place.
The final prompt is their aggregate, written once, here, in this order:

1. 1 h block: the base (template, personalization, capabilities line), then the skills index;
2. 5 min block: the skills of the moment;
3. 5 min block: the memory profile;
4. the recalled memory;
5. the volatile part: memory notice, directives, open threads, date, work hours, day context,
   last exchange, the run's preamble, the spoken rule, the driving rule.

What the prompt reads comes from `PromptSources`, wired by the assembly (`app/prompt_sources.py`): this module
imports no domain module.
"""

import asyncio
from dataclasses import dataclass
from datetime import date, datetime
from typing import Protocol

from app.llm.capabilities import generate_capability_summary
from app.llm.prompt_cache import build_system


class MemoryParts(Protocol):
    notice: str
    profile: str
    recalled: str


class PromptSources(Protocol):
    """What the prompt reads, provided by the assembly."""

    async def base(self, user_id: str, capabilities: str) -> str: ...

    async def skills_index(self) -> str: ...

    def moment_skills(self, moment: str) -> str: ...

    async def timezone(self, user_id: str): ...

    async def memory(
        self, user_id: str, about: str, *, source: str, context_id: str | None, today: date, planned: bool = False
    ) -> MemoryParts: ...

    async def directives(self, user_id: str) -> str: ...

    async def contexts(self, user_id: str, current_context_id: str | None, contexts: list | None) -> str: ...

    async def last_exchange(self, user_id: str, *, exclude_message_id: str | None, tz) -> str: ...

    async def work_hours(self, user_id: str, now: datetime) -> str: ...

    async def day_context(self, user_id: str, day: date) -> str | None: ...

    def datetime_line(self, tz) -> str: ...

    def spoken_rules(self) -> str: ...

    def driving_rules(self) -> str: ...

    def warm_up(self, user_id: str) -> None: ...


@dataclass(frozen=True)
class ThreadFreeParts:
    """The parts of the system prompt that do not depend on the thread (loaded beside the router)."""

    base: str
    skills: str
    tz: object
    directives: str
    last_exchange: str
    work_hours: str
    day_context: str | None


class SystemPrompt:
    def __init__(self, sources: PromptSources):
        self.sources = sources

    def warm_up(self, user_id: str) -> None:
        """Start the memory's bucket catch-up now, so it overlaps what the caller does before `build`."""
        self.sources.warm_up(user_id)

    async def thread_free(
        self,
        user_id: str,
        tools: list[dict] | None = None,
        *,
        moment: str | None = None,
        exclude_message_id: str | None = None,
        tz=None,
    ) -> ThreadFreeParts:
        """What the prompt holds that does not depend on the thread the message is routed into,
        so it loads while the router thinks."""
        sources = self.sources
        if tz is None:
            tz = await sources.timezone(user_id)
        capabilities = generate_capability_summary(tools) if tools else ""

        async def base_and_skills() -> tuple[str, str]:
            base = await sources.base(user_id, capabilities)
            index = await sources.skills_index()
            return base + index, sources.moment_skills(moment) if moment else ""

        async def work_hours() -> str:
            return await sources.work_hours(user_id, datetime.now(tz))

        async def day_context() -> str | None:
            return await sources.day_context(user_id, datetime.now(tz).date())

        (base, skills), directives, last_exchange, work, day = await asyncio.gather(
            base_and_skills(),
            sources.directives(user_id),
            sources.last_exchange(user_id, exclude_message_id=exclude_message_id, tz=tz),
            work_hours(),
            day_context(),
        )
        return ThreadFreeParts(
            base=base,
            skills=skills,
            tz=tz,
            directives=directives,
            last_exchange=last_exchange,
            work_hours=work,
            day_context=day,
        )

    async def build(
        self,
        user_id: str,
        tools: list[dict] | None = None,
        preamble: str = "",
        *,
        exclude_message_id: str | None = None,
        current_context_id: str | None = None,
        moment: str | None = None,
        about: str = "",
        source: str = "chat",
        spoken: bool = False,
        in_car: bool = False,
        parts: ThreadFreeParts | None = None,
        open_threads: list | None = None,
        tz=None,
    ) -> list[dict]:
        """The system blocks, in the order of the module's aggregate.

        `spoken`: the reply will be read aloud (live voice, voice message, car), so it is asked for as a
        spoken text (ADR-014), in the volatile part, never in the cached prefix.
        `in_car`: the owner is driving (MAG-684): short answers for the ear, same place.
        The recall is planned by the fast model only for a written turn of the owner (his message stored,
        neither spoken nor driving).
        `parts` is what does not depend on the thread, when the caller loaded it already;
        `open_threads` the threads it already read (the section is built from them).
        """
        sources = self.sources
        if parts is None:
            parts = await self.thread_free(user_id, tools, moment=moment, exclude_message_id=exclude_message_id, tz=tz)
        tz = parts.tz
        planned = source == "chat" and exclude_message_id is not None and not (spoken or in_car)
        memory, context_section = await asyncio.gather(
            sources.memory(
                user_id,
                about,
                source=source,
                context_id=current_context_id,
                today=datetime.now(tz).date(),
                planned=planned,
            ),
            sources.contexts(user_id, current_context_id, open_threads),
        )
        day_context_block = f"\n{parts.day_context}" if parts.day_context else ""
        volatile = (
            f"{memory.notice}{parts.directives}{context_section}\n\n{sources.datetime_line(tz)}\n{parts.work_hours}"
            f"{day_context_block}{parts.last_exchange}{preamble}"
        )
        if spoken or in_car:
            volatile = f"{volatile}\n\n{sources.spoken_rules()}"
        if in_car:
            volatile = f"{volatile}\n\n{sources.driving_rules()}"
        return build_system(parts.base, volatile, parts.skills, memory.profile, memory.recalled)
