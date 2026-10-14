"""Encoding along the way (MAG-15, ADR-011): the carte and the obvious fiches, written once a subject goes quiet.

Three rhythms write the memory. Right away, during the turn (MAG-17); at night, for what needs hindsight
(MAG-159); and this one: about five minutes after the last message of a thread, a background pass on the fast
model reads what was said since its last pass and writes

  - the **carte** of the thread's conversation file (`resume`, `moments`, `personnes`, `evoque`), on top of the
    file — the messages below it are never touched;
  - the **obvious fiches**: what the owner said and that will stay true, with no arbitration to make.

It decides nothing that needs a second look. A contradiction with a fiche, two fiches that could take the same
claim, a claim that is not clear: the pass writes the carte and stops there, and says in `journal.md` what it
left for the night. It runs from the scheduler, never during a turn, and its cost is counted under its own
`call_type`. It is idempotent: the thread remembers where its last pass stopped (`encoded_up_to`), the carte is
merged rather than replaced, and a claim already in a fiche is not added twice.

Every write goes through `MemoryService` like any other (file, index, then a line in `journal.md`), and the
reason of each line starts with `PASS_LABEL`, so what this pass did reads apart from what the night did.
"""

import asyncio
import json
import logging
import re
import time
from dataclasses import dataclass, field
from datetime import UTC, date, datetime
from typing import Any
from zoneinfo import ZoneInfo

from app.config import settings
from app.db.context_model import ConversationContext
from app.db.context_repository import context_repo
from app.db.memory_index_repository import IndexedFile
from app.db.message_repository import message_repo
from app.llm.client import create_llm_client, llm_configured
from app.memory import carte as carte_mod
from app.memory import conversations, fiches, paths
from app.memory.carte import Carte, UnreadableFile
from app.memory.service import MemoryService, memory_service
from app.memory.ulid import is_ulid
from app.metrics import record_llm_usage, usage_kwargs
from app.user_timezone import resolve_user_timezone

logger = logging.getLogger(__name__)

PASS_LABEL = "encodage au fil de l'eau"
CALL_TYPE = "memory_encoding"

MAX_TOKENS = 1500
MAX_MESSAGES = 100
MAX_MESSAGE_CHARS = 1500
MAX_PROPOSALS = 5
MAX_CLAIMS = 8
MAX_NEW_MOMENTS = 8
MAX_TERMS = 12
MAX_RESUME = 600
FICHES_LISTED = 60
FICHES_OPENED = 4
FICHE_EXCERPT = 1500
# A pass that fails this many times in a row on the same last message waits for the next message
# rather than call the model again every minute.
MAX_ATTEMPTS = 3

SYSTEM_PROMPT = (
    "Tu écris la mémoire de Maggie, l'assistante personnelle d'un propriétaire, à partir d'un passage de "
    "conversation qui vient de s'arrêter.\n"
    "Réponds UNIQUEMENT par un objet JSON, sans texte autour, de cette forme :\n"
    '{"carte": {"resume": "…", "moments": [{"heure": "AAAA-MM-JJ HHhMM", "texte": "…"}], '
    '"personnes": ["…"], "evoque": ["…"]}, '
    '"fiches": [{"chemin": null, "titre": "…", "dossiers": ["…"], "resume": "…", "personnes": ["…"], '
    '"evoque": ["…"], "alias": ["…"], "ajouts": [{"texte": "…", "heure": "AAAA-MM-JJ HHhMM"}], "doute": null}], '
    '"doute": null}\n'
    "La carte dit de quoi parle ce fil, en français. `resume` réécrit le résumé actuel en y intégrant le passage "
    "(3 lignes au maximum). `moments` : les moments clés du passage, chacun avec l'heure EXACTE d'un message "
    "(telle qu'elle est écrite devant le message). `evoque` : les situations et les thèmes pour lesquels ce "
    "souvenir devrait revenir, même sans mot commun (« fleurs, cadeau, deuil »).\n"
    "Les fiches ne contiennent que l'ÉVIDENT : ce que le propriétaire a DIT dans le passage et qui restera vrai "
    "(un goût, une habitude, une personne, un fait de sa vie). Chaque ajout cite l'heure d'un message du "
    "Propriétaire. Le titre est une phrase vraie (« Françoise n'aime pas les roses »), pas une étiquette. Pour "
    "compléter une fiche listée, donne son `chemin` tel qu'il est écrit ; sinon `chemin` reste null. Rien pour un "
    "échange purement pratique (courses, agenda, rappel) : la carte suffit.\n"
    "Tu ne tranches RIEN. Si ce que dit le propriétaire contredit une fiche, si tu hésites entre deux fiches, si "
    "une affirmation n'est pas claire, renseigne `doute` (une phrase) sur la fiche : elle ne sera pas écrite, la "
    "nuit s'en chargera. Si tout le passage est ambigu, renseigne `doute` au premier niveau : seule la carte sera "
    "écrite. Ce que Maggie a proposé ou déduit n'est pas une affirmation du propriétaire. N'invente rien."
)


