"""The night puts what is past its day, and the deductions nothing reconfirmed, under « Ce qui n'est plus vrai »."""

from datetime import timedelta

from app.memory import carte as carte_mod
from app.memory import fiches
from app.memory.night import summary_line
from tests.memory.conftest import USER_A
from tests.memory.test_night import (  # noqa: F401  (wired is an autouse fixture of the night's tests)
    NOW,
    keeper,
    NightModel,
    conversation,
    journal,
    run,
    wired,
)

LATER = NOW + timedelta(days=1)
DAY = NOW.date().isoformat()
NIGHT = LATER.date().isoformat()
PATH = "Santé/Le propriétaire est en arrêt.md"


async def fiche(chat_db, env, claims, *, when=None, titre="Le propriétaire est en arrêt", **kw):
    when = when or NOW - timedelta(hours=3)
    source = await conversation(chat_db, env, "Je suis en arrêt de travail.", when)
    heure = when.astimezone(fiches_tz()).strftime("%Y-%m-%d %Hh%M")
    affirmations = [
        {"texte": t, "provenance": prov, "source": source, "heure": heure} | extra for t, prov, extra in claims
    ]
    for a in affirmations:
        if a["provenance"] == "dit":
            a["citation"] = "arrêt de travail"
    await fiches.write_fiche(
        env.service, USER_A, titre=titre, dossiers=["Santé"], resume="Arrêt", affirmations=affirmations,
        today=when.date(), **kw,
    )
    return f"Santé/{titre}.md"


def fiches_tz():
    from tests.memory.test_night import PARIS

    return PARIS


def body_of(env, path):
    return fiches.parse_body(carte_mod.parse(env.local(path)).body)


class TestWhatLapses:
    async def test_a_claim_past_its_day_moves_to_what_is_no_longer_true(self, chat_db, env):
        path = await fiche(
            chat_db, env, [("Il est en arrêt", "dit", {"valide_jusqu_au": DAY}), ("Il a un chien", "dit", {})]
        )

        run_ = await run(keeper(env, NightModel()), LATER)

        body = body_of(env, path)
        assert [c.texte for c in body.known] == ["Il a un chien"]
        assert (body.replaced[0].texte, body.replaced[0].replaced_on) == ("Il est en arrêt", NIGHT)
        assert body.replaced[0].motif == f"valable jusqu'au {DAY}"
        assert "~~Il est en arrêt~~" in env.local(path)
        assert run_.report["echues"][0]["texte"] == "Il est en arrêt"
        assert "Il est en arrêt" not in fiches.to_announce(env.local(path))
        assert "Il est en arrêt" not in [c.texte for c in body.known]

    async def test_a_deduction_never_reconfirmed_for_60_days_is_set_aside_with_its_reason(self, chat_db, env):
        when = LATER - timedelta(days=61)
        path = await fiche(chat_db, env, [("Il est lève-tôt", "déduit", {}), ("Il a un chien", "dit", {})], when=when)

        run_ = await run(keeper(env, NightModel()), LATER)

        body = body_of(env, path)
        assert [c.texte for c in body.known] == ["Il a un chien"]
        assert body.replaced[0].motif == f"déduction jamais reconfirmée depuis le {when.date().isoformat()}"
        assert run_.report["deductions_de_cote"][0]["chemin"] == path
        assert "Il est lève-tôt" not in fiches.to_announce(env.local(path))

    async def test_a_recent_deduction_and_an_old_said_claim_stay(self, chat_db, env):
        recent = await fiche(
            chat_db, env, [("Il est lève-tôt", "déduit", {})], when=LATER - timedelta(days=59), titre="Récente"
        )
        old = await fiche(
            chat_db, env, [("Il a un chien", "dit", {})], when=LATER - timedelta(days=200), titre="Ancienne"
        )

        run_ = await run(keeper(env, NightModel()), LATER)

        assert [c.texte for c in body_of(env, recent).known] == ["Il est lève-tôt"]
        assert [c.texte for c in body_of(env, old).known] == ["Il a un chien"]
        assert not run_.report.get("echues") and not run_.report.get("deductions_de_cote")

    async def test_a_fiche_left_with_nothing_true_is_set_aside(self, chat_db, env):
        path = await fiche(chat_db, env, [("Il est en arrêt", "dit", {"valide_jusqu_au": DAY})])

        await run(keeper(env, NightModel()), LATER)

        assert carte_mod.parse(env.local(path)).carte.extra[carte_mod.ARCHIVED_KEY] == NIGHT
        assert body_of(env, path).known == []

    async def test_an_archived_fiche_is_left_as_it_is(self, chat_db, env):
        path = await fiche(chat_db, env, [("Il est en arrêt", "dit", {"valide_jusqu_au": DAY})])
        parsed = carte_mod.parse(env.local(path))
        parsed.carte.extra[carte_mod.ARCHIVED_KEY] = "2026-10-01"
        await env.service.write(USER_A, path, carte_mod.render(parsed.carte, parsed.body))
        before = env.local(path)

        await run(keeper(env, NightModel()), LATER)

        assert env.local(path) == before

    async def test_the_journal_counts_what_lapsed(self, chat_db, env):
        await fiche(
            chat_db, env, [("Il est en arrêt", "dit", {"valide_jusqu_au": DAY})], titre="Un"
        )
        await fiche(
            chat_db, env, [("Il est lève-tôt", "déduit", {})], when=LATER - timedelta(days=61), titre="Deux"
        )

        run_ = await run(keeper(env, NightModel()), LATER)

        line = summary_line(run_)
        assert "1 affirmation(s) échue(s)" in line and "1 déduction(s) mise(s) de côté" in line
        assert "1 affirmation(s) échue(s)" in journal(env)
