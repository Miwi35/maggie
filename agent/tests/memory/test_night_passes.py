"""Each pass of the night runs alone (MAG-1051): a `Pass` built here, no `NightKeeper.run`."""

from datetime import timedelta
from unittest.mock import AsyncMock, patch

from app.db.memory_night_repository import memory_night_repo
from app.memory import carte as carte_mod
from app.memory import conversations
from app.memory.night_passes import cartes, oubli
from app.memory.night_passes import fiches as fiches_pass
from app.memory.night_passes.common import Pass
from tests.memory.conftest import USER_A
from tests.memory.test_night import (  # noqa: F401  (wired is an autouse fixture of the night's tests)
    FICHE_RENNES,
    NOW,
    PARIS,
    RENNES,
    TODAY,
    NightModel,
    conversation,
    fiche_files,
    keeper,
    minute,
    proposal,
    seed,
    wired,
)


async def a_pass(env, model: NightModel) -> Pass:
    return Pass(keeper(env, model), await memory_night_repo.start(USER_A, TODAY), PARIS, NOW, 40)


async def test_the_cartes_pass_runs_alone(env, chat_db):
    path = await conversation(chat_db, env, "On ira au concert à l'UBU.", NOW - timedelta(hours=2))
    p = await a_pass(env, NightModel(carte={"resume": "Un concert à l'UBU.", "moments": [], "personnes": [], "evoque": []}))

    await cartes.run(p)

    assert carte_mod.parse(env.local(path)).carte.resume == "Un concert à l'UBU."
    assert p.run.report["cartes"] == [path.rsplit("/", 1)[-1].removesuffix(".md")]
    assert p.run.calls == 1


async def test_the_fiches_pass_runs_alone(env, chat_db):
    when = NOW - timedelta(hours=3)
    await conversation(chat_db, env, "J'ai emménagé à Rennes.", when)
    claims = [{"texte": RENNES, "heure": minute(when), "citation": "J'ai emménagé à Rennes"}]
    p = await a_pass(env, NightModel(consolidate=proposal(claims=claims)))

    await fiches_pass.run(p)

    assert fiche_files(env) == [FICHE_RENNES]
    assert p.run.report["ecrites"] == [FICHE_RENNES]


async def test_the_oubli_pass_runs_alone(env, chat_db):
    old = NOW - timedelta(days=60)
    path = await conversation(chat_db, env, "Le plombier passe mardi.", old)
    await seed(env, "Le plombier passe mardi", "Le plombier passe mardi", path, minute(old), dossiers=("Maison",))
    fiche = "Maison/Le plombier passe mardi.md"
    decision = {"chemin": fiche, "action": "oublier", "motif": "un rendez-vous passé"}
    p = await a_pass(env, NightModel(forget={"decisions": [decision]}))

    await oubli.run(p)

    assert carte_mod.parse(env.local(fiche)).carte.extra["archivee"] == TODAY
    assert p.run.report["archivees"] == [{"chemin": fiche, "motif": "un rendez-vous passé"}]


async def test_the_night_merges_a_proposal_through_the_shared_merge(env, chat_db):
    when = NOW - timedelta(hours=3)
    await conversation(chat_db, env, "J'ai emménagé à Rennes.", when)
    claims = [{"texte": RENNES, "heure": minute(when), "citation": "J'ai emménagé à Rennes"}]
    p = await a_pass(env, NightModel(consolidate=proposal(claims=claims)))

    with patch("app.memory.night.merge_into_fiche", AsyncMock(return_value=None)) as merge:
        await fiches_pass.run(p)

    merge.assert_awaited_once()


def test_no_step_keeps_its_body_in_night_py():
    import inspect

    from app.memory.night import STEPS, NightKeeper

    for step in STEPS:
        source = inspect.getsource(getattr(NightKeeper, f"_step_{step}")).strip().splitlines()
        assert len(source) == 2, f"_step_{step} is a signature and one line"
