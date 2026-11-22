"""The agent's own tools, registered by the assembly (MAG-945).

This module knows no domain: `app/native_tools.py` imports the tools of each domain and
registers them here, so the router dispatches every native tool through one path.
"""

from collections.abc import Awaitable, Callable
from dataclasses import dataclass, replace


@dataclass(frozen=True)
class ToolCallContext:
    """What a handler may need besides its arguments: who calls, from where, in which thread."""

    user_id: str
    source: str
    context_id: str | None


Handler = Callable[[dict, ToolCallContext], Awaitable[str]]


def _always() -> bool:
    return True


@dataclass(frozen=True)
class NativeTool:
    definition: dict | Callable[[], dict]
    handler: Handler
    offered: Callable[[], bool] = _always

    def current_definition(self) -> dict:
        return self.definition() if callable(self.definition) else self.definition

    @property
    def name(self) -> str:
        return self.current_definition()["name"]


class NativeToolRegistry:
    def __init__(self) -> None:
        self._tools: dict[str, NativeTool] = {}

    def register(self, tool: NativeTool) -> None:
        name = tool.name
        if name in self._tools:
            raise ValueError(f"Native tool '{name}' is already registered")
        self._tools[name] = tool

    def definitions(self) -> list[dict]:
        """The tools to offer the model now, in registration order."""
        return [tool.current_definition() for tool in self._tools.values() if tool.offered()]

    def names(self) -> frozenset[str]:
        """Every registered name, offered or not: an MCP tool by the same name is not offered."""
        return frozenset(self._tools)

    def get(self, name: str) -> NativeTool | None:
        return self._tools.get(name)

    def required_fields(self, name: str) -> list[str]:
        tool = self._tools.get(name)
        if tool is None:
            return []
        schema = tool.current_definition().get("input_schema") or {}
        return list(schema.get("required", []))

    def with_handler(self, name: str, handler: Handler) -> "NativeToolRegistry":
        """A copy where `name` runs `handler` instead: for tests."""
        copy = NativeToolRegistry()
        copy._tools = {**self._tools, name: replace(self._tools[name], handler=handler)}
        return copy