class ProposalError(ValueError):
    """The model did not answer with a proposal that can be read."""


@dataclass
class Report:
    carte: bool = False
    written: list[str] = field(default_factory=list)
    left: list[str] = field(default_factory=list)


def _one_line(value: Any) -> str:
    return " ".join(str(value or "").split())


def _aware(when: datetime) -> datetime:
    return when if when.tzinfo is not None else when.replace(tzinfo=UTC)


def _strings(value: Any) -> list[str]:
    if not isinstance(value, list):
        return []
    return [text for text in (_one_line(item) for item in value) if text]


def _union(current: list[str], new: list[str], limit: int) -> list[str]:
    seen = {fiches.norm(item) for item in current}
    out = list(current)
    for item in new:
        if len(out) - len(current) >= limit:
            break
        if fiches.norm(item) not in seen:
            seen.add(fiches.norm(item))
            out.append(item)
    return out


def parse_proposal(raw: str) -> dict[str, Any]:
    """The JSON object in the model's answer, however it was fenced. Raises `ProposalError`."""
    text = re.sub(r"^```(?:json)?\s*|\s*```$", "", raw.strip())
    start, end = text.find("{"), text.rfind("}")
    if start < 0 or end <= start:
        raise ProposalError("no JSON object in the answer")
    try:
        data = json.loads(text[start : end + 1])
    except json.JSONDecodeError as e:
        raise ProposalError(f"the answer is not valid JSON: {e}") from e
    if not isinstance(data, dict):
        raise ProposalError("the answer is not an object")
    return data


def _stamp(when: datetime, tz: ZoneInfo) -> str:
    return _aware(when).astimezone(tz).strftime("%Y-%m-%d %Hh%M")


def transcript(messages: list[Any], tz: ZoneInfo) -> str:
    """The passage as the model reads it: each message under the minute the conversation file gives it."""
    parts = []
    for message in messages:
        content = (message.content or "").strip()
        if len(content) > MAX_MESSAGE_CHARS:
            content = content[:MAX_MESSAGE_CHARS] + " […]"
        role = conversations.ROLE_NAMES.get(message.role, message.role)
        parts.append(f"## {_stamp(message.created_at, tz)} — {role}\n{content}")
    return "\n\n".join(parts)


def _words(text: str) -> set[str]:
    return {w for w in re.findall(r"\w+", fiches.norm(text)) if len(w) >= 4}


def nearby(listed: list[IndexedFile], passage: str) -> list[IndexedFile]:
    """The fiches that share most words with the passage: the ones a new claim could belong to or contradict."""
    wanted = _words(passage)
    scored = [(len(wanted & _words(f"{f.path} {f.resume} {f.body}")), f) for f in listed]
    scored = [item for item in scored if item[0] > 0]
    scored.sort(key=lambda item: (-item[0], item[1].path))
    return [f for _, f in scored[:FICHES_OPENED]]


def _claims_of(fiche: IndexedFile) -> str:
    known = fiches.parse_body(fiche.body).known
    text = "\n".join(fiches.render_claim(c) for c in known) or fiche.body
    return text[:FICHE_EXCERPT]


