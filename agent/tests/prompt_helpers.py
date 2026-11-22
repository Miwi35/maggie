"""Test doubles for the system prompt builder (MAG-952)."""

from unittest.mock import AsyncMock, MagicMock

from app.prompt_sources import MaggiePromptSources
from app.runtime.system_prompt import SystemPrompt


def system_prompt_with(*, personality=None, agent_memory=None) -> SystemPrompt:
    """The real builder over the real sources, with a personality and a memory of the test's choosing."""
    return SystemPrompt(MaggiePromptSources(personality=personality, agent_memory=agent_memory))


def stub_system_prompt(blocks) -> MagicMock:
    """A builder that returns `blocks` and loads nothing."""
    return MagicMock(
        build=AsyncMock(return_value=blocks), thread_free=AsyncMock(return_value=None), warm_up=MagicMock()
    )
