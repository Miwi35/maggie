"""An action announced is an action done (MAG-339, then MAG-340).

In production Maggie answered « C'est noté, je vous rappellerai à 16h05 » after computing
the time with `date_time` and calling nothing else: no proaction existed (MAG-339). Then she
answered « C'est noté », « j'ai enregistré cette préférence » to « quand je demande un
rappel, je veux une notification » and called no tool at all: nothing was stored (MAG-340).
The prompt says not to; this is the check that does not depend on the model reading it.

Both tool loops — `run_tool_loop` and the streamed `chat_stream` — hold one guard per
turn. They report every tool result to it, and ask it about the answer the model ends on.
Two kinds of announcement are checked, in this order:

- a reminder (« je vous rappellerai à 16h05 »), backed by `schedule_proaction`, an event
  created or updated with reminders, or a pending reminder listed by `list_proactions`;
- something learned (« c'est noté », « je retiens », « j'ai enregistré »), backed by
  `add_instruction`, `create_skill`, `update_skill`, `store_memory`, `schedule_proaction`,
  or any other tool that writes and went through — « C'est noté : dentiste le 12 mars »
  after `create_event` is a true sentence, and relaunching it would only book it twice.

Then:

- nothing announced, or every announcement backed this turn → `ACCEPT`;
- an announcement nothing backs → `RETRY` once: the loop sends the model `nudge()`, so it
  calls the tool or takes the promise back. Only the first unbacked kind is named: a
  « c'est noté » next to an unscheduled reminder is about that reminder;
- announced again → `GIVE_UP`: the loop replaces the answer with `honest_answer()`.

A kind is only checked in a turn that was offered one of its tools — the announcement of
an approved action, or a sub-agent, has nothing to back it with.
"""

import json
import re
from dataclasses import dataclass
from enum import Enum

from app.llm.dry_run import is_read_only

SCHEDULE_TOOL = "schedule_proaction"
LEARNING_TOOLS = ("add_instruction", "create_skill", "update_skill", "store_memory")

# Case-insensitive, and only the affirmative forms: « je ne vous rappellerai pas » and
# « le rappel n'a pas été programmé » are honest, and must not trigger a relaunch. « Je vous
# préviendrai » needs a time after it — « si la météo change » is not a reminder — and « je
# vous rappelle », « je vous préviens » one right after, so that « je vous rappelle que … »
# and « je te préviens, … » stay out. Maggie also promises a reminder as a notification
# (« je t'enverrai une notification à 20h44 », MAG-339's second refusal), and in the present
# (« je te notifie à 23h47 », its third): same rule. The verbs keep changing, which is why
# the check covers the family — notify, alert, warn, send — rather than one phrasing.
_TIME_ANCHOR = (
    r"[^.!?\n]{0,40}?(?:\bà\b|\bdans\b|\bvers\b|\bavant\b|\bdemain\b|\bmatin\b|\bmidi\b|\bsoir\b"
    r"|\b(?:lun|mar|mercre|jeu|vendre|same)di\b|\bdimanche\b|\d)"
)
_REMINDER = re.compile(
    r"\bje\s+(?:vous\s+|te\s+|t['\u2019]\s*)(?:l[ea]\s+|les\s+|l['\u2019]\s*)?"
    r"(?:rapp?ell?erai|env(?:errai|oie)\s+(?:un\s+rappel|une\s+(?:notification|alerte))"
    r"|rappelle\s+(?:dans|à|vers|demain|ce\s+soir)\b"
    r"|préviens\s+(?:dans|à|vers|demain|ce\s+soir)\b"
    r"|(?:notifie|alerte|avertis)\s+(?:dans|à|vers|demain|ce\s+soir|\d)"
    r"|(?:préviendrai|notifierai|alerterai|avertirai|ferai\s+signe"
    r"|env(?:errai|oie)\s+un\s+message)" + _TIME_ANCHOR + r")"
    r"|\b(?:tu\s+recevras|vous\s+recevrez)\s+"
    r"(?:un\s+rappel|une\s+(?:notification|alerte)|un\s+message" + _TIME_ANCHOR + r")"
    r"|\b(?:tu\s+seras|vous\s+serez)\s+(?:notifié|prévenu|alerté|averti)(?:e)?s?"
    + _TIME_ANCHOR
    + r"|\b(?:rappel|notification)\s+(?:est\s+|bien\s+)*(?:programmée?|planifiée?|enregistrée?)"
    r"|\b(?:j['\u2019]ai|c['\u2019]est)\s+(?:bien\s+)?(?:programmé|planifié)\s+(?:un|le|votre|ton|ce)\s+rappel",
    re.IGNORECASE,
)