def build_request(label: str, carte: Carte, listed: list[IndexedFile], passage: str) -> str:
    parts = [f"Sujet du fil : {label}"]
    current = [f"résumé : {carte.resume}"] if carte.resume else []
    current += [f"moment : {m}" for m in carte.moments[-MAX_NEW_MOMENTS:]]
    if carte.personnes:
        current.append("personnes : " + ", ".join(carte.personnes))
    if carte.evoque:
        current.append("évoque : " + ", ".join(carte.evoque))
    parts.append("Carte actuelle :\n" + ("\n".join(current) if current else "(aucune)"))
    if listed:
        shown = listed[:FICHES_LISTED]
        lines = [f"- {f.path} — {f.resume}" if f.resume else f"- {f.path}" for f in shown]
        parts.append("Fiches existantes :\n" + "\n".join(lines))
        close = nearby(shown, passage)
        if close:
            opened = [f"### {fiche.path}\n{_claims_of(fiche)}" for fiche in close]
            parts.append("Contenu des fiches proches :\n" + "\n\n".join(opened))
    else:
        parts.append("Fiches existantes : aucune.")
    parts.append("Passage à encoder :\n" + passage)
    parts.append("Réponds par le JSON.")
    return "\n\n".join(parts)


def merge_carte(carte: Carte, proposed: Any, file_text: str) -> Carte:
    """The carte with the proposal folded in: the summary rewritten, the rest added to, nothing dropped."""
    data = proposed if isinstance(proposed, dict) else {}
    resume = _one_line(data.get("resume"))[:MAX_RESUME] or carte.resume

    known = {_moment_key(m) for m in carte.moments}
    moments = list(carte.moments)
    added = 0
    proposed_moments = data.get("moments")
    for item in proposed_moments if isinstance(proposed_moments, list) else []:
        if added >= MAX_NEW_MOMENTS or not isinstance(item, dict):
            continue
        heure, texte = _one_line(item.get("heure")), _one_line(item.get("texte"))
        # A moment is timestamped to a minute of the file, or it is not kept.
        if not texte or f"## {heure} — " not in file_text or fiches.norm(texte) in known:
            continue
        known.add(fiches.norm(texte))
        moments.append(f"{heure} — {texte}")
        added += 1

    return Carte(
        id=carte.id,
        resume=resume,
        moments=moments,
        personnes=_union(carte.personnes, _strings(data.get("personnes")), MAX_TERMS),
        evoque=_union(carte.evoque, _strings(data.get("evoque")), MAX_TERMS),
        alias=carte.alias,
        liens=carte.liens,
        sources=carte.sources,
        extra=carte.extra,
    )


def _moment_key(moment: str) -> str:
    return fiches.norm(moment.split(" — ", 1)[1] if " — " in moment else moment)


