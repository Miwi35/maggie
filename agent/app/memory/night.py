"""The night (MAG-159, ADR-011): once a day, off the path of any answer, Maggie goes through the day with hindsight.

Three rhythms write the memory: right away during the turn (MAG-17), along the way a few minutes after a thread
goes quiet (MAG-15, `encoder.py`), and this one — what needs a second look. In order, one step after the other:

  1. **cartes**    catch up the missing cartes, and only those (the conversations themselves are never touched);
  2. **fiches**    read each conversation not read yet, oldest first, and create or complete the fiches it
                   justifies; the newest word wins, the old wording stays in the fiche dated and marked replaced,
                   and a doubt becomes a question to ask the owner — never a silent decision;
  3. **régularités** write what several conversations prove together, each with its proofs — no proof, no line;
  3b. **habitudes**  once a week, read the agenda, the errands, the meals and the transactions through MCP and write
                   the habits they show (MAG-18), `observé`, each with the period and the data that prove it;
  4. **rangement** name, file, rename, move, merge the duplicates, split the catch-alls;
  5. **oubli**     set aside what was only circumstance (the file and the conversation stay, the profile and the
                   spontaneous recall let go of it), judged by Maggie from age, use and links;
  6. **orphelines** tie each fiche to the conversations that justify it, and report the fiches with no source
                   or that nothing points to;
  7. **rappels**   replay every missed recall of `Rappels manqués/` against the index (MAG-228): find why a fiche
                   did not come back, fix the cause (never silently: the journal says it), look over the fiches
                   that risk the same miss, and report the cases that are green again and those that went red.

The judgement is the model's; the checks are the code's. A claim still has to cite a conversation that exists, at a
minute the owner spoke in; nothing true vanishes from a fiche; a path never leaves the folder. Every change goes
through `MemoryService` with a reason starting `NIGHT_LABEL`, so `journal.md` says what the night changed and why,
and one line sums the pass up. The pass is bounded (a number of model calls per tick and per pass) and resumable:
`memory_night_run` keeps where it stopped, `memory_night_seen` which conversation or fiche was read at which content.
"""

import asyncio
import copy
import hashlib
import logging
import re
import time
from dataclasses import dataclass
from datetime import UTC, date, datetime, timedelta
from typing import Any
from zoneinfo import ZoneInfo

from app.config import settings
from app.db.context_repository import context_repo
from app.db.memory_index_repository import IndexedFile, memory_index_repo
from app.db.memory_night_repository import DONE, NightRun, memory_night_repo
from app.llm.client import create_llm_client, llm_configured
from app.llm.provider_status import note_provider_failure, note_provider_success
from app.mcp.client import mcp_client
from app.memory import carte as carte_mod
from app.memory import conversations, fiches, habits, misses, paths, recall
from app.memory.carte import ARCHIVED_KEY, QUESTION_KEY, Carte, UnreadableFile
from app.memory.encoder import ProposalError, merge_carte, nearby, parse_proposal
from app.memory.encoder import memory_encoder as default_encoder
from app.memory.service import MemoryService, memory_service
from app.memory.ulid import is_ulid
from app.metrics import record_llm_usage, usage_kwargs
from app.user_timezone import resolve_user_timezone

logger = logging.getLogger(__name__)

NIGHT_LABEL = "entretien nocturne"
CALL_TYPE = "memory_night"
SCOPE_CONSOLIDATED = "consolidated"
SCOPE_TIDIED = "tidied"
SCOPE_FORGOTTEN = "forgotten"
SCOPE_HABITS = "habits"
HABITS_WEEK = "habits:week"
STEPS = ("cartes", "fiches", "regularites", "habitudes", "rangement", "oubli", "orphelines", "rappels")

MAX_TOKENS = 3000
MAX_MESSAGES = 120
MAX_MESSAGE_CHARS = 1500
MAX_PROPOSALS = 8
MAX_CLAIMS = 10
MAX_TERMS = 12
MAX_RESUME = 600
FICHES_LISTED = 80
FICHES_OPENED = 6
FICHE_EXCERPT = 2500
ENCODING_PAGE = 20
ENCODING_MAX_PAGES = 50
MAX_OPERATIONS = 10
MAX_TIDY_BATCHES = 5
MAX_MERGED = 4
FORGET_BATCH = 25
REGULARITY_FRESH = 8
REGULARITY_OTHERS = 40
REGULARITY_OWNER_LINES = 8
MAX_ATTEMPTS = 2
MAX_EVOQUE_ADDED = 6
MAX_TERM_WORDS = 4
MAX_TERM_CHARS = 40
MISS_CANDIDATES = 2
MISS_REPAIRS = 3
HABIT_FICHE_CLAIMS = 40

_MESSAGE = re.compile(
    r"^## (?P<heure>\d{4}-\d{2}-\d{2} \d{1,2}h\d{2}) — (?P<role>Propriétaire|Maggie)[ \t]*$", re.MULTILINE
)
_ANCHOR = re.compile(r"^<!-- msg: .*? -->[ \t]*\n?", re.MULTILINE)
_STAMP_DATE = re.compile(r"^(\d{4})-(\d{2})-(\d{2})")

CONSOLIDATE_SYSTEM = (
    "Tu fais l'entretien de nuit de la mémoire de Maggie, l'assistante personnelle d'un propriétaire. Tu relis UNE "
    "conversation avec du recul et tu dis ce qui doit être rangé dans les fiches.\n"
    "Réponds UNIQUEMENT par un objet JSON, sans texte autour, de cette forme :\n"
    '{"fiches": [{"chemin": null, "titre": "…", "dossiers": ["…"], "resume": "…", "personnes": ["…"], '
    '"evoque": ["…"], "alias": ["…"], "ajouts": [{"texte": "…", "heure": "AAAA-MM-JJ HHhMM"}], '
    '"remplace": [{"texte": "…", "motif": "…"}], "doute": null}], '
    '"carte": {"personnes": ["…"], "evoque": ["…"]}}\n'
    "Une fiche = une vérité durable sur la vie du propriétaire : un goût, une habitude, une personne, un lieu, un "
    "fait. Le titre est une phrase vraie (« La salle s'appelle l'UBU »), pas une étiquette. Un fait par fiche : "
    "« les concerts commencent à 19 heures et la salle s'appelle l'UBU » donne DEUX fiches. Pour compléter une "
    "fiche listée, donne son `chemin` tel qu'il est écrit ; sinon `chemin` reste null.\n"
    "`ajouts` : seulement ce que le PROPRIÉTAIRE a dit (jamais ce que Maggie a proposé ou déduit), chaque ajout "
    "citant l'heure EXACTE d'un de ses messages, telle qu'elle est écrite devant le message. Ce qui est déjà dans "
    "la fiche n'est pas à répéter.\n"
    "Contradiction : si ce que dit le propriétaire remplace une affirmation d'une fiche (« finalement Lyon »), donne "
    "le `chemin` de la fiche, l'ajout nouveau, et dans `remplace` le texte EXACT de l'ancienne affirmation avec son "
    "motif. La plus récente l'emporte ; l'ancienne reste dans la fiche, datée et marquée remplacée.\n"
    "Doute : si tu hésites (deux lectures possibles, affirmation floue, plaisanterie peut-être), ne tranche pas : "
    "renseigne `doute` avec UNE question à poser au propriétaire, et le `chemin` de la fiche concernée si elle existe. "
    "Les ajouts de cette fiche ne seront pas écrits.\n"
    "Le ponctuel (un rendez-vous de mardi, le menu d'un soir, un détail de circonstance) n'est PAS une fiche. Un "
    "échange purement pratique ne donne rien : `fiches` reste vide.\n"
    "`carte` : `evoque` = les situations et thèmes pour lesquels ce souvenir devrait revenir, même sans mot commun ; "
    "`personnes` = qui est concerné. N'invente rien."
)

CARTE_SYSTEM = (
    "Tu fais l'entretien de nuit de la mémoire de Maggie : une conversation n'a pas de carte, tu l'écris.\n"
    "Réponds UNIQUEMENT par un objet JSON : "
    '{"resume": "…", "moments": [{"heure": "AAAA-MM-JJ HHhMM", "texte": "…"}], "personnes": ["…"], "evoque": ["…"]}\n'
    "`resume` : 3 lignes au maximum, en français. `moments` : les moments clés, chacun avec l'heure EXACTE d'un "
    "message (telle qu'elle est écrite devant le message). `evoque` : les situations et thèmes pour lesquels ce "
    "souvenir devrait revenir, même sans mot commun. N'invente rien."
)

REGULARITY_SYSTEM = (
    "Tu fais l'entretien de nuit de la mémoire de Maggie : tu cherches des RÉGULARITÉS, ce que plusieurs "
    "conversations prouvent ensemble (« les concerts commencent en général à 19 heures »).\n"
    "Réponds UNIQUEMENT par un objet JSON : "
    '{"regularites": [{"chemin": null, "titre": "…", "dossiers": ["…"], "resume": "…", "evoque": ["…"], '
    '"preuves": [{"conversation": "Conversations/…md", "heure": "AAAA-MM-JJ HHhMM", "texte": "…"}]}]}\n'
    "Une régularité sans preuve n'est pas écrite. Chaque preuve est un message du PROPRIÉTAIRE : le chemin de la "
    "conversation tel qu'il est écrit, l'heure EXACTE du message, et ce qu'il a dit (`texte`, une phrase courte). Il "
    "faut au moins {proofs} preuves, dans des conversations DIFFÉRENTES. "
    "Un goût, une habitude ou un usage qui revient ; "
    "jamais une simple coïncidence, jamais ce que Maggie a proposé. Le titre est une phrase vraie. Pour compléter "
    "une fiche existante, donne son `chemin`. Si rien n'est prouvé, `regularites` reste vide."
)

