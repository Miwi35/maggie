"""The trace of a turn (part A of the agent spec, MAG-995): every tool call, its arguments, its result and its duration.

Measured around the router, not inside the loop: the loop's own record (`tool_calls`) stays free of timings,
and the calls are timed the same whether a round runs them one by one or side by side.
"""

import time


class TracingToolRouter:
    """A ToolRouter that keeps, in the order they were made, the calls of the turn and how long each took."""

    def __init__(self, router):
        self._router = router
        self.calls: list[dict] = []

    def __getattr__(self, name: str):
        # Transparent to what the wrapped router keeps: the checker's `decisions`, the dry run's `simulated`.
        if name == "_router":
            raise AttributeError(name)
        return getattr(self._router, name)

    async def get_tool_definitions(self, *args, **kwargs) -> list[dict]:
        return await self._router.get_tool_definitions(*args, **kwargs)

    async def call_tool(self, name: str, arguments: dict, **kwargs) -> str:
        call = {"name": name, "input": arguments, "result": None, "durationMs": 0}
        self.calls.append(call)
        started = time.monotonic()
        try:
            result = await self._router.call_tool(name, arguments, **kwargs)
        except Exception as exc:
            call["result"] = str(exc)
            raise
        finally:
            call["durationMs"] = int((time.monotonic() - started) * 1000)
        call["result"] = result
        return result