# The same rule: only the affirmative forms. « Je n'ai rien enregistré », « je ne l'ai pas
# encore enregistré » and « ce n'est pas noté » put a word between the subject and the
# verb, so they stay out. « Je note » needs its object — « je le note », « je m'en note » —
# so that « je note que tu as trois rendez-vous » stays a remark. An offer is not a claim:
# « veux-tu que je le note ? » asks first, which is what she should do when unsure.
_OBJECT = r"(?:l[ea]\s+|les\s+|l['\u2019]\s*|m['\u2019]\s*en\s+)"
_NOT_AN_OFFER = r"(?<!\bque\s)"
_LEARNING = re.compile(
    r"\bc['\u2019]est\s+(?:bien\s+)?(?:noté|enregistré)\b"
    r"|\bj['\u2019]ai\s+(?:bien\s+)?(?:noté|enregistré)\b"
    r"|" + _NOT_AN_OFFER + r"\bje\s+" + _OBJECT + r"note(?:rai)?\b"
    r"|\bj['\u2019]\s*en\s+prends\s+note\b"
    r"|" + _NOT_AN_OFFER + r"\bje\s+" + _OBJECT + r"?(?:retiens|retiendrai)\b"
    r"|\bje\s+m['\u2019]\s*en\s+souviendrai\b",
    re.IGNORECASE,
)

# Other tools that back an announced reminder:
# - an event created or updated with `reminders` — « préviens-moi une heure avant » is that
#   tool's job, and its confirmation reads like a proaction's;
# - `list_proactions` showing a pending one — « tu me rappelles bien ? » is answered by a
#   reminder scheduled in an earlier turn.
EVENT_TOOLS = ("create_event", "update_event")
LIST_TOOL = "list_proactions"

NUDGE = (
    "[Contrôle automatique] Ta réponse annonce un rappel, mais aucun appel à schedule_proaction "
    "n'a réussi pendant ce tour : rien n'est programmé. Si l'utilisateur veut un rappel, appelle "
    "schedule_proaction maintenant (scheduled_at = le champ iso renvoyé par date_time), puis "
    "confirme avec l'heure locale que l'outil renvoie. S'il était déjà programmé avant ce message, "
    "dis qu'il est déjà programmé. Sinon, dis ce qui manque pour le programmer, sans promettre "
    "de rappel."
)
LEARNING_NUDGE = (
    "[Contrôle automatique] Ta réponse dit que tu as noté ou retenu quelque chose, mais aucun "
    "outil n'a rien enregistré pendant ce tour : tu n'as rien appris. Choisis l'outil maintenant : "
    "une façon de faire générale (« quand je te demande X, fais Y ») → create_skill (update_skill "
    'si la compétence existe) ; le ton ou la façon de lui parler → add_instruction kind="behavior" ; '
    'ce qui revient à date fixe → add_instruction kind="planning" ; une action ponctuelle plus tard '
    "→ schedule_proaction ; un fait sur lui → store_memory. Puis confirme ce que l'outil a enregistré. "
    "S'il n'y a rien à retenir, réponds sans dire que c'est noté."
)

# Fixed, and written without « tu » or « vous »: the user's register is a preference this
# module cannot read, and a neutral sentence is right under both.
NOT_SCHEDULED_MESSAGE = "Le rappel n'a pas été programmé : rien n'est prévu pour l'instant. Il faudra me le redemander."
NOT_LEARNED_MESSAGE = "Je ne l'ai pas encore enregistré : rien n'est retenu pour l'instant. Il faudra me le redemander."


@dataclass(frozen=True)
class Claim:
    """One kind of announcement: how to spot it, which tools make it checkable, what to say."""

    name: str
    pattern: re.Pattern
    offered_by: tuple[str, ...]
    nudge: str
    honest_answer: str

    def made_in(self, text: str) -> bool:
        return bool(text) and self.pattern.search(text) is not None


REMINDER = Claim("reminder", _REMINDER, (SCHEDULE_TOOL,), NUDGE, NOT_SCHEDULED_MESSAGE)
LEARNING = Claim("learning", _LEARNING, LEARNING_TOOLS, LEARNING_NUDGE, NOT_LEARNED_MESSAGE)
# The order is the priority: a reminder is the more specific claim.
CLAIMS = (REMINDER, LEARNING)


class Verdict(Enum):
    ACCEPT = "accept"
    RETRY = "retry"
    GIVE_UP = "give_up"


def claims_reminder(text: str) -> bool:
    """Whether the answer tells the user a reminder is coming."""
    return REMINDER.made_in(text)


def claims_learning(text: str) -> bool:
    """Whether the answer tells the user something was noted, stored or learned."""
    return LEARNING.made_in(text)