TIDY_SYSTEM = (
    "Tu fais l'entretien de nuit de la mémoire de Maggie : tu ranges les fiches. Une fiche est un fichier dont le "
    "nom est une phrase vraie, dans des dossiers peu profonds choisis par sujet.\n"
    "Réponds UNIQUEMENT par un objet JSON : "
    '{"operations": [{"op": "renommer|deplacer|fusionner|scinder", "chemin": "…", "titre": "…", "dossiers": ["…"], '
    '"chemins": ["…"], "resume": "…", "parties": [{"titre": "…", "dossiers": ["…"], "resume": "…", '
    '"affirmations": ["texte exact d\'une affirmation de la fiche"]}], "motif": "…"}]}\n'
    "- renommer : `chemin`, nouveau `titre` (le nom ne dit pas ce que la fiche dit : une phrase vraie). deplacer : "
    "`chemin`, nouveaux `dossiers` (3 niveaux au plus ; `[]` = à la racine).\n"
    "- fusionner : `chemins` (2 à 4 fiches qui disent la même chose ou se complètent), le premier est gardé ; "
    "`titre` et `resume` facultatifs pour la fiche fusionnée. "
    "Rien ne se perd : toutes les affirmations sont reprises.\n"
    "- scinder : une fiche fourre-tout (`chemin`) en `parties`, chacune avec son `titre`, ses `dossiers` et les "
    "`affirmations` (texte exact) qu'elle reprend ; ce qui n'est dans aucune partie reste dans la fiche. Une "
    "affirmation ne va que dans UNE partie.\n"
    "Ne touche à rien qui est bien rangé. "
    "Les fiches marquées * sont celles qui ont changé depuis le dernier rangement. "
    "Si tout est bien rangé, `operations` reste vide."
)

FORGET_SYSTEM = (
    "Tu fais l'entretien de nuit de la mémoire de Maggie : tu décides ce qui n'a plus à être évoqué spontanément. "
    "Une fiche qui n'était que de circonstance (un détail d'un soir, un projet passé, un rendez-vous dépassé) "
    "est mise de côté : le fichier et la conversation d'origine restent, Maggie ne s'en sert plus d'elle-même.\n"
    "Tu juges d'après le contenu et ces signaux : l'âge de la dernière affirmation, le nombre de fois où Maggie a "
    "ouvert la fiche, ce qui la pointe. Un fait durable (un goût, une personne, une habitude, un lieu) reste, même "
    "ancien ou jamais ouvert. En cas de doute, garde.\n"
    "Réponds UNIQUEMENT par un objet JSON : "
    '{"decisions": [{"chemin": "…", "action": "garder|oublier", "motif": "…"}]}'
)

MISS_SYSTEM = (
    "Tu fais l'entretien de nuit de la mémoire de Maggie. Le propriétaire a dit une phrase à un moment où une fiche "
    "aurait dû revenir à l'esprit de Maggie, et elle ne l'a pas fait. Tu dis quelles associations ajouter à la carte "
    "de cette fiche (son `evoque`) pour qu'elle revienne pour cette phrase et pour des phrases voisines.\n"
    "Réponds UNIQUEMENT par un objet JSON : "
    '{"evoque": ["…"], "voisines": [{"chemin": "…", "evoque": ["…"]}]}\n'
    "`evoque` : les situations, thèmes et mots que cette fiche évoque, comme le propriétaire pourrait les dire — de "
    "courtes expressions (1 à 4 mots), en reprenant les mots de sa phrase quand ils portent le sujet, au plus 6. "
    "Jamais un fait nouveau : seulement des associations avec ce que la fiche dit déjà.\n"
    "`voisines` : parmi les fiches proches listées, seulement celles qui risquent le même raté (mêmes personnes, mêmes "
    "thèmes), avec leurs propres associations. Si aucune ne le risque, `voisines` reste vide."
)

MISS_FICHE_SYSTEM = (
    "Tu fais l'entretien de nuit de la mémoire de Maggie. Le propriétaire attendait qu'elle se souvienne de quelque "
    "chose, et aucune fiche ne le disait. Tu relis UNE conversation : si le propriétaire y dit ce que la fiche aurait "
    "dû contenir, tu donnes la fiche.\n"
    "Réponds UNIQUEMENT par un objet JSON : "
    '{"fiches": [{"titre": "…", "dossiers": ["…"], "resume": "…", "personnes": ["…"], "evoque": ["…"], '
    '"alias": ["…"], "ajouts": [{"texte": "…", "heure": "AAAA-MM-JJ HHhMM"}]}]}\n'
    "Le titre est une phrase vraie. `ajouts` : seulement ce que le PROPRIÉTAIRE a dit, chaque ajout citant l'heure "
    "EXACTE d'un de ses messages, telle qu'elle est écrite devant le message. `evoque` : les situations et thèmes "
    "pour lesquels la fiche doit revenir. Si la conversation ne dit pas ce souvenir, `fiches` reste vide."
)


class TickLimit(Exception):
    """The tick has spent its calls: the pass is saved and resumes at the next tick."""


class RunLimit(Exception):
    """The pass has spent its calls: it ends here, the rest waits for the next night."""


class ModelDown(Exception):
    """The model cannot be reached: the pass is saved where it is and resumes later."""


def _line(value: Any) -> str:
    return " ".join(str(value or "").split())


def _strings(value: Any) -> list[str]:
    if not isinstance(value, list):
        return []
    return [text for text in (_line(item) for item in value) if text]


def _items(value: Any) -> list[Any]:
    return value if isinstance(value, list) else []


def _dicts(value: Any) -> list[dict[str, Any]]:
    return [item for item in _items(value) if isinstance(item, dict)]


def _union(current: list[str], new: list[str], limit: int = MAX_TERMS) -> list[str]:
    seen = {fiches.norm(item) for item in current}
    out = list(current)
    for item in new:
        if len(out) - len(current) >= limit:
            break
        if fiches.norm(item) not in seen:
            seen.add(fiches.norm(item))
            out.append(item)
    return out


def _stem(rel: str) -> str:
    return rel.rsplit("/", 1)[-1].removesuffix(paths.EXTENSION)


def _directory(rel: str) -> list[str]:
    return rel.split("/")[:-1]


@dataclass
class Message:
    heure: str
    role: str
    content: str


def messages_of(text: str) -> list[Message]:
    """The messages of a conversation file, as they stand: minute, speaker, text (the anchors left out)."""
    body = carte_mod.split(text)[1]
    found = list(_MESSAGE.finditer(body))
    out: list[Message] = []
    for index, match in enumerate(found):
        end = found[index + 1].start() if index + 1 < len(found) else len(body)
        content = _ANCHOR.sub("", body[match.end() : end].lstrip("\n"), count=1).strip()
        out.append(Message(match.group("heure"), match.group("role"), content))
    return out


def transcript(messages: list[Message]) -> str:
    parts = []
    for message in messages[-MAX_MESSAGES:]:
        content = message.content
        if len(content) > MAX_MESSAGE_CHARS:
            content = content[:MAX_MESSAGE_CHARS] + " […]"
        parts.append(f"## {message.heure} — {message.role}\n{content}")
    return "\n\n".join(parts)


def _claims_text(fiche: IndexedFile) -> str:
    body = fiches.parse_body(fiche.body)
    lines = [fiches.render_claim(c) for c in body.known + body.replaced]
    return ("\n".join(lines) or fiche.body)[:FICHE_EXCERPT]


def _fiche_line(fiche: IndexedFile) -> str:
    mark = " (mise de côté)" if fiche.archived else ""
    return f"- {fiche.path} — {fiche.resume}{mark}" if fiche.resume else f"- {fiche.path}{mark}"


def _date_of(stamp: str | None) -> date | None:
    match = _STAMP_DATE.match(stamp or "")
    return date(int(match.group(1)), int(match.group(2)), int(match.group(3))) if match else None


def last_claim_date(fiche: IndexedFile) -> date | None:
    """When the fiche was last backed by something said. None when a claim has no date (app data, old memory)."""
    known = fiches.parse_body(fiche.body).known
    dates = [_date_of(c.heure) for c in known]
    if not known or any(d is None for d in dates):
        return None
    return max(d for d in dates if d is not None)


class _Pass:
    """One pass in progress: its run, its clock, its budget, and the account it keeps."""

    def __init__(self, keeper: "NightKeeper", run: NightRun, tz: ZoneInfo, now: datetime, tick_calls: int):
        self.keeper = keeper
        self.run = run
        self.tz = tz
        self.now = now
        self.today = now.astimezone(tz).date()
        self.tick_left = tick_calls
        self.service = keeper.service
        self.user_id = run.user_id

    def spend(self) -> None:
        if self.run.calls >= settings.memory_night_max_calls:
            raise RunLimit
        if self.tick_left <= 0:
            raise TickLimit
        self.tick_left -= 1
        self.run.calls += 1

    async def ask(self, system: str, request: str) -> dict[str, Any]:
        self.spend()
        return parse_proposal(await self.keeper.ask(system, request))

    def note(self, key: str, value: Any) -> None:
        self.run.report.setdefault(key, []).append(value)

    def attempts(self, unit: str) -> int:
        return int((self.run.report.get("echecs") or {}).get(unit, 0))

    def failed(self, unit: str, why: str) -> None:
        failures = self.run.report.setdefault("echecs", {})
        failures[unit] = int(failures.get(unit, 0)) + 1
        logger.warning("Night of %s: %s left alone: %s", self.user_id, unit, why)

    def gave_up(self, unit: str) -> bool:
        return self.attempts(unit) >= MAX_ATTEMPTS

    def reason(self, what: str) -> str:
        return f"{NIGHT_LABEL} du {self.run.night} : {what}"

    async def save(self) -> None:
        await memory_night_repo.save(self.run)


