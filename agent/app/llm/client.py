"""Where the agent gets its LLM client — the real one, or the scripted fake (MAG-95).

Every call to a model goes through `create_llm_client()`, so `LLM_PROVIDER` is
the single switch: nothing else in the agent knows there is a fake, and no
caller takes an e2e-only branch.
"""

import logging

import anthropic

from app.config import settings
from app.llm.fake import FakeAnthropicClient

logger = logging.getLogger(__name__)

FAKE = "fake"


def llm_configured() -> bool:
    """Whether a model can be reached at all. The fake needs no key."""
    return settings.llm_provider == FAKE or bool(settings.anthropic_api_key)


def create_llm_client():
    """The client the agent talks to: the scripted fake under e2e, Anthropic otherwise.

    Only two providers exist, and `Settings` has already refused anything else —
    so "not fake" really does mean Anthropic here.
    """
    if settings.llm_provider == FAKE:
        logger.info("LLM_PROVIDER=fake — answers come from the scenario fixtures, not from Claude")
        return FakeAnthropicClient()
    return anthropic.AsyncAnthropic(api_key=settings.anthropic_api_key)
