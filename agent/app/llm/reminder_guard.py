"""A reminder announced is a reminder scheduled (MAG-339).

In production Maggie answered « C'est noté, je vous rappellerai à 16h05 » after computing
the time with `date_time` and calling nothing else: no proaction existed, and the next
time she copied her own « C'est noté » from the history. The prompt says not to; this is
the check that does not depend on the model reading it.

Both tool loops — `run_tool_loop` and the streamed `chat_stream` — hold one guard per
turn. They report every tool result to it, and ask it about the answer the model ends on:

- no reminder announced, or `schedule_proaction` went through this turn → `ACCEPT`;
- a reminder announced without it → `RETRY` once: the loop sends the model `nudge()`,
  so it calls the tool or takes the promise back;
- announced again → `GIVE_UP`: the loop replaces the answer with `NOT_SCHEDULED_MESSAGE`.

A turn that was not offered `schedule_proaction` has nothing to check — the announcement
of an approved action, or a sub-agent — and the guard stays out of it.
"""

import json
import re
from enum import Enum

SCHEDULE_TOOL = "schedule_proaction"

# Case-insensitive, and only the affirmative forms: « je ne vous rappellerai pas » and
# « le rappel n'a pas été programmé » are honest, and must not trigger a relaunch.
_CLAIM = re.compile(
    r"\bje\s+(?:vous\s+|te\s+|t['\u2019]\s*)(?:l[ea]\s+|les\s+|l['\u2019]\s*)?"
    r"(?:rapp?ell?erai|rappelle\s+dans|préviendrai|enverrai\s+un\s+rappel)"
    r"|\brappel\s+(?:est\s+|bien\s+)*(?:programmé|planifié|enregistré)"
    r"|\b(?:j['\u2019]ai|c['\u2019]est)\s+(?:bien\s+)?(?:programmé|planifié)\s+(?:un|le|votre|ton|ce)\s+rappel",
    re.IGNORECASE,
)

NUDGE = (
    "[Contrôle automatique] Ta réponse annonce un rappel, mais aucun appel à schedule_proaction "
    "n'a réussi pendant ce tour : rien n'est programmé. Si l'utilisateur veut un rappel, appelle "
    "schedule_proaction maintenant (scheduled_at = le champ iso renvoyé par date_time), puis "
    "confirme avec l'heure locale que l'outil renvoie. S'il était déjà programmé avant ce message, "
    "dis qu'il est déjà programmé. Sinon, dis ce qui manque pour le programmer, sans promettre "
    "de rappel."
)

# Fixed, and written without « tu » or « vous »: the user's register is a preference this
# module cannot read, and a neutral sentence is right under both.
NOT_SCHEDULED_MESSAGE = "Le rappel n'a pas été programmé : rien n'est prévu pour l'instant. Il faudra me le redemander."


class Verdict(Enum):
    ACCEPT = "accept"
    RETRY = "retry"
    GIVE_UP = "give_up"


def claims_reminder(text: str) -> bool:
    """Whether the answer tells the user a reminder is coming."""
    return bool(text) and _CLAIM.search(text) is not None


def _went_through(result: str) -> bool:
    """A result without an error. `pending_approval` counts: the call is in front of the user.

    Relaunching after a held call would only file a second request for the same reminder.
    """
    try:
        data = json.loads(result)
    except (json.JSONDecodeError, TypeError):
        return False
    return isinstance(data, dict) and "error" not in data


class ReminderGuard:
    """One per turn: what was scheduled, and whether the model was already sent back once."""

    def __init__(self, tools: list[dict] | None):
        self.active = any(isinstance(tool, dict) and tool.get("name") == SCHEDULE_TOOL for tool in tools or [])
        self.scheduled = False
        self.nudged = False

    def record(self, tool_name: str, result: str) -> None:
        if tool_name == SCHEDULE_TOOL and _went_through(result):
            self.scheduled = True

    def review(self, answer: str, *, can_retry: bool = True) -> Verdict:
        """What to do with the answer the model ends its turn on.

        `can_retry` is false on the loop's last iteration: a relaunch would have no room
        to run, so an unbacked announcement is replaced straight away.
        """
        if not self.active or self.scheduled:
            return Verdict.ACCEPT
        if self.nudged and not answer.strip():
            # Sent back, and answered with nothing: the streamed path would fall back on
            # the claim it just corrected, so silence after a relaunch is a failure too.
            return Verdict.GIVE_UP
        if not claims_reminder(answer):
            return Verdict.ACCEPT
        if not self.nudged and can_retry:
            self.nudged = True
            return Verdict.RETRY
        return Verdict.GIVE_UP

    @staticmethod
    def nudge() -> dict:
        """The message that sends the model back.

        A list of blocks rather than a string: it is the loop speaking, not the user, and
        the fake LLM tells the two apart the same way — a string is what the user said,
        a list is a round of the loop.
        """
        return {"role": "user", "content": [{"type": "text", "text": NUDGE}]}