class NightKeeper:
    """Runs the night for one owner at a time."""

    def __init__(self, service: MemoryService | None = None, encoder: Any = None, mcp: Any = None):
        self.client = create_llm_client() if llm_configured() else None
        self._service = service
        self._encoder = encoder
        self.mcp = mcp or mcp_client
        self._locks: dict[str, asyncio.Lock] = {}

    @property
    def service(self) -> MemoryService:
        return self._service or memory_service

    @property
    def encoder(self) -> Any:
        return self._encoder or default_encoder

    # ------------------------------------------------------------------ when, and how far

    async def due(self, user_id: str, now: datetime) -> bool:
        """A pass cut off resumes whatever the hour; otherwise one pass per local night, from the night hour on."""
        if await memory_night_repo.running(user_id) is not None:
            return True
        local = now.astimezone(await resolve_user_timezone(user_id))
        if local.hour < settings.memory_night_hour:
            return False
        return not await memory_night_repo.has_night(user_id, local.date().isoformat())

    async def run_all(self, now: datetime | None = None) -> list[NightRun]:
        """Every owner whose night is due. Never raises: the next tick tries again."""
        now = now or datetime.now(UTC)
        out: list[NightRun] = []
        if self.client is None or not self.service.enabled:
            return out
        for user_id in await self.service.users():
            run = await self.run(user_id, now)
            if run is not None:
                out.append(run)
        return out

    async def run(self, user_id: str, now: datetime | None = None, *, force: bool = False) -> NightRun | None:
        """One tick of the night for one owner: the pass that was cut off, else a new one if it is due (or forced).

        Returns the run as it stands (`status` `done` once it has ended), None when there was nothing to start.
        Never raises: it runs in the background, with nobody to tell.
        """
        service = self.service
        if self.client is None or not service.enabled or not is_ulid(user_id):
            return None
        async with self._locks.setdefault(user_id, asyncio.Lock()):
            try:
                return await self._run(user_id, now or datetime.now(UTC), force)
            except Exception:
                logger.exception("The night of %s failed", user_id)
                return None

    async def _run(self, user_id: str, now: datetime, force: bool) -> NightRun | None:
        tz = await resolve_user_timezone(user_id)
        run = await memory_night_repo.running(user_id)
        if run is None:
            if not force and not await self.due(user_id, now):
                return None
            run = await memory_night_repo.start(user_id, now.astimezone(tz).date().isoformat())
        pass_ = _Pass(self, run, tz, now, settings.memory_night_tick_calls)
        try:
            for step in STEPS:
                if f"step:{step}" in run.done:
                    continue
                try:
                    await getattr(self, f"_step_{step}")(pass_)
                except (TickLimit, ModelDown, RunLimit):
                    raise
                except Exception:
                    logger.exception("Night of %s: the step %s failed", user_id, step)
                    pass_.failed(f"step:{step}", "erreur inattendue")
                    await pass_.save()
                    if not pass_.gave_up(f"step:{step}"):
                        return run
                    pass_.note("reste", f"étape « {step} » abandonnée après une erreur")
                run.done.append(f"step:{step}")
                await pass_.save()
        except TickLimit:
            await pass_.save()
            return run
        except ModelDown as e:
            logger.warning("The night of %s waits for the model: %s", user_id, e)
            await pass_.save()
            return run
        except RunLimit:
            pass_.note("reste", "limite d'appels atteinte : le reste attend la prochaine nuit")
        await self._finish(pass_)
        return run

    async def _finish(self, p: _Pass) -> None:
        """The account of the pass: stored with the run, and in `journal.md` so that it can be read there."""
        p.run.status = DONE
        p.run.finished_at = datetime.now(UTC)
        p.run.report["appels"] = p.run.calls
        await p.save()
        line = summary_line(p.run)
        if line:
            await p.service.journal(p.user_id, line)
        try:
            await p.service.sync_user(p.user_id)
        except Exception as e:
            logger.warning("The night of %s could not synchronise at the end: %s", p.user_id, e)

    # ------------------------------------------------------------------ the model

    async def ask(self, system: str, request: str) -> str:
        model = settings.anthropic_model
        t0 = time.monotonic()
        try:
            response = await self.client.messages.create(
                model=model,
                max_tokens=MAX_TOKENS,
                system=system,
                messages=[{"role": "user", "content": request}],
            )
        except Exception as e:
            await note_provider_failure(e)
            record_llm_usage(
                model=model,
                call_type=CALL_TYPE,
                input_tokens=0,
                output_tokens=0,
                duration_seconds=time.monotonic() - t0,
                status="error",
            )
            raise ModelDown(str(e)) from e
        await note_provider_success()
        record_llm_usage(
            model=model, call_type=CALL_TYPE, duration_seconds=time.monotonic() - t0, **usage_kwargs(response.usage)
        )
        return "".join(getattr(block, "text", "") for block in response.content).strip()

    # ------------------------------------------------------------------ 1. the missing cartes

    async def _step_cartes(self, p: _Pass) -> None:
        silent_before = p.now - timedelta(seconds=settings.memory_encode_after_seconds)
        offset = 0
        for _ in range(ENCODING_MAX_PAGES):
            page = await context_repo.find_to_encode(silent_before, p.now, ENCODING_PAGE, offset, p.user_id)
            encoded_here = 0
            for ctx in page:
                p.spend()
                report = await self.encoder.encode(ctx, silent_before)
                if report is None:
                    continue
                encoded_here += 1
                p.note("cartes", str(ctx.label or "sans titre"))
                for written in report.written:
                    p.note("ecrites", written)
            if len(page) < ENCODING_PAGE:
                break
            offset += len(page) - encoded_here

        for conversation in await memory_index_repo.list_files(p.user_id, kind="conversation"):
            if conversation.resume or conversation.unreadable:
                continue
            unit = f"carte:{conversation.card_id}"
            if p.gave_up(unit):
                continue
            text = p.service.folder(p.user_id).read(conversation.path) or ""
            messages = messages_of(text)
            if not messages:
                continue
            try:
                proposal = await p.ask(
                    CARTE_SYSTEM, "Conversation :\n\n" + transcript(messages) + "\n\nRéponds par le JSON."
                )
            except ProposalError as e:
                p.failed(unit, str(e))
                continue
            if not _line(proposal.get("resume")):
                p.failed(unit, "le modèle n'a rendu aucun résumé")
                continue
            written = await conversations.update_carte_of_file(
                p.user_id,
                conversation.path,
                lambda carte, proposal=proposal, text=text: merge_carte(carte, proposal, text),
                reason=p.reason("carte rattrapée"),
                service=p.service,
            )
            if not written:
                p.failed(unit, "la conversation n'a pas de fil connu")
                continue
            p.note("cartes", _stem(conversation.path))

    # ------------------------------------------------------------------ 2. the conversations, one by one

    async def _step_fiches(self, p: _Pass) -> None:
        seen = await memory_night_repo.seen(p.user_id, SCOPE_CONSOLIDATED)
        # The paths begin with the date of the conversation: oldest first, so the newest word is the last one said.
        for conversation in await memory_index_repo.list_files(p.user_id, kind="conversation"):
            digest = carte_mod.content_hash(conversation.body)
            if conversation.unreadable or seen.get(conversation.card_id) == digest:
                continue
            unit = f"fiches:{conversation.card_id}"
            if p.gave_up(unit):
                continue
            try:
                await self._consolidate(p, conversation)
            except (ProposalError, fiches.FicheError, paths.UnsafePath, UnreadableFile) as e:
                p.failed(unit, str(e))
                continue
            await memory_night_repo.mark_seen(p.user_id, conversation.card_id, SCOPE_CONSOLIDATED, digest)
            p.note("consolidees", conversation.path)
            await p.save()

    async def _consolidate(self, p: _Pass, conversation: IndexedFile) -> None:
        text = p.service.folder(p.user_id).read(conversation.path) or conversation.body
        messages = messages_of(text)
        owner_minutes = {m.heure for m in messages if m.role == "Propriétaire"}
        if not owner_minutes:
            return
        passage = transcript(messages)
        listed = await p.service.fiches(p.user_id)
        carte = carte_mod.parse(text).carte
        request = self._consolidation_request(conversation, carte, listed, passage)
        proposal = await p.ask(CONSOLIDATE_SYSTEM, request)

        for item in _items(proposal.get("fiches"))[:MAX_PROPOSALS]:
            await self._apply_proposal(p, conversation, item, owner_minutes, {f.path for f in listed})

        enrich = proposal.get("carte")
        enrich = enrich if isinstance(enrich, dict) else {}
        extra = {"evoque": _strings(enrich.get("evoque")), "personnes": _strings(enrich.get("personnes"))}
        if extra["evoque"] or extra["personnes"]:
            await conversations.update_carte_of_file(
                p.user_id,
                conversation.path,
                lambda c: merge_carte(c, extra, text),
                reason=p.reason("`evoque` de la carte enrichi avec du recul"),
                service=p.service,
            )

    @staticmethod
    def _consolidation_request(conversation: IndexedFile, carte: Carte, listed: list[IndexedFile], passage: str) -> str:
        parts = [f"Conversation : {conversation.path}"]
        current = [f"résumé : {carte.resume}"] if carte.resume else []
        current += [f"moment : {m}" for m in carte.moments]
        if carte.evoque:
            current.append("évoque : " + ", ".join(carte.evoque))
        parts.append("Carte actuelle :\n" + ("\n".join(current) if current else "(aucune)"))
        if listed:
            shown = listed[:FICHES_LISTED]
            parts.append("Fiches existantes :\n" + "\n".join(_fiche_line(f) for f in shown))
            close = nearby(shown, passage)
            if close:
                parts.append(
                    "Contenu des fiches proches (affirmations telles qu'elles sont écrites) :\n"
                    + "\n\n".join(f"### {f.path}\n{_claims_text(f)}" for f in close[:FICHES_OPENED])
                )
        else:
            parts.append("Fiches existantes : aucune.")
        parts.append("Conversation à relire :\n" + passage)
        parts.append("Réponds par le JSON.")
        return "\n\n".join(parts)

    async def _apply_proposal(
        self, p: _Pass, conversation: IndexedFile, item: Any, owner_minutes: set[str], known_paths: set[str]
    ) -> None:
        if not isinstance(item, dict):
            return
        title = _line(item.get("titre"))
        chemin = _line(item.get("chemin")) or None
        if chemin is not None and chemin not in known_paths:
            p.note("laissees", {"unite": conversation.path, "pourquoi": f"« {chemin} » n'est pas une fiche connue"})
            return
        doubt = _line(item.get("doute"))
        if doubt:
            await self._ask_owner(p, chemin, doubt, title or (conversation.path))
            return
        claims = [
            {
                "texte": _line(a.get("texte")),
                "provenance": "dit",
                "source": conversation.path,
                "heure": _line(a.get("heure")),
            }
            for a in _dicts(item.get("ajouts"))[:MAX_CLAIMS]
            if _line(a.get("texte")) and _line(a.get("heure")) in owner_minutes
        ]
        replace = _dicts(item.get("remplace"))
        if not claims or not (title or chemin):
            return
        try:
            outcome = await self.upsert(
                p,
                titre=title,
                chemin=chemin,
                dossiers=_strings(item.get("dossiers")),
                resume=_line(item.get("resume")) or None,
                personnes=_strings(item.get("personnes")),
                evoque=_strings(item.get("evoque")),
                alias=_strings(item.get("alias")),
                claims=claims,
                replace=replace,
                what=f"conversation du {min(c['heure'] for c in claims)[:10]}",
            )
        except (fiches.FicheError, paths.UnsafePath, UnreadableFile) as e:
            p.note("laissees", {"unite": conversation.path, "pourquoi": str(e)})
            return
        if outcome is not None:
            p.note(outcome[1], outcome[0])

    async def upsert(
        self,
        p: _Pass,
        *,
        titre: str,
        chemin: str | None,
        dossiers: list[str],
        resume: str | None,
        personnes: list[str],
        evoque: list[str],
        alias: list[str],
        claims: list[dict[str, Any]],
        replace: list[dict[str, Any]],
        what: str,
    ) -> tuple[str, str] | None:
        """Create a fiche, complete it, or correct it: `(path, 'ecrites'|'completees'|'corrigees')`, None if no change.

        A correction keeps the old wording: it goes to « Ce qui n'est plus vrai » dated today, with its reason,
        unless it is more recent than what replaces it (then the fiche is left as it is and the case is reported).
        """
        service, user_id = p.service, p.user_id
        folder = service.folder(user_id)
        if chemin is None:
            if not titre:
                return None
            resume = resume or titre
            if len(dossiers) > fiches.MAX_FOLDERS:
                raise fiches.FicheError(f"At most {fiches.MAX_FOLDERS} nested folders.")
            target = paths.fiche_relative(dossiers, titre, [])
            chemin = target if folder.exists(target) else None

        previous: list[dict[str, Any]] = []
        marks: list[dict[str, str]] = []
        if chemin is not None:
            parsed = carte_mod.parse(await service.read(user_id, chemin) or "")
            before = fiches.parse_body(parsed.body)
            held = {c.key: c for c in before.known}
            newest = max((c["heure"] for c in claims if c.get("heure")), default="")
            for raw in replace:
                old = held.get(fiches.norm(_line(raw.get("texte"))))
                if old is None:
                    wanted = fiches.norm(_line(raw.get("texte")))
                    if wanted and wanted not in {c.key for c in before.replaced}:
                        p.note(
                            "laissees",
                            {"unite": chemin, "pourquoi": f"«{_line(raw.get('texte'))}» ne figure pas dans la fiche"},
                        )
                    continue
                if old.heure and newest and old.heure > newest:
                    raise fiches.FicheError(
                        f"«{old.texte}» est plus récente ({old.heure}) que ce qui devrait la remplacer "
                        f"({newest}) : laissée"
                    )
                marks.append(
                    {
                        "texte": old.texte,
                        "motif": _line(raw.get("motif")) or "remplacée par une affirmation plus récente",
                    }
                )
            marked = {fiches.norm(m["texte"]) for m in marks}
            claims = [c for c in claims if fiches.norm(c["texte"]) not in held]
            if not claims:
                return None
            previous = [
                {"texte": c.texte, "provenance": c.provenance, "source": c.source, "heure": c.heure}
                for c in before.known
                if c.key not in marked
            ]
            personnes = _union(parsed.carte.personnes, personnes)
            evoque = _union(parsed.carte.evoque, evoque)
            alias = _union(parsed.carte.alias, alias)
            resume = (resume if marks and resume else None) or parsed.carte.resume or resume
            titre = _stem(chemin)

        written = await fiches.write_fiche(
            service,
            user_id,
            titre=titre,
            affirmations=previous + claims,
            remplacees=marks,
            resume=resume,
            dossiers=dossiers if chemin is None else None,
            chemin=chemin,
            personnes=personnes or None,
            evoque=evoque or None,
            alias=alias or None,
            today=p.today,
            reason=p.reason(
                f"{len(claims)} affirmation(s) du propriétaire ({what})"
                + (f", {len(marks)} remplacée(s)" if marks else "")
            ),
        )
        kind = "ecrites" if written["cree"] else ("corrigees" if marks else "completees")
        return written["chemin"], kind

    async def _ask_owner(self, p: _Pass, chemin: str | None, question: str, subject: str) -> None:
        """A doubt becomes a question for the owner, carried by the fiche it concerns: nothing is decided silently."""
        if chemin is None:
            p.note("questions", {"chemin": None, "question": question, "sujet": subject})
            return
        text = await p.service.read(p.user_id, chemin) or ""
        parsed = carte_mod.parse(text)
        if parsed.carte.extra.get(QUESTION_KEY):
            return
        parsed.carte.extra[QUESTION_KEY] = question
        await p.service.write(
            p.user_id,
            chemin,
            carte_mod.render(parsed.carte, parsed.body),
            reason=p.reason(f"question à poser au propriétaire — {question}"),
        )
        p.note("questions", {"chemin": chemin, "question": question})

    # ------------------------------------------------------------------ 3. the regularities

    async def _step_regularites(self, p: _Pass) -> None:
        fresh = list(dict.fromkeys(p.run.report.get("consolidees") or []))[-REGULARITY_FRESH:]
        if not fresh:
            return
        folder = p.service.folder(p.user_id)
        parts = []
        for rel in fresh:
            owner = [m for m in messages_of(folder.read(rel) or "") if m.role == "Propriétaire"][
                :REGULARITY_OWNER_LINES
            ]
            if owner:
                parts.append(f"### {rel}\n" + transcript([Message(m.heure, m.role, m.content[:300]) for m in owner]))
        if not parts:
            return
        others = [
            f
            for f in reversed(await memory_index_repo.list_files(p.user_id, kind="conversation"))
            if f.path not in fresh
        ][:REGULARITY_OTHERS]
        old = [
            f"- {f.path} — {f.resume}" + "".join(f"\n    moment : {m}" for m in f.moments[:4])
            for f in others
            if f.resume
        ]
        listed = await p.service.fiches(p.user_id)
        request = "\n\n".join(
            [
                "Conversations relues cette nuit (ce que dit le propriétaire) :\n" + "\n\n".join(parts),
                "Autres conversations récentes (cartes) :\n" + ("\n".join(old) if old else "(aucune)"),
                "Fiches existantes :\n" + ("\n".join(_fiche_line(f) for f in listed[:FICHES_LISTED]) or "(aucune)"),
                "Réponds par le JSON.",
            ]
        )
        try:
            proposal = await p.ask(
                REGULARITY_SYSTEM.replace("{proofs}", str(settings.memory_night_min_proofs)), request
            )
        except ProposalError as e:
            p.failed("regularites", str(e))
            return
        for item in _items(proposal.get("regularites"))[:MAX_PROPOSALS]:
            try:
                await self._write_regularity(p, item)
            except (fiches.FicheError, paths.UnsafePath, UnreadableFile) as e:
                p.note("laissees", {"unite": "régularité", "pourquoi": str(e)})

    async def _write_regularity(self, p: _Pass, item: Any) -> None:
        if not isinstance(item, dict):
            return
        title = _line(item.get("titre"))
        chemin = _line(item.get("chemin")) or None
        if not title and not chemin:
            return
        folder = p.service.folder(p.user_id)
        proofs: dict[tuple[str, str], str] = {}
        for raw in _dicts(item.get("preuves")):
            conversation, heure, said = _line(raw.get("conversation")), _line(raw.get("heure")), _line(raw.get("texte"))
            try:
                rel = paths.clean_relative(conversation)
            except paths.UnsafePath:
                continue
            text = folder.read(rel) if paths.kind_of(rel) == "conversation" else None
            if said and text is not None and f"## {heure} — {conversations.ROLE_NAMES['user']}" in text:
                proofs.setdefault((rel, heure), said)
        needed = settings.memory_night_min_proofs
        if len({rel for rel, _ in proofs}) < needed:
            p.note(
                "laissees",
                {"unite": title or chemin, "pourquoi": f"régularité sans {needed} preuves valides : non écrite"},
            )
            return
        ordered = sorted(proofs, key=lambda key: (key[1], key[0]))
        claims = [{"texte": proofs[key], "provenance": "dit", "source": key[0], "heure": key[1]} for key in ordered]
        last_source, last_heure = ordered[-1]
        if title:
            claims.append({"texte": title, "provenance": "déduit", "source": last_source, "heure": last_heure})
        outcome = await self.upsert(
            p,
            titre=title,
            chemin=chemin,
            dossiers=_strings(item.get("dossiers")),
            resume=_line(item.get("resume")) or None,
            personnes=[],
            evoque=_strings(item.get("evoque")),
            alias=[],
            claims=claims,
            replace=[],
            what=f"régularité prouvée par {len(ordered)} message(s)",
        )
        if outcome is not None:
            p.note("regularites", {"chemin": outcome[0], "preuves": len(ordered)})

    # ------------------------------------------------------------------ 3b. the habits read in the data (MAG-18)

    async def _step_habitudes(self, p: _Pass) -> None:
        """Once per ISO week: what the agenda, the errands, the meals and the transactions show, as `observé` fiches."""
        iso = p.today.isocalendar()
        week = f"{iso.year}-W{iso.week:02d}"
        seen = await memory_night_repo.seen(p.user_id, SCOPE_HABITS)
        if seen.get(HABITS_WEEK) == week:
            return
        try:
            await self.mcp.ensure_connected()
            obs = await habits.observe(self.mcp, p.user_id, p.today, p.tz)
        except Exception as e:
            p.failed("habitudes", f"données illisibles : {e}")
            return
        if not obs.ok:
            p.failed("habitudes", "aucune donnée de l'application n'a répondu")
            return
        listed = await p.service.fiches(p.user_id)
        mine = habits.existing_habits([f for f in listed if not f.archived and not f.unreadable])
        if obs.items or mine:
            others = "\n".join(_fiche_line(f) for f in listed[:FICHES_LISTED] if f not in mine)
            request = habits.build_request(obs, habits.existing_block(mine), others)
            system = habits.HABITS_SYSTEM.replace("{proofs}", str(settings.memory_habits_min_proofs))
            try:
                proposal = await p.ask(system, request)
            except ProposalError as e:
                p.failed("habitudes", str(e))
                return
            for item in _items(proposal.get("habitudes"))[:MAX_PROPOSALS]:
                try:
                    await self._write_habit(p, obs, item, mine, seen)
                except (fiches.FicheError, paths.UnsafePath, UnreadableFile) as e:
                    p.note("laissees", {"unite": "habitude", "pourquoi": str(e)})
            for item in _dicts(proposal.get("cessees"))[:MAX_PROPOSALS]:
                try:
                    await self._cease_habit(p, obs, item, mine)
                except (fiches.FicheError, paths.UnsafePath, UnreadableFile) as e:
                    p.note("laissees", {"unite": "habitude cessée", "pourquoi": str(e)})
        if obs.down:
            # A module that did not answer is read again tomorrow, not in a week.
            p.note("reste", f"habitudes : {', '.join(sorted(obs.down))} illisible(s), relu(s) à la prochaine nuit")
            return
        await memory_night_repo.mark_seen(p.user_id, HABITS_WEEK, SCOPE_HABITS, week)

    async def _write_habit(
        self, p: _Pass, obs: habits.Observation, item: Any, mine: list[IndexedFile], seen: dict[str, str]
    ) -> None:
        if not isinstance(item, dict):
            return
        title = habits.clean(_line(item.get("titre")), 160)
        if not title:
            return
        service, user_id = p.service, p.user_id
        folder = service.folder(user_id)
        wanted = fiches.norm(title)
        by_path = {f.path: f for f in mine}
        found = by_path.get(_line(item.get("chemin"))) or next(
            (f for f in mine if wanted in {c.key for c in fiches.parse_body(f.body).known}), None
        )
        # A habit already proved only needs the new proofs it is given; a new one needs the minimum.
        proven = found is not None and wanted in {c.key for c in fiches.parse_body(found.body).known}
        proofs, why = habits.proofs_of(
            obs, item.get("preuves"), needed=1 if proven else settings.memory_habits_min_proofs
        )
        if why:
            p.note("laissees", {"unite": title, "pourquoi": why})
            return
        deleted_key = "habit:" + hashlib.sha1(wanted.encode()).hexdigest()[:16]
        dossiers = _strings(item.get("dossiers"))
        if found is not None:
            rel = found.path
        else:
            if len(dossiers) > fiches.MAX_FOLDERS:
                raise fiches.FicheError(f"At most {fiches.MAX_FOLDERS} nested folders.")
            rel = paths.fiche_relative(dossiers, title, [])
        exists = folder.exists(rel)
        if not exists and deleted_key in seen:
            p.note("laissees", {"unite": title, "pourquoi": "fiche supprimée par le propriétaire : non récrite"})
            return
        parsed = carte_mod.parse(await service.read(user_id, rel) or "") if exists else None
        before = fiches.parse_body(parsed.body) if parsed else fiches.Body()
        if wanted in {c.key for c in before.replaced}:
            p.note("laissees", {"unite": title, "pourquoi": "déjà marquée remplacée dans la fiche : non récrite"})
            return

        new = habits.habit_claims(obs, title, proofs)
        held = {c.key for c in before.known}
        added = []
        for claim in new:
            key = fiches.norm(claim["texte"])
            if key not in held and key not in {fiches.norm(a["texte"]) for a in added}:
                added.append(claim)
        added = added[: max(0, HABIT_FICHE_CLAIMS - len(held))]
        if not added:
            return
        period = new[0]["source"]
        affirmations = [
            {
                "texte": c.texte,
                "provenance": c.provenance,
                "source": period if c.key == wanted and fiches.is_observed_regularity(c) else c.source,
                "heure": c.heure,
            }
            for c in before.known
        ] + added
        evoque = _union(parsed.carte.evoque, _strings(item.get("evoque"))) if parsed else _strings(item.get("evoque"))
        resume = (parsed.carte.resume if parsed else "") or _line(item.get("resume")) or title
        written = await fiches.write_fiche(
            service,
            user_id,
            titre=_stem(rel) if exists else title,
            affirmations=affirmations,
            resume=resume,
            dossiers=None if exists else dossiers,
            chemin=rel if exists else None,
            evoque=evoque or None,
            today=p.today,
            reason=p.reason(f"{len(proofs)} donnée(s) lue(s) dans l'application ({self._period_of(obs)})"),
        )
        if not exists:
            await memory_night_repo.mark_seen(
                user_id, deleted_key, SCOPE_HABITS, hashlib.sha1(written["chemin"].encode()).hexdigest()[:16]
            )
        p.note("habitudes", {"chemin": written["chemin"], "preuves": len(proofs)})

    @staticmethod
    def _period_of(obs: habits.Observation) -> str:
        return f"du {obs.start.isoformat()} au {obs.today.isoformat()}"

    async def _cease_habit(
        self, p: _Pass, obs: habits.Observation, item: dict[str, Any], mine: list[IndexedFile]
    ) -> None:
        """A habit the data no longer show: dated and marked replaced, never erased."""
        chemin, texte = _line(item.get("chemin")), _line(item.get("texte"))
        fiche = next((f for f in mine if f.path == chemin), None)
        if fiche is None:
            p.note("laissees", {"unite": chemin or "habitude cessée", "pourquoi": "pas une fiche d'habitude connue"})
            return
        parsed = carte_mod.parse(await p.service.read(p.user_id, fiche.path) or "")
        before = fiches.parse_body(parsed.body)
        claim = next((c for c in before.known if c.key == fiches.norm(texte)), None)
        if claim is None or not fiches.is_observed_regularity(claim):
            why = f"«{texte}» n'est pas une habitude lue dans les données"
            p.note("laissees", {"unite": fiche.path, "pourquoi": why})
            return
        silent = habits.unreadable_for(obs, claim)
        if silent:
            why = f"{', '.join(silent)} illisible cette nuit : habitude gardée"
            p.note("laissees", {"unite": fiche.path, "pourquoi": why})
            return
        motif = _line(item.get("motif")) or "les données de la période ne la montrent plus"
        await fiches.write_fiche(
            p.service,
            p.user_id,
            titre=_stem(fiche.path),
            affirmations=[
                {"texte": c.texte, "provenance": c.provenance, "source": c.source, "heure": c.heure}
                for c in before.known
                if c.key != claim.key
            ],
            remplacees=[{"texte": claim.texte, "motif": motif}],
            chemin=fiche.path,
            today=p.today,
            reason=p.reason(f"habitude qui cesse ({motif})"),
        )
        p.note("habitudes_cessees", {"chemin": fiche.path, "texte": claim.texte})

    # ------------------------------------------------------------------ 4. the tidying

    async def _step_rangement(self, p: _Pass) -> None:
        for _ in range(MAX_TIDY_BATCHES):
            if not await self._tidy_batch(p):
                return
        p.note("reste", "rangement : des fiches changées attendent la prochaine nuit")

    async def _tidy_batch(self, p: _Pass) -> bool:
        """Ask for the tidying of the next fiches that changed. True when some are still waiting for their turn."""
        listed = [f for f in await p.service.fiches(p.user_id) if not f.unreadable]
        seen = await memory_night_repo.seen(p.user_id, SCOPE_TIDIED)
        changed = [f for f in listed if seen.get(f.card_id) != f.content_hash]
        if not changed:
            return False
        shown = changed[:FICHES_OPENED]
        changed_paths = {f.path for f in changed}
        lines = [
            ("* " if f.path in changed_paths else "  ") + _fiche_line(f).removeprefix("- ")
            for f in listed[:FICHES_LISTED]
        ]
        detail = "\n\n".join(f"### {f.path}\n{_claims_text(f)}" for f in shown)
        request = (
            "Fiches :\n"
            + "\n".join(lines)
            + "\n\nContenu des fiches changées :\n"
            + detail
            + "\n\nRéponds par le JSON."
        )
        try:
            proposal = await p.ask(TIDY_SYSTEM, request)
        except ProposalError as e:
            p.failed("rangement", str(e))
            return False
        for op in _items(proposal.get("operations"))[:MAX_OPERATIONS]:
            try:
                await self._tidy(p, op)
            except (fiches.FicheError, paths.UnsafePath, UnreadableFile) as e:
                p.note("laissees", {"unite": "rangement", "pourquoi": str(e)})
        # Only what the model was shown is done: the others keep their turn, tonight or the next night.
        looked_at = {f.card_id for f in shown}
        await self._mark_tidied(p, [f for f in await p.service.fiches(p.user_id) if f.card_id in looked_at])
        return len(changed) > len(shown)

    async def _mark_tidied(self, p: _Pass, listed: list[IndexedFile]) -> None:
        for fiche in listed:
            await memory_night_repo.mark_seen(p.user_id, fiche.card_id, SCOPE_TIDIED, fiche.content_hash)

    async def _tidy(self, p: _Pass, op: Any) -> None:
        if not isinstance(op, dict):
            return
        kind = _line(op.get("op")).casefold()
        why = _line(op.get("motif"))
        if kind in ("renommer", "deplacer", "déplacer"):
            chemin = await self._existing_fiche(p, op.get("chemin"))
            dossiers = _strings(op.get("dossiers")) if isinstance(op.get("dossiers"), list) else None
            new = await self._relocate(p, chemin, _line(op.get("titre")) or None, dossiers, why)
            if new is not None:
                p.note("renommees" if kind == "renommer" else "deplacees", {"de": chemin, "a": new})
        elif kind == "fusionner":
            await self._merge(p, op, why)
        elif kind == "scinder":
            await self._split(p, op, why)

    async def _existing_fiche(self, p: _Pass, chemin: Any) -> str:
        rel = paths.clean_relative(_line(chemin))
        indexed = await p.service.indexed(p.user_id, rel)
        if indexed is None or indexed.kind != "fiche" or indexed.unreadable:
            raise fiches.FicheError(f"«{rel}» n'est pas une fiche connue")
        return rel

    async def _rewrite_carte(self, p: _Pass, rel: str, change: Any, reason: str) -> None:
        """Change the carte of a file, whatever it is, the text below it carried over untouched."""
        if paths.kind_of(rel) == "conversation":
            await conversations.update_carte_of_file(p.user_id, rel, change, reason=reason, service=p.service)
            return
        parsed = carte_mod.parse(await p.service.read(p.user_id, rel) or "")
        before = copy.deepcopy(parsed.carte)
        after = change(parsed.carte)
        if after != before:
            await p.service.write(p.user_id, rel, carte_mod.render(after, parsed.body), reason=reason)

    async def _relocate(
        self, p: _Pass, old: str, titre: str | None, dossiers: list[str] | None, why: str
    ) -> str | None:
        folder = p.service.folder(p.user_id)
        new_dirs = dossiers if dossiers is not None else _directory(old)
        if len(new_dirs) > fiches.MAX_FOLDERS:
            raise fiches.FicheError(f"At most {fiches.MAX_FOLDERS} nested folders.")
        new_title = titre or _stem(old)
        new = paths.fiche_relative(new_dirs, new_title, [f for f in folder.files() if f != old])
        if new == old:
            return None
        await p.service.move(p.user_id, old, new, reason=p.reason("rangement" + (f" — {why}" if why else "")))
        if titre and _stem(new) != _stem(old):
            await self._rewrite_carte(
                p,
                new,
                lambda c: _with(c, alias=_union(c.alias, [_stem(old)])),
                p.reason(f"ancien titre gardé en alias — {_stem(old)}"),
            )
        return new

    async def _merge(self, p: _Pass, op: dict[str, Any], why: str) -> None:
        wanted = list(dict.fromkeys(_strings(op.get("chemins"))))
        if not 2 <= len(wanted) <= MAX_MERGED:
            raise fiches.FicheError("Une fusion porte sur 2 à 4 fiches.")
        rels = [await self._existing_fiche(p, rel) for rel in wanted]
        keep, others = rels[0], rels[1:]
        parsed = {rel: carte_mod.parse(await p.service.read(p.user_id, rel) or "") for rel in rels}
        bodies = {rel: fiches.parse_body(parsed[rel].body) for rel in rels}
        indexed = {rel: await p.service.indexed(p.user_id, rel) for rel in rels}

        known: dict[str, fiches.Claim] = {}
        replaced: dict[str, fiches.Claim] = {}
        rest: list[str] = []
        for rel in rels:
            for claim in bodies[rel].known:
                known.setdefault(claim.key, claim)
            for claim in bodies[rel].replaced:
                replaced.setdefault(claim.key, claim)
            if bodies[rel].rest:
                rest += ([""] if rest else []) + bodies[rel].rest
        for key in [k for k in replaced if k in known]:
            del replaced[key]

        base = parsed[keep].carte
        merged = Carte(
            id=base.id,
            resume=_line(op.get("resume"))
            or base.resume
            or next((parsed[r].carte.resume for r in others if parsed[r].carte.resume), ""),
            moments=_union(base.moments, [m for r in others for m in parsed[r].carte.moments], 50),
            personnes=_union(base.personnes, [x for r in others for x in parsed[r].carte.personnes]),
            evoque=_union(base.evoque, [x for r in others for x in parsed[r].carte.evoque]),
            alias=_union(base.alias, [_stem(r) for r in others] + [x for r in others for x in parsed[r].carte.alias]),
            liens=list(dict.fromkeys([*base.liens, *(i for r in others for i in parsed[r].carte.liens)])),
            sources=_union_sources(base.sources, [s for r in others for s in parsed[r].carte.sources]),
            extra=dict(base.extra),
        )
        if any(ARCHIVED_KEY not in parsed[r].carte.extra for r in rels):
            merged.extra.pop(ARCHIVED_KEY, None)
        if QUESTION_KEY not in merged.extra:
            question = next(
                (parsed[r].carte.extra[QUESTION_KEY] for r in others if parsed[r].carte.extra.get(QUESTION_KEY)), None
            )
            if question:
                merged.extra[QUESTION_KEY] = question
        body = fiches.Body(known=list(known.values()), replaced=list(replaced.values()), rest=rest)
        await p.service.write(
            p.user_id,
            keep,
            carte_mod.render(merged, fiches.render_body(body)),
            reason=p.reason(f"fusion de {len(rels)} fiches — {why or 'elles disaient la même chose'}"),
        )
        survivor = await p.service.indexed(p.user_id, keep)
        for rel in others:
            old = indexed[rel]
            if old is not None and survivor is not None:
                await self._repoint(p, old.card_id, survivor.card_id)
            await p.service.delete(p.user_id, rel, reason=p.reason(f"fusionnée dans « {_stem(keep)} »"))
        titre = _line(op.get("titre"))
        final = keep
        if titre and titre != _stem(keep):
            final = await self._relocate(p, keep, titre, None, why) or keep
        p.note("fusions", {"dans": final, "absorbees": others})

    async def _repoint(self, p: _Pass, old_id: str, new_id: str) -> None:
        """What pointed at an absorbed fiche points at the one that took it in."""

        def swap(carte: Carte) -> Carte:
            carte.liens = list(dict.fromkeys(new_id if i == old_id else i for i in carte.liens))
            carte.sources = [{**s, "id": new_id} if s.get("id") == old_id else s for s in carte.sources]
            return carte

        for pointer in await memory_index_repo.linked_from(p.user_id, old_id):
            await self._rewrite_carte(p, pointer, swap, p.reason("lien suivi vers la fiche fusionnée"))
        await misses.repoint(p.service, p.user_id, old_id, new_id)

    async def _split(self, p: _Pass, op: dict[str, Any], why: str) -> None:
        rel = await self._existing_fiche(p, op.get("chemin"))
        parts = _dicts(op.get("parties"))
        if not parts:
            raise fiches.FicheError("Une scission a besoin de parties.")
        parsed = carte_mod.parse(await p.service.read(p.user_id, rel) or "")
        body = fiches.parse_body(parsed.body)
        held = {c.key: c for c in body.known}
        taken: set[str] = set()
        planned: list[tuple[dict[str, Any], list[fiches.Claim]]] = []
        for part in parts:
            claims = []
            for text in _strings(part.get("affirmations")):
                claim = held.get(fiches.norm(text))
                if claim is None or claim.key in taken:
                    raise fiches.FicheError(
                        f"«{text}» n'est pas une affirmation de la fiche, ou déjà prise par une autre partie"
                    )
                taken.add(claim.key)
                claims.append(claim)
            if not claims or not _line(part.get("titre")):
                raise fiches.FicheError("Chaque partie a un titre et au moins une affirmation.")
            planned.append((part, claims))
        remaining = [c for c in body.known if c.key not in taken]
        if not remaining:
            raise fiches.FicheError("La fiche d'origine doit garder au moins une affirmation.")

        created: list[str] = []
        try:
            for part, claims in planned:
                dossiers = _strings(part.get("dossiers")) if isinstance(part.get("dossiers"), list) else _directory(rel)
                target = paths.fiche_relative(
                    dossiers, _line(part["titre"]), [f for f in p.service.folder(p.user_id).files()]
                )
                entries = [await fiches.stored_entry(p.service, p.user_id, c) for c in claims]
                carte = Carte(
                    resume=_line(part.get("resume")) or parsed.carte.resume,
                    personnes=parsed.carte.personnes,
                    evoque=parsed.carte.evoque,
                    sources=_union_sources([], entries),
                )
                await p.service.write(
                    p.user_id,
                    target,
                    carte_mod.render(carte, fiches.render_body(fiches.Body(known=claims))),
                    reason=p.reason(f"scission de « {_stem(rel)} » — {why or 'fourre-tout'}"),
                )
                created.append(target)
        except Exception:
            for target in created:
                await p.service.delete(p.user_id, target, reason=p.reason("scission annulée"))
            raise
        entries = [await fiches.stored_entry(p.service, p.user_id, c) for c in remaining + body.replaced]
        carte = _with(parsed.carte, sources=_union_sources([], entries))
        await p.service.write(
            p.user_id,
            rel,
            carte_mod.render(
                carte, fiches.render_body(fiches.Body(known=remaining, replaced=body.replaced, rest=body.rest))
            ),
            reason=p.reason(f"scindée en {len(created) + 1} fiches — {why or 'fourre-tout'}"),
        )
        p.note("scissions", {"de": rel, "en": created})

    # ------------------------------------------------------------------ 5. forgetting what was only circumstance

    async def _step_oubli(self, p: _Pass) -> None:
        cutoff = p.today - timedelta(days=settings.memory_night_forget_after_days)
        week = "-".join(str(x) for x in p.today.isocalendar()[:2])
        seen = await memory_night_repo.seen(p.user_id, SCOPE_FORGOTTEN)
        tidied = await memory_night_repo.seen(p.user_id, SCOPE_TIDIED)
        usage = await p.service.usage(p.user_id)
        # A fiche the owner expected Maggie to remember is not circumstance: its case keeps it out of the forgetting.
        expected = {c.attendue_id for c in await misses.cases(p.service, p.user_id) if c.attendue_id}
        candidates = []
        for fiche in await p.service.fiches(p.user_id):
            last = last_claim_date(fiche)
            if fiche.archived or fiche.unreadable or last is None or last > cutoff:
                continue
            if fiche.card_id in expected:
                continue
            if seen.get(fiche.card_id) == f"{fiche.content_hash}:{week}":
                continue
            candidates.append((fiche, last))
        for start in range(0, len(candidates), FORGET_BATCH):
            batch = candidates[start : start + FORGET_BATCH]
            lines = []
            for fiche, last in batch:
                pointers = len(await memory_index_repo.linked_from(p.user_id, fiche.card_id))
                lines.append(
                    f"### {fiche.path}\n{fiche.resume}\n{_claims_text(fiche)}\n"
                    f"Signaux : dernière affirmation il y a {(p.today - last).days} jours · ouverte "
                    f"{usage.get(fiche.card_id, 0)} fois · pointée par {pointers} fichier(s)"
                )
            try:
                proposal = await p.ask(
                    FORGET_SYSTEM, "Fiches à juger :\n\n" + "\n\n".join(lines) + "\n\nRéponds par le JSON."
                )
            except ProposalError as e:
                p.failed(f"oubli:{start}", str(e))
                continue
            by_path = {f.path: f for f, _ in batch}
            for decision in _dicts(proposal.get("decisions")):
                if _line(decision.get("chemin")) not in by_path:
                    continue
                if _line(decision.get("action")).casefold() not in ("oublier", "archiver", "mettre de côté"):
                    continue
                await self._archive(p, by_path[_line(decision["chemin"])], _line(decision.get("motif")), tidied)
            for fiche, _ in batch:
                current = await p.service.indexed(p.user_id, fiche.path)
                digest = (current or fiche).content_hash
                await memory_night_repo.mark_seen(p.user_id, fiche.card_id, SCOPE_FORGOTTEN, f"{digest}:{week}")

    async def _archive(self, p: _Pass, fiche: IndexedFile, why: str, tidied: dict[str, str]) -> None:
        """Out of the profile and the spontaneous recall; the file, its sources and the search stay."""
        text = await p.service.read(p.user_id, fiche.path) or ""
        parsed = carte_mod.parse(text)
        parsed.carte.extra[ARCHIVED_KEY] = p.today.isoformat()
        await p.service.write(
            p.user_id,
            fiche.path,
            carte_mod.render(parsed.carte, parsed.body),
            reason=p.reason(f"mise de côté, plus évoquée d'elle-même — {why or 'détail de circonstance'}"),
        )
        current = await p.service.indexed(p.user_id, fiche.path)
        if current is not None and tidied.get(fiche.card_id) == fiche.content_hash:
            await memory_night_repo.mark_seen(p.user_id, fiche.card_id, SCOPE_TIDIED, current.content_hash)
        p.note("archivees", {"chemin": fiche.path, "motif": why})

    # ------------------------------------------------------------------ 6. the orphans

    async def _step_orphelines(self, p: _Pass) -> None:
        files = await memory_index_repo.list_files(p.user_id)
        by_id = {f.card_id: f for f in files if f.kind == "conversation"}
        listed = [f for f in files if f.kind == "fiche" and not f.unreadable]
        no_source: list[str] = []
        unpointed: list[str] = []
        for fiche in listed:
            pointers = set(await memory_index_repo.linked_from(p.user_id, fiche.card_id))
            for source in fiche.sources:
                conversation = by_id.get(str(source.get("id") or ""))
                if conversation is not None and conversation.path not in pointers:
                    await self._rewrite_carte(
                        p,
                        conversation.path,
                        lambda c, fid=fiche.card_id: _with(c, liens=list(dict.fromkeys([*c.liens, fid]))),
                        p.reason(f"lien vers la fiche « {fiche.title} »"),
                    )
                    pointers.add(conversation.path)
                    p.note("liens", {"conversation": conversation.path, "fiche": fiche.path})
            if not fiche.sources:
                no_source.append(fiche.path)
            known = fiches.parse_body(fiche.body).known
            from_the_app = bool(known) and all(c.source.startswith(fiches.APP_SOURCE) for c in known)
            if not pointers and not from_the_app:
                unpointed.append(fiche.path)
        found = {"sans_source": no_source, "non_pointees": unpointed}
        last = await memory_night_repo.last_done(p.user_id)
        # Said once: the journal repeats an orphan only when the list changes.
        p.run.report["orphelines"] = found
        p.run.report["orphelines_deja_dites"] = last is not None and (last.report.get("orphelines") or {}) == found

    # ------------------------------------------------------------------ 7. the missed recalls

    async def _step_rappels(self, p: _Pass) -> None:
        todo = await misses.cases(p.service, p.user_id)
        p.run.report["rappels_total"] = len(todo)
        done = set(p.run.report.get("rappels_traites") or [])
        for case in todo:
            if case.path in done:
                continue
            unit = f"rappel:{case.path}"
            if p.gave_up(unit):
                continue
            try:
                await self._replay_case(p, case)
            except (ProposalError, fiches.FicheError, paths.UnsafePath, UnreadableFile) as e:
                p.failed(unit, str(e))
                continue
            p.note("rappels_traites", case.path)
            await p.save()

    async def _replay_case(self, p: _Pass, case: misses.Miss) -> None:
        """Replay one case; when the fiche does not come back, find why, fix the cause, and replay once more."""
        before_text, before = misses.render(case), case.statut
        cards = await p.service.cards(p.user_id)
        result = misses.replay(cards, case, p.today)
        first_cause, fixes, tried = "", [], set()
        for _ in range(MISS_REPAIRS):
            if result.ok:
                break
            cause, card = misses.diagnose(cards, case, p.today, on_disk=self._on_disk(p, case))
            first_cause = first_cause or cause
            fixed = await self._repair(p, case, cause, card, cards, tried)
            if fixed is None:
                break
            fixes.append(fixed)
            cards = await p.service.cards(p.user_id)
            result = misses.replay(cards, case, p.today)

        if result.card is not None:
            case.attendue, case.attendue_id = result.card.path, result.card.card_id
        case.ressorti = result.ressorti
        case.statut = misses.GREEN if result.ok else misses.RED
        if result.ok:
            case.vert_depuis = case.vert_depuis if before == misses.GREEN and case.vert_depuis else p.today.isoformat()
        else:
            case.vert_depuis = ""
        name = case.stem
        if first_cause:
            case.cause = first_cause
            fixed_text = " ; ".join(fixes)
            case.log(p.today, f"cause : {misses.CAUSES[first_cause]}" + (f" ; corrigé : {fixed_text}" if fixes else ""))
            p.note("rappels_causes", {"cas": name, "cause": first_cause, "correction": " ; ".join(fixes)})
        if before == misses.GREEN and first_cause:
            p.note("rappels_retours", {"cas": name, "cause": first_cause})
            case.log(p.today, "retour au rouge : la fiche ne ressortait plus")
        if result.ok:
            if before != misses.GREEN:
                case.log(p.today, "rejeu vert : la fiche ressort")
            p.note("rappels_verts", name)
        else:
            case.log(p.today, "rejeu rouge : la fiche ne ressort toujours pas")
            p.note("rappels_rouges", {"cas": name, "cause": first_cause or case.cause})
        if misses.render(case) != before_text:
            await misses.save(p.service, p.user_id, case)

    @staticmethod
    def _on_disk(p: _Pass, case: misses.Miss) -> bool:
        if not case.attendue or not paths.is_indexable(case.attendue):
            return False
        return p.service.folder(p.user_id).exists(case.attendue)

    async def _repair(
        self, p: _Pass, case: misses.Miss, cause: str, card: Any, cards: list[Any], tried: set[str]
    ) -> str | None:
        """One fix for one cause, described for the journal. None when there is nothing (more) to do."""
        if cause == misses.NOT_A_FICHE and not case.attendue_id and cause not in tried:
            tried.add(cause)
            created = await self._create_missing(p, case)
            return f"fiche « {_stem(created)} » créée" if created else None
        if cause == misses.NOT_INDEXED and cause not in tried:
            tried.add(cause)
            text = p.service.folder(p.user_id).read(case.attendue)
            if text is None:
                return None
            reason = p.reason("rappel manqué — fiche remise dans l'index")
            await p.service.write(p.user_id, case.attendue, text, reason=reason)
            return "fiche remise dans l'index"
        if cause == misses.SET_ASIDE and cause not in tried and card is not None:
            tried.add(cause)

            def wake(carte: Carte) -> Carte:
                carte.extra.pop(ARCHIVED_KEY, None)
                return carte

            reason = p.reason(f"rappel manqué « {case.demande} » — fiche remise en jeu, elle était mise de côté")
            await self._rewrite_carte(p, card.path, wake, reason)
            return "fiche remise en jeu (elle était mise de côté)"
        if cause in (misses.POOR_EVOQUE, misses.THRESHOLD, misses.CROWDED_OUT) and card is not None:
            if "model" not in tried:
                tried.add("model")
                added = await self._enrich(p, case, cause, card, cards)
                if added:
                    return added
            if "fallback" not in tried:
                tried.add("fallback")
                term = misses.fallback_term(case.demande)
                if term and recall.fold(term) not in {recall.fold(t) for t in card.evoque}:
                    await self._add_evoque(p, card.path, [term], case)
                    return f"`evoque` de « {_stem(card.path)} » + {term}"
        return None

    async def _add_evoque(self, p: _Pass, rel: str, terms: list[str], case: misses.Miss) -> None:
        await self._rewrite_carte(
            p,
            rel,
            lambda c: _with(c, evoque=_union(c.evoque, terms, MAX_EVOQUE_ADDED)),
            p.reason(f"rappel manqué « {case.demande} » — `evoque` de « {_stem(rel)} » enrichi : {', '.join(terms)}"),
        )

    @staticmethod
    def _terms(value: Any) -> list[str]:
        return [
            t
            for t in _strings(value)
            if len(t) <= MAX_TERM_CHARS and len(t.split()) <= MAX_TERM_WORDS and recall.significant(t)
        ][:MAX_EVOQUE_ADDED]

    async def _enrich(self, p: _Pass, case: misses.Miss, cause: str, card: Any, cards: list[Any]) -> str | None:
        """Ask for the associations that were missing, on the fiche and on the fiches that risk the same miss."""
        near = misses.neighbours(cards, card)
        indexed = await p.service.indexed(p.user_id, card.path)
        request = "\n\n".join(
            [
                f"Phrase du propriétaire : « {case.demande} »",
                f"Pourquoi la fiche n'est pas revenue : {misses.CAUSES[cause]}.",
                f"Fiche qui aurait dû revenir : {card.path}\nrésumé : {card.resume}\npersonnes : "
                + ", ".join(card.personnes)
                + "\névoque actuel : "
                + (", ".join(card.evoque) or "(rien)")
                + "\naffirmations :\n"
                + (_claims_text(indexed) if indexed is not None else ""),
                "Fiches proches :\n"
                + (
                    "\n".join(f"- {n.path} — {n.resume} (évoque : {', '.join(n.evoque) or 'rien'})" for n in near)
                    or "(aucune)"
                ),
                "Réponds par le JSON.",
            ]
        )
        proposal = await p.ask(MISS_SYSTEM, request)
        known = {recall.fold(t) for t in card.evoque}
        terms = [t for t in self._terms(proposal.get("evoque")) if recall.fold(t) not in known]
        parts = []
        if terms:
            await self._add_evoque(p, card.path, terms, case)
            parts.append(f"`evoque` de « {_stem(card.path)} » + {', '.join(terms)}")
        by_path = {n.path: n for n in near}
        for item in _dicts(proposal.get("voisines")):
            neighbour = by_path.get(_line(item.get("chemin")))
            if neighbour is None:
                continue
            owned = {recall.fold(t) for t in neighbour.evoque}
            extra = [t for t in self._terms(item.get("evoque")) if recall.fold(t) not in owned]
            if extra:
                await self._add_evoque(p, neighbour.path, extra, case)
                parts.append(f"fiche proche « {_stem(neighbour.path)} » + {', '.join(extra)}")
                p.note("rappels_voisines", neighbour.path)
        return " ; ".join(parts) or None

    async def _create_missing(self, p: _Pass, case: misses.Miss) -> str | None:
        """The fiche never existed: write it from the conversation where the owner said it, nothing he did not say."""
        wanted = case.souvenir or case.demande
        found = [f for f in await p.service.search(p.user_id, wanted, 10) if f.kind == "conversation"]
        known = {f.path for f in await p.service.fiches(p.user_id)}
        for conversation in found[:MISS_CANDIDATES]:
            text = p.service.folder(p.user_id).read(conversation.path) or conversation.body
            messages = messages_of(text)
            owner_minutes = {m.heure for m in messages if m.role == "Propriétaire"}
            if not owner_minutes:
                continue
            request = (
                f"Ce que le propriétaire attendait que Maggie sache : {wanted}\n"
                f"Phrase où elle aurait dû s'en souvenir : « {case.demande} »\n\n"
                f"Conversation : {conversation.path}\n{transcript(messages)}\n\nRéponds par le JSON."
            )
            proposal = await p.ask(MISS_FICHE_SYSTEM, request)
            for item in _items(proposal.get("fiches"))[:1]:
                if isinstance(item, dict):
                    item = {k: v for k, v in item.items() if k != "chemin"}
                before = {f.path for f in await p.service.fiches(p.user_id)}
                await self._apply_proposal(p, conversation, item, owner_minutes, known)
                created = sorted({f.path for f in await p.service.fiches(p.user_id)} - before)
                if created:
                    indexed = await p.service.indexed(p.user_id, created[0])
                    case.attendue = created[0]
                    case.attendue_id = indexed.card_id if indexed is not None else ""
                    return created[0]
        p.note("laissees", {"unite": case.path, "pourquoi": "aucune conversation ne dit ce souvenir : fiche non créée"})
        return None


