"""coverage.py plugin: the dynamic context of a traced frame is the journey it runs for.

coverage.py asks `dynamic_context` on each function call made while no context is open, and
closes the context when that frame returns. An `asyncio` task resuming is such a call — its
coroutine frame is re-entered from the event loop — and suspending is a return: so each step of
each task opens the context of the journey in *its* context variables, and two journeys running
side by side never share a label. Needs the C or Python tracer (`COVERAGE_CORE=ctrace`): the
`sysmon` core does not support dynamic contexts.

Loaded by `coveragerc` (`plugins = app.e2e_coverage.plugin`), before uvicorn imports the app —
the same module object then, so the same context variable the middleware sets.
"""

from types import FrameType
from typing import Any

from coverage import CoveragePlugin

from app.e2e_coverage import current_journey


class JourneyContext(CoveragePlugin):
    def dynamic_context(self, frame: FrameType) -> str | None:
        return current_journey.get()


def coverage_init(reg: Any, options: dict[str, Any]) -> None:
    reg.add_dynamic_context(JourneyContext())
