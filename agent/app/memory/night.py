"""The night (MAG-159, ADR-011): once a day, off the path of any answer, Maggie goes through the day with hindsight.

Three rhythms write the memory: right away during the turn (MAG-17), along the way a few minutes after a thread
goes quiet (MAG-15, `encoder.py`), and this one — what needs a second look. In order, one step after the other:

  1. **cartes**    catch up the missing cartes, and only those (the conversations themselves are never touched);
  1b. **échéances** put what is past its day and the deductions nothing reconfirmed under « Ce qui n'est plus vrai »,
                   without the model;
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
import logging
import time
from datetime import UTC, datetime
from typing import Any

from app.auth import TECHNICAL_ACCOUNT_EMAILS
from app.config import settings
from app.container import container
from app.db.memory_night_repository import DONE, NightRun, memory_night_repo
from app.llm.models import NIGHT, effort_kwargs, model_for
from app.llm.provider_status import note_provider_failure, note_provider_success
from app.mcp.client import mcp_client
from app.memory import misses
from app.memory.encoder import memory_encoder as default_encoder
from app.memory.night_passes import cartes, echeances, habitudes, orphelines, oubli, rangement, rappels, regularites
from app.memory.night_passes import fiches as fiches_pass
from app.memory.night_passes.common import (
    MAX_TOKENS,
    NIGHT_LABEL,
    ModelDown,
    Pass,
    RunLimit,
    TickLimit,
    _stem,
)
from app.memory.proposals import existing_target, merge_into_fiche
from app.memory.service import MemoryService, memory_service
from app.memory.ulid import is_ulid
from app.metrics import record_llm_usage, usage_kwargs
from app.user_timezone import is_technical_account, resolve_user_email, resolve_user_timezone

logger = logging.getLogger(__name__)

CALL_TYPE = "memory_night"
STEPS = ("cartes", "echeances", "fiches", "regularites", "habitudes", "rangement", "oubli", "orphelines", "rappels")
_Pass = Pass


class NightKeeper:
    """Runs the night for one owner at a time."""

    def __init__(self, service: MemoryService | None = None, encoder: Any = None, mcp: Any = None):
        self.client = container.llm_client
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
        """Every owner whose night is due, technical accounts left out. Never raises: the next tick tries again."""
        now = now or datetime.now(UTC)
        out: list[NightRun] = []
        if self.client is None or not self.service.enabled:
            return out
        for user_id in await self.service.users():
            if await resolve_user_email(user_id) in TECHNICAL_ACCOUNT_EMAILS:
                continue
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
        if await is_technical_account(user_id):
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
        pass_ = Pass(self, run, tz, now, settings.memory_night_tick_calls)
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

    async def _finish(self, p: Pass) -> None:
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
        model = model_for(NIGHT)
        t0 = time.monotonic()
        try:
            response = await self.client.messages.create(
                model=model,
                max_tokens=MAX_TOKENS,
                **effort_kwargs(NIGHT),
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

    async def _step_cartes(self, p: Pass) -> None:
        await cartes.run(p)

    async def _step_echeances(self, p: Pass) -> None:
        await echeances.run(p)

    async def _step_fiches(self, p: Pass) -> None:
        await fiches_pass.run(p)

    async def _step_regularites(self, p: Pass) -> None:
        await regularites.run(p)

    async def _step_habitudes(self, p: Pass) -> None:
        await habitudes.run(p)

    async def _step_rangement(self, p: Pass) -> None:
        await rangement.run(p)

    async def _step_oubli(self, p: Pass) -> None:
        await oubli.run(p)

    async def _step_orphelines(self, p: Pass) -> None:
        await orphelines.run(p)

    async def _step_rappels(self, p: Pass) -> None:
        await rappels.run(p)

    async def upsert(
        self,
        p: Pass,
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
        """One fiche, written or corrected: `(path, 'ecrites'|'completees'|'corrigees')`, None if no change."""
        left: list[str] = []
        unit = chemin
        try:
            if unit is None and titre:
                unit = existing_target(p.service, p.user_id, titre, dossiers)
            merged = await merge_into_fiche(
                p.service,
                p.user_id,
                titre=titre,
                chemin=chemin,
                dossiers=dossiers,
                resume=resume,
                personnes=personnes,
                evoque=evoque,
                alias=alias,
                claims=claims,
                replace=replace,
                today=p.today,
                reason=lambda n, marked: p.reason(
                    f"{n} affirmation(s) du propriétaire ({what})" + (f", {marked} remplacée(s)" if marked else "")
                ),
                left=left,
            )
        finally:
            for text in left:
                p.note("laissees", {"unite": unit, "pourquoi": f"«{text}» ne figure pas dans la fiche"})
        if merged is None:
            return None
        return merged.chemin, merged.kind


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
    ("echues", "affirmation(s) échue(s)"),
    ("deductions_de_cote", "déduction(s) mise(s) de côté"),
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