class PassageEncoder:
    """Writes the carte and the obvious fiches of a thread that went quiet, with the fast model."""

    def __init__(self, service: MemoryService | None = None):
        self.client = create_llm_client() if llm_configured() else None
        self._service = service
        self._locks: dict[str, asyncio.Lock] = {}
        self._failures: dict[str, tuple[datetime, int]] = {}

    @property
    def service(self) -> MemoryService:
        return self._service or memory_service

    async def encode(self, ctx: ConversationContext, silent_before: datetime) -> Report | None:
        """Encode what a thread gained since its last pass. None when there was nothing to do or the pass failed.

        Never raises: it runs in the background, after the answer, with nobody to tell. A failure leaves
        the watermark where it was, so the next cycle tries again (a few times, then it waits for a message).
        """
        service, context_id, user_id = self.service, str(ctx.id), str(ctx.user_id)
        if self.client is None or not service.enabled or not is_ulid(user_id):
            return None
        async with self._locks.setdefault(context_id, asyncio.Lock()):
            try:
                return await self._encode(service, context_id, user_id, silent_before)
            except Exception:
                logger.exception("Could not encode the thread %s of %s", context_id, user_id)
                return None

    async def _encode(self, service: MemoryService, context_id: str, user_id: str, silent_before: datetime):
        # Read again under the lock: the thread may have been spoken in, or encoded, since it was listed.
        ctx = await context_repo.get(context_id)
        if ctx is None:
            return None
        expected = ctx.encoded_up_to
        messages = await message_repo.find_by_context(context_id, since=expected, limit=MAX_MESSAGES, user_id=user_id)
        if not messages or _aware(messages[-1].created_at) >= silent_before:
            return None
        last = messages[-1].created_at
        if self._gave_up_on(context_id, last):
            return None

        await conversations.record_thread(user_id, context_id, service=service)
        rel = conversations.find_file(service, user_id, context_id)
        if rel is None:
            return None
        tz = await resolve_user_timezone(user_id)
        file_text = service.folder(user_id).read(rel) or ""
        passage = transcript(messages, tz)
        owner_minutes = {_stamp(m.created_at, tz) for m in messages if m.role == "user"}
        try:
            carte_now = carte_mod.parse(file_text).carte
            listed = await service.fiches(user_id)
            answer = await self._ask(build_request(str(ctx.label), carte_now, listed, passage))
            proposal = parse_proposal(answer)
        except (ProposalError, UnreadableFile) as e:
            self._failed(context_id, last)
            logger.warning("Encoding of thread %s left alone: %s", context_id, e)
            return None
        except Exception:
            self._failed(context_id, last)
            raise

        report = Report()
        label = str(ctx.label)
        reason = f"{PASS_LABEL}, fil « {label} »"
        await self._write_carte(service, user_id, context_id, proposal.get("carte"), file_text, reason, report)

        doubt = _one_line(proposal.get("doute"))
        proposals = proposal.get("fiches") if isinstance(proposal.get("fiches"), list) else []
        if doubt:
            report.left.append("fiches")
            await service.journal(
                user_id, f"{PASS_LABEL} : carte seule pour « {label} », fiches laissées à la nuit — {doubt}"
            )
        else:
            await self._write_fiches(
                service, user_id, rel, proposals[:MAX_PROPOSALS], listed, owner_minutes, tz, reason, label, report
            )

        if not await context_repo.set_encoded(context_id, last, expected):
            logger.info("Thread %s was encoded by another pass meanwhile", context_id)
        self._failures.pop(context_id, None)
        return report

    async def _write_carte(
        self,
        service: MemoryService,
        user_id: str,
        context_id: str,
        proposed: Any,
        file_text: str,
        reason: str,
        report: Report,
    ) -> None:
        changed: list[bool] = []

        def change(carte: Carte) -> Carte:
            merged = merge_carte(carte, proposed, file_text)
            changed.append(merged != carte)
            return merged

        await conversations.update_carte(
            user_id, context_id, change, reason=f"{reason} : carte du passage", service=service
        )
        report.carte = bool(changed and changed[0])

    async def _write_fiches(
        self,
        service: MemoryService,
        user_id: str,
        conversation: str,
        proposals: list[Any],
        listed: list[IndexedFile],
        owner_minutes: set[str],
        tz: ZoneInfo,
        reason: str,
        label: str,
        report: Report,
    ) -> None:
        known_paths = {f.path for f in listed}
        today = datetime.now(tz).date()
        for proposal in proposals:
            title = _one_line(proposal.get("titre")) if isinstance(proposal, dict) else ""
            try:
                outcome = await self._write_fiche(
                    service, user_id, conversation, proposal, known_paths, owner_minutes, today, reason
                )
            except (fiches.FicheError, paths.UnsafePath, UnreadableFile) as e:
                outcome = f"laissée à la nuit : {e}"
            if outcome is None:
                continue
            if outcome.startswith("écrite "):
                report.written.append(outcome.removeprefix("écrite "))
            else:
                report.left.append(title or "?")
                why = outcome.removeprefix("laissée à la nuit : ")
                await service.journal(
                    user_id, f"{PASS_LABEL} : « {title or '?'} » laissée à la nuit, fil « {label} » — {why}"
                )

    async def _write_fiche(
        self,
        service: MemoryService,
        user_id: str,
        conversation: str,
        proposal: Any,
        known_paths: set[str],
        owner_minutes: set[str],
        today: date,
        reason: str,
    ) -> str | None:
        """One fiche: `écrite <chemin>`, a reason it is left to the night, or None when it adds nothing."""
        if not isinstance(proposal, dict):
            return "laissée à la nuit : proposition illisible"
        if _one_line(proposal.get("doute")):
            return f"laissée à la nuit : {_one_line(proposal.get('doute'))}"
        title = _one_line(proposal.get("titre"))
        added = proposal.get("ajouts")
        claims = [
            {
                "texte": _one_line(a.get("texte")),
                "provenance": "dit",
                "source": conversation,
                "heure": _one_line(a.get("heure")),
            }
            for a in (added if isinstance(added, list) else [])[:MAX_CLAIMS]
            if isinstance(a, dict) and _one_line(a.get("texte")) and _one_line(a.get("heure")) in owner_minutes
        ]
        if not title or not claims:
            return None

        folder = service.folder(user_id)
        chemin = _one_line(proposal.get("chemin")) or None
        if chemin is not None and chemin not in known_paths:
            return f"laissée à la nuit : « {chemin} » n'est pas une fiche connue"
        if chemin is None:
            dossiers = _strings(proposal.get("dossiers"))
            target = paths.fiche_relative(dossiers, title, []) if len(dossiers) <= fiches.MAX_FOLDERS else None
            chemin = target if target is not None and folder.exists(target) else None

        previous: list[dict[str, Any]] = []
        personnes, evoque, alias = (_strings(proposal.get(k)) for k in ("personnes", "evoque", "alias"))
        resume = _one_line(proposal.get("resume")) or None
        if chemin is not None:
            parsed = carte_mod.parse(await service.read(user_id, chemin) or "")
            before = fiches.parse_body(parsed.body)
            replaced = {c.key for c in before.replaced}
            if any(fiches.norm(c["texte"]) in replaced for c in claims):
                return "laissée à la nuit : l'affirmation figure parmi celles qui ne sont plus vraies"
            held = {c.key for c in before.known}
            claims = [c for c in claims if fiches.norm(c["texte"]) not in held]
            if not claims:
                return None
            previous = [
                {"texte": c.texte, "provenance": c.provenance, "source": c.source, "heure": c.heure}
                for c in before.known
            ]
            personnes = _union(parsed.carte.personnes, personnes, MAX_TERMS)
            evoque = _union(parsed.carte.evoque, evoque, MAX_TERMS)
            alias = _union(parsed.carte.alias, alias, MAX_TERMS)
            resume = parsed.carte.resume or resume
            title = chemin.rsplit("/", 1)[-1].removesuffix(paths.EXTENSION)

        written = await fiches.write_fiche(
            service,
            user_id,
            titre=title,
            affirmations=previous + claims,
            resume=resume,
            dossiers=_strings(proposal.get("dossiers")) if chemin is None else None,
            chemin=chemin,
            personnes=personnes or None,
            evoque=evoque or None,
            alias=alias or None,
            today=today,
            reason=f"{reason} : {len(claims)} affirmation(s) du propriétaire",
        )
        return f"écrite {written['chemin']}"

    async def _ask(self, request: str) -> str:
        model = settings.anthropic_fast_model
        t0 = time.monotonic()
        try:
            response = await self.client.messages.create(
                model=model,
                max_tokens=MAX_TOKENS,
                system=SYSTEM_PROMPT,
                messages=[{"role": "user", "content": request}],
            )
        except Exception:
            record_llm_usage(
                model=model,
                call_type=CALL_TYPE,
                input_tokens=0,
                output_tokens=0,
                duration_seconds=time.monotonic() - t0,
                status="error",
            )
            raise
        record_llm_usage(
            model=model, call_type=CALL_TYPE, duration_seconds=time.monotonic() - t0, **usage_kwargs(response.usage)
        )
        return "".join(getattr(block, "text", "") for block in response.content).strip()

    def _failed(self, context_id: str, last: datetime) -> None:
        seen, count = self._failures.get(context_id, (last, 0))
        self._failures[context_id] = (last, (count if seen == last else 0) + 1)

    def _gave_up_on(self, context_id: str, last: datetime) -> bool:
        seen, count = self._failures.get(context_id, (last, 0))
        return seen == last and count >= MAX_ATTEMPTS


memory_encoder = PassageEncoder()
