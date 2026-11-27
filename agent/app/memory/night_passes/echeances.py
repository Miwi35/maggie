"""What has lapsed: past its day or a deduction nothing reconfirmed, without the model."""

from app.config import settings
from app.memory import carte as carte_mod
from app.memory import fiches
from app.memory.carte import ARCHIVED_KEY, UnreadableFile
from app.memory.night_passes.common import Pass


async def run(p: Pass) -> None:
    delay = settings.memory_night_deduction_after_days
    for fiche in await p.service.fiches(p.user_id):
        if fiche.archived or fiche.unreadable:
            continue
        if not fiches.lapsed(fiches.parse_body(fiche.body).known, p.today, delay):
            continue
        try:
            parsed = carte_mod.parse(await p.service.read(p.user_id, fiche.path) or "")
        except UnreadableFile:
            p.note("laissees", {"unite": fiche.path, "pourquoi": "illisible"})
            continue
        body = fiches.parse_body(parsed.body)
        due = fiches.lapsed(body.known, p.today, delay)
        if not due:
            continue
        gone = {id(c) for c, _ in due}
        body.known = [c for c in body.known if id(c) not in gone]
        for claim, motif in due:
            claim.replaced_on, claim.motif = p.today.isoformat(), motif
            body.replaced.append(claim)
        if not body.known:
            parsed.carte.extra[ARCHIVED_KEY] = p.today.isoformat()
        await p.service.write(
            p.user_id,
            fiche.path,
            carte_mod.render(parsed.carte, fiches.render_body(body)),
            reason=p.reason(f"échéances : {len(due)} affirmation(s) qui ne sont plus vraies"),
        )
        for claim, motif in due:
            if motif.startswith("valable"):
                p.note("echues", {"chemin": fiche.path, "texte": claim.texte, "motif": motif})
            else:
                p.note("deductions_de_cote", {"chemin": fiche.path, "texte": claim.texte, "motif": motif})
