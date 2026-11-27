"""The merge of a proposal into a fiche (MAG-1051): what the encoder and the night both rely on."""

from datetime import timedelta

import pytest

from app.memory import carte as carte_mod
from app.memory import fiches
from app.memory.proposals import Merged, merge_into_fiche, union_terms
from tests.memory.conftest import USER_A
from tests.memory.test_night import (  # noqa: F401  (wired is an autouse fixture of the night's tests)
    FICHE_RENNES,
    LYON,
    NOW,
    RENNES,
    conversation,
    minute,
    said,
    seed,
    wired,
)


def claim(env, path: str, when, texte: str) -> dict:
    heure = minute(when)
    return {"texte": texte, "provenance": "dit", "source": path, "heure": heure, "citation": said(env, path, heure)}


async def merge(env, claims, *, chemin=None, titre=RENNES, replace=None, **kw):
    return await merge_into_fiche(
        env.service,
        USER_A,
        titre=titre,
        chemin=chemin,
        dossiers=["Lieu de vie"],
        resume="Où habite le propriétaire",
        personnes=[],
        evoque=[],
        alias=[],
        claims=claims,
        replace=replace or [],
        today=NOW.date(),
        reason=lambda n, marked: f"test : {n} claim(s), {marked} replaced",
        **kw,
    )


async def test_a_new_fiche_is_written(env, chat_db):
    when = NOW - timedelta(hours=3)
    path = await conversation(chat_db, env, "J'ai emménagé à Rennes.", when)

    merged = await merge(env, [claim(env, path, when, RENNES)])

    assert merged == Merged(chemin=FICHE_RENNES, kind="ecrites", left=[])
    assert RENNES in env.local(FICHE_RENNES)


async def test_claims_already_held_are_dropped(env, chat_db):
    when = NOW - timedelta(hours=3)
    path = await conversation(chat_db, env, "J'ai emménagé à Rennes.", when)
    await seed(env, RENNES, RENNES, path, minute(when))

    assert await merge(env, [claim(env, path, when, RENNES)], chemin=FICHE_RENNES) is None


def test_terms_are_united_without_duplicates():
    assert union_terms(["Léa", "sorties"], ["léa", "concert", "UBU"], 1) == ["Léa", "sorties", "concert"]


async def test_a_replacement_older_than_its_replacement_is_refused(env, chat_db):
    early, late = NOW - timedelta(days=1), NOW - timedelta(hours=3)
    before = await conversation(chat_db, env, "Je m'installe à Lyon.", early)
    after = await conversation(chat_db, env, "J'ai emménagé à Rennes.", late)
    await seed(env, RENNES, RENNES, after, minute(late))

    with pytest.raises(fiches.FicheError, match="plus récente"):
        await merge(
            env,
            [claim(env, before, early, LYON)],
            chemin=FICHE_RENNES,
            replace=[{"texte": RENNES, "motif": "Lyon"}],
        )


async def test_refuse_replaced_leaves_a_claim_marked_no_longer_true(env, chat_db):
    first, second = NOW - timedelta(days=1, hours=3), NOW - timedelta(hours=3)
    before = await conversation(chat_db, env, "J'ai emménagé à Rennes.", first)
    after = await conversation(chat_db, env, "Finalement, je m'installe à Lyon.", second)
    await seed(env, RENNES, RENNES, before, minute(first))
    merged = await merge(
        env, [claim(env, after, second, LYON)], chemin=FICHE_RENNES, replace=[{"texte": RENNES, "motif": "Lyon"}]
    )
    assert merged is not None and merged.kind == "corrigees"

    with pytest.raises(fiches.FicheError, match="ne sont plus vraies"):
        await merge(env, [claim(env, before, first, RENNES)], chemin=merged.chemin, refuse_replaced=True)


async def test_a_replacement_not_in_the_fiche_is_reported_in_left(env, chat_db):
    when = NOW - timedelta(hours=3)
    path = await conversation(chat_db, env, "J'ai emménagé à Rennes.", when)
    later = NOW - timedelta(hours=1)
    other = await conversation(chat_db, env, "Je m'installe à Lyon.", later)
    await seed(env, RENNES, RENNES, path, minute(when))
    nantes = "Le propriétaire habite à Nantes"

    merged = await merge(
        env, [claim(env, other, later, LYON)], chemin=FICHE_RENNES, replace=[{"texte": nantes, "motif": "Lyon"}]
    )

    assert merged == Merged(chemin=FICHE_RENNES, kind="completees", left=[nantes])
    assert carte_mod.parse(env.local(FICHE_RENNES)).carte.resume