def _with(carte: Carte, **changes: Any) -> Carte:
    data = {
        "id": carte.id,
        "resume": carte.resume,
        "moments": carte.moments,
        "personnes": carte.personnes,
        "evoque": carte.evoque,
        "alias": carte.alias,
        "liens": carte.liens,
        "sources": carte.sources,
        "extra": carte.extra,
    }
    data.update(changes)
    return Carte(**data)


def _union_sources(current: list[dict[str, Any]], new: list[dict[str, Any]]) -> list[dict[str, Any]]:
    out = list(current)
    seen = {(str(s.get("titre")), str(s.get("provenance"))) for s in out}
    for source in new:
        key = (str(source.get("titre")), str(source.get("provenance")))
        if key not in seen:
            seen.add(key)
            out.append(source)
    return out


_LABELS = (
    ("cartes", "carte(s) rattrapée(s)"),
    ("ecrites", "fiche(s) créée(s)"),
    ("completees", "fiche(s) complétée(s)"),
    ("corrigees", "fiche(s) corrigée(s)"),
    ("regularites", "régularité(s) prouvée(s)"),
    ("habitudes", "habitude(s) lue(s) dans les données"),
    ("habitudes_cessees", "habitude(s) cessée(s)"),
    ("fusions", "fusion(s)"),
    ("scissions", "scission(s)"),
    ("renommees", "renommage(s)"),
    ("deplacees", "déplacement(s)"),
    ("archivees", "fiche(s) mise(s) de côté"),
    ("questions", "question(s) à poser"),
    ("liens", "lien(s) tissé(s)"),
    ("laissees", "cas laissé(s) tel(s) quel(s)"),
)