def _went_through(result: str) -> bool:
    """A result without an error. `pending_approval` counts: the call is in front of the user.

    Relaunching after a held call would only file a second request for the same thing.
    """
    try:
        data = json.loads(result)
    except (json.JSONDecodeError, TypeError):
        return False
    # A stored proaction always carries `error`, null when nothing went wrong.
    return isinstance(data, dict) and not data.get("error")


def _lists_a_pending_one(result: str) -> bool:
    try:
        data = json.loads(result)
    except (json.JSONDecodeError, TypeError):
        return False
    return isinstance(data, list) and any(isinstance(p, dict) and p.get("status") == "pending" for p in data)


def _backs_a_reminder(tool_name: str, result: str, arguments: dict) -> bool:
    if tool_name == LIST_TOOL:
        return _lists_a_pending_one(result)
    if tool_name in EVENT_TOOLS:
        return bool(arguments.get("reminders")) and _went_through(result)
    return tool_name == SCHEDULE_TOOL and _went_through(result)


def _backs_learning(tool_name: str, result: str, arguments: dict) -> bool:
    """A write that went through: the learning tools, `schedule_proaction`, or any other.

    `is_read_only` is the dry run's list (MAG-249): a tool missing from it is taken as a
    write, so an unknown tool leans towards accepting the answer rather than relaunching it.
    """
    return not is_read_only(tool_name, arguments) and _went_through(result)


class ClaimGuard:
    """One per turn: what the tools backed, and whether the model was already sent back once."""

    def __init__(self, tools: list[dict] | None):
        offered = {tool.get("name") for tool in tools or [] if isinstance(tool, dict)}
        self.checked = tuple(claim for claim in CLAIMS if offered.intersection(claim.offered_by))
        self.backed: set[str] = set()
        self.nudged = False
        self._pending: Claim | None = None

    @property
    def active(self) -> bool:
        return bool(self.checked)

    def record(self, tool_name: str, result: str, arguments: dict | None = None) -> None:
        arguments = arguments or {}
        if _backs_a_reminder(tool_name, result, arguments):
            self.backed.add(REMINDER.name)
        if _backs_learning(tool_name, result, arguments):
            self.backed.add(LEARNING.name)

    def _unbacked(self, answer: str) -> Claim | None:
        # A « c'est noté » next to a reminder that is backed — scheduled now, or found pending
        # by `list_proactions` — is about that reminder: the learning check stays out of it,
        # or its nudge would offer `schedule_proaction` and book the reminder twice.
        reminder_backed = REMINDER.name in self.backed and REMINDER.made_in(answer)
        return next(
            (
                claim
                for claim in self.checked
                if claim.name not in self.backed
                and not (claim is LEARNING and reminder_backed)
                and claim.made_in(answer)
            ),
            None,
        )

    def review(self, answer: str, *, can_retry: bool = True) -> Verdict:
        """What to do with the answer the model ends its turn on.

        `can_retry` is false when the loop has fewer than two iterations left: a relaunch
        needs one to call the tool and one to confirm, so without them an unbacked
        announcement is replaced straight away.
        """
        if not self.active:
            return Verdict.ACCEPT
        if self.nudged and not answer.strip() and self._pending and self._pending.name not in self.backed:
            # Sent back, and answered with nothing: the streamed path would fall back on
            # the claim it just corrected, so silence after a relaunch is a failure too.
            return Verdict.GIVE_UP
        claim = self._unbacked(answer)
        if claim is None:
            return Verdict.ACCEPT
        # The honest answer follows the claim made last: a relaunched answer that drops the
        # reminder and only says « c'est noté » is answered about what it says now.
        self._pending = claim
        if not self.nudged and can_retry:
            self.nudged = True
            return Verdict.RETRY
        return Verdict.GIVE_UP

    def honest_answer(self) -> str:
        """What replaces an answer given up on: the truth about the claim it made."""
        return (self._pending or REMINDER).honest_answer

    def nudge(self) -> dict:
        """The message that sends the model back, about the claim `review` found unbacked.

        A list of blocks rather than a string: it is the loop speaking, not the user, and
        the fake LLM tells the two apart the same way — a string is what the user said,
        a list is a round of the loop.
        """
        text = (self._pending or REMINDER).nudge
        return {"role": "user", "content": [{"type": "text", "text": text}]}

    def send_back(self, messages: list, content: list) -> None:
        """Append the answer being refused, then `nudge()`.

        The answer is left out when it holds no text — the claim then came from an earlier
        step, which is already in `messages` — because the API refuses an assistant message
        made of blank text.
        """
        if any(_text_of(block).strip() for block in content or []):
            messages.append({"role": "assistant", "content": content})
        messages.append(self.nudge())


def _text_of(block) -> str:
    text = block.get("text") if isinstance(block, dict) else getattr(block, "text", None)
    return text if isinstance(text, str) else ""
