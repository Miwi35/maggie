"""Reads the owner's dictated answer to a held action as yes, no or other (part D of the agent reliability spec).

The classifier sees only the owner's sentence, already masked by `hold_secrets`: never Maggie's message, the
action or the screen, so nothing can talk it into a yes. « oui mais à 15 h » is `other`: nothing is decided.
Any failure reads as `other` (fail closed).
"""

import asyncio
import json
import logging
import time
from typing import Literal

from app.llm.client import client_for
from app.llm.models import APPROVAL_REPLY, effort_kwargs, model_for
from app.metrics import record_llm_usage

logger = logging.getLogger(__name__)

ReplyDecision = Literal["approve", "deny", "other"]

_TIMEOUT_S = 8.0

SYSTEM_PROMPT = (
    "Tu classes la réponse d'une personne à une demande de validation. "
    "Réponds UNIQUEMENT avec un JSON valide, sans explication : "
    '{"answer": "approve"} pour un oui sans condition ; '
    '{"answer": "deny"} pour un non ; '
    '{"answer": "other"} pour tout le reste — un oui avec une condition, une réserve ou un changement '
    "(« oui mais à 15 h », « oui, sauf… »), une question, une autre demande. Dans le doute : other."
)


async def classify_approval_reply(client, said: str, user_id: str) -> ReplyDecision:
    client = await client_for(user_id, client)
    if client is None:
        return "other"
    try:
        t0 = time.monotonic()
        response = await asyncio.wait_for(
            client.messages.create(
                model=model_for(APPROVAL_REPLY),
                max_tokens=200,
                **effort_kwargs(APPROVAL_REPLY),
                system=SYSTEM_PROMPT,
                messages=[{"role": "user", "content": said}],
            ),
            _TIMEOUT_S,
        )
        record_llm_usage(
            model=model_for(APPROVAL_REPLY),
            call_type="approval_reply",
            input_tokens=response.usage.input_tokens,
            output_tokens=response.usage.output_tokens,
            duration_seconds=time.monotonic() - t0,
        )
        text = response.content[0].text.strip()
        if text.startswith("```"):
            text = text.split("\n", 1)[-1].rsplit("```", 1)[0].strip()
        answer = json.loads(text)["answer"]
    except Exception as e:
        logger.warning("Approval reply not classified, read as other: %s", e)
        return "other"
    return answer if answer in ("approve", "deny") else "other"