def summary_line(run: NightRun) -> str | None:
    """The one line of `journal.md` that sums a pass up. None when the night changed nothing."""
    report = run.report
    parts = [f"{len(report[key])} {label}" for key, label in _LABELS if report.get(key)]
    unread = len(report.get("consolidees") or [])
    if unread:
        parts.insert(0, f"{unread} conversation(s) relue(s)")
    orphans = {} if report.get("orphelines_deja_dites") else report.get("orphelines") or {}
    for key, label in (("sans_source", "sans source"), ("non_pointees", "que rien ne pointe")):
        if orphans.get(key):
            parts.append(f"fiche(s) {label} : " + ", ".join(f"« {_stem(x)} »" for x in orphans[key][:5]))
    for item in report.get("questions") or []:
        where = f"sur `{item['chemin']}`" if item.get("chemin") else f"après {item.get('sujet', 'une conversation')}"
        parts.append(f"question {where} : {item['question']}")
    for item in report.get("archivees") or []:
        parts.append(f"mise de côté `{item['chemin']}` ({item.get('motif') or 'circonstance'})")
    for item in report.get("rappels_causes") or []:
        parts.append(
            f"rappel manqué « {item['cas']} » : {misses.CAUSES.get(item['cause'], item['cause'])}"
            + (f" — corrigé : {item['correction']}" if item.get("correction") else " — rien à corriger seul")
        )
    for item in report.get("rappels_retours") or []:
        parts.append(f"retour au rouge : « {item['cas']} » ne ressortait plus")
    if report.get("rappels_causes") or report.get("rappels_rouges") or report.get("rappels_retours"):
        green, total = len(report.get("rappels_verts") or []), report.get("rappels_total", 0)
        parts.append(f"{green}/{total} rappel(s) manqué(s) rejoué(s) au vert")
        for item in report.get("rappels_rouges") or []:
            parts.append(f"toujours rouge : « {item['cas']} »")
    if report.get("reste"):
        parts.append("; ".join(report["reste"]))
    if not parts:
        return None
    return f"{NIGHT_LABEL} du {run.night} ({run.calls} appel(s) au modèle) : " + " ; ".join(parts)


memory_night = NightKeeper()
