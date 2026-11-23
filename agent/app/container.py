"""The one place where the agent's shared instances are built (MAG-884).

The Anthropic client, the `ToolRouter` and the two gateways live here, built on first
read and kept. What is added later (routers, approvals, tool registry) is built here too.
In a test: `with container.override(...)`, never a `patch` of a module attribute.
"""

from collections.abc import Iterator
from contextlib import contextmanager
from functools import cached_property

from app.llm.client import create_llm_client, llm_configured

_PARTS = ("llm_client", "tool_router", "system_prompt", "llm_gateway", "streaming_gateway", "approvals")


class Container:
    # The classes are imported in the methods: low modules (`context_summary`...) read
    # `container.llm_client` without an import cycle. M8 will sort the layers.
    @cached_property
    def llm_client(self):
        return create_llm_client() if llm_configured() else None

    @cached_property
    def tool_router(self):
        from app.llm.tools import ToolRouter
        from app.native_tools import build_native_registry

        return ToolRouter(natives=build_native_registry())

    @cached_property
    def system_prompt(self):
        from app.prompt_sources import MaggiePromptSources
        from app.runtime.system_prompt import SystemPrompt

        return SystemPrompt(MaggiePromptSources())

    @cached_property
    def llm_gateway(self):
        from app.llm.gateway import LLMGateway

        return LLMGateway(client=self.llm_client, tool_router=self.tool_router, system_prompt=self.system_prompt)

    @cached_property
    def streaming_gateway(self):
        from app.llm.streaming import StreamingGateway

        return StreamingGateway(client=self.llm_client, tool_router=self.tool_router, system_prompt=self.system_prompt)

    @cached_property
    def approvals(self):
        from app.domain.approvals.service import ApprovalService

        return ApprovalService(gateway=self.llm_gateway, system_prompt=self.system_prompt)

    @contextmanager
    def override(self, **parts) -> Iterator["Container"]:
        unknown = set(parts) - set(_PARTS)
        if unknown:
            raise TypeError(f"Unknown container part(s): {sorted(unknown)}")
        saved = dict(self.__dict__)
        stale = []
        if "llm_client" in parts or "tool_router" in parts or "system_prompt" in parts:
            stale += ["llm_gateway", "streaming_gateway"]
        if stale or "llm_gateway" in parts:
            stale.append("approvals")
        for name in stale:
            if name not in parts:
                self.__dict__.pop(name, None)
        self.__dict__.update(parts)
        try:
            yield self
        finally:
            self.__dict__.clear()
            self.__dict__.update(saved)


container = Container()
