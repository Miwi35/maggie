"""The night replays the missed recalls (MAG-228): why the fiche did not come back, the cause fixed and told, the case
replayed against the index, a return to red reported."""

from datetime import timedelta

from app.memory import misses, recall
from app.memory import carte as carte_mod
from app.memory.night import summary_line
from tests.memory.conftest import USER_A
from tests.memory.test_night import (  # noqa: F401  (wired is an autouse fixture of the night's tests)
    NOW,
    TODAY,
    NightModel,
    conversation,
    journal,
    keeper,
    minute,
    proposal,
    run,
    seed,
    wired,
)

ROSES = "Famille/Françoise n'aime pas les roses.md"
ROSES_TITLE = "Françoise n'aime pas les roses"
DEMANDE = "Un bouquet pour la fête des mères"
CASE = f"Rappels manqués/{TODAY} — Un bouquet pour la fête des mères.md"
TODAY_DATE = NOW.date()


def card(title: str, *, evoque=(), personnes=(), extra: str = "") -> str:
    lines = ["---", f"resume: {title}"]
    if personnes:
        lines.append("personnes: [" + ", ".join(personnes) + "]")
    if evoque:
        lines.append("evoque: [" + ", ".join(evoque) + "]")
    if extra:
        lines.append(extra)
    return "\n".join([*lines, "---", "", f"{title}."]) + "\n"


async def write(env, rel: str, title: str, **kw) -> None:
    await env.service.write(USER_A, rel, card(title, **kw))


async def miss(env, *, demande: str = DEMANDE, chemin: str = ROSES, souvenir: str = "") -> misses.Miss:
    answer = await misses.record(
        env.service, USER_A, demande=demande, souvenir=souvenir, chemin=chemin, today=TODAY_DATE
    )
    return next(m for m in await misses.cases(env.service, USER_A) if m.path == answer["cas"])


async def case(env, path: str = CASE) -> misses.Miss:
    return next(m for m in await misses.cases(env.service, USER_A) if m.path == path)


async def evoque_of(env, rel: str) -> list[str]:
    return carte_mod.parse(env.local(rel)).carte.evoque


class TestTheFicheComesBackAfterTheNight:
    async def test_the_poor_carte_is_enriched_the_journal_says_so_and_the_replay_is_green(self, env):
        await write(env, ROSES, ROSES_TITLE)
        await miss(env)
        assert recall.rank(await env.service.cards(USER_A), DEMANDE, TODAY_DATE) == []
        model = NightModel(miss={"evoque": ["fête des mères", "offrir des fleurs"], "voisines": []})

        run_ = await run(keeper(env, model))

        assert await evoque_of(env, ROSES) == ["fête des mères", "offrir des fleurs"]
        found = recall.rank(await env.service.cards(USER_A), DEMANDE, TODAY_DATE)
        assert [r.path for r in found] == [ROSES]
        done = await case(env)
        assert done.statut == misses.GREEN and done.vert_depuis == TODAY and done.cause == misses.POOR_EVOQUE
        assert done.ressorti == [ROSES]
        assert run_.report["rappels_verts"] == [done.stem]
        text = journal(env)
        assert f"`evoque` de « {ROSES_TITLE} » enrichi : fête des mères, offrir des fleurs" in text
        assert "1/1 rappel(s) manqué(s) rejoué(s) au vert" in text
        assert "`evoque` trop pauvre" in text

    async def test_the_case_keeps_the_history_of_what_the_night_found_and_did(self, env):
        await write(env, ROSES, ROSES_TITLE)
        await miss(env)

        await run(keeper(env, NightModel(miss={"evoque": ["fête des mères"], "voisines": []})))

        history = " | ".join((await case(env)).historique)
        assert "enregistré" in history and "cause : `evoque` trop pauvre" in history
        assert "corrigé : `evoque` de « Françoise n'aime pas les roses » + fête des mères" in history
        assert "rejeu vert" in history

    async def test_when_the_model_has_nothing_better_the_demand_itself_becomes_the_association(self, env):
        await write(env, ROSES, ROSES_TITLE)
        await miss(env)

        await run(keeper(env, NightModel()))

        assert await evoque_of(env, ROSES) == ["bouquet fête mères"]
        assert (await case(env)).statut == misses.GREEN

    async def test_an_association_that_is_a_whole_sentence_is_not_taken(self, env):
        await write(env, ROSES, ROSES_TITLE)
        await miss(env)
        wordy = "quand le propriétaire cherche un cadeau pour sa mère à la fête"

        await run(keeper(env, NightModel(miss={"evoque": [wordy, "fête des mères"], "voisines": []})))

        assert await evoque_of(env, ROSES) == ["fête des mères"]

    async def test_a_carte_one_word_short_of_the_threshold_is_enriched_too(self, env):
        await write(env, ROSES, ROSES_TITLE)
        await miss(env, demande="Un bouquet de roses")

        await run(keeper(env, NightModel(miss={"evoque": ["bouquet de roses"], "voisines": []})))

        done = await case(env, f"Rappels manqués/{TODAY} — Un bouquet de roses.md")
        assert done.cause == misses.THRESHOLD and done.statut == misses.GREEN

    async def test_the_fiches_at_risk_of_the_same_miss_are_looked_at_too(self, env):
        await write(env, ROSES, ROSES_TITLE, personnes=["Françoise"])
        await write(env, "Famille/Son anniversaire.md", "Son anniversaire", personnes=["Françoise"])
        await write(env, "Cuisine/Gratin.md", "Le gratin")
        await miss(env)
        model = NightModel(
            miss={
                "evoque": ["fête des mères"],
                "voisines": [
                    {"chemin": "Famille/Son anniversaire.md", "evoque": ["fête de Françoise"]},
                    {"chemin": "Cuisine/Gratin.md", "evoque": ["gratin"]},
                ],
            }
        )

        run_ = await run(keeper(env, model))

        assert await evoque_of(env, "Famille/Son anniversaire.md") == ["fête de Françoise"]
        assert await evoque_of(env, "Cuisine/Gratin.md") == [], "not close to the fiche: not offered, not touched"
        assert "Cuisine/Gratin.md" not in model.asked("miss")[0]
        assert "Famille/Son anniversaire.md" in model.asked("miss")[0]
        assert run_.report["rappels_voisines"] == ["Famille/Son anniversaire.md"]
        assert "fiche proche « Son anniversaire » + fête de Françoise" in journal(env)

    async def test_the_model_is_given_the_phrase_the_cause_and_the_fiche(self, env):
        await write(env, ROSES, ROSES_TITLE, personnes=["Françoise"])
        await miss(env)
        model = NightModel()

        await run(keeper(env, model))

        asked = model.asked("miss")[0]
        assert DEMANDE in asked and ROSES in asked and "`evoque` trop pauvre" in asked and "(rien)" in asked

    async def test_a_fiche_set_aside_is_put_back_in_play(self, env):
        await write(env, ROSES, ROSES_TITLE, evoque=["fête des mères"], extra=f"archivee: {TODAY}")
        await miss(env)

        await run(keeper(env, NightModel()))

        done = await case(env)
        assert done.cause == misses.SET_ASIDE and done.statut == misses.GREEN
        assert "archivee" not in carte_mod.parse(env.local(ROSES)).carte.extra
        assert "remise en jeu" in journal(env)

    async def test_a_fiche_on_disk_that_the_index_lacks_is_indexed_again(self, env):
        env.service.folder(USER_A).write(ROSES, card(ROSES_TITLE, evoque=["fête des mères"]))
        assert await env.service.indexed(USER_A, ROSES) is None
        lost = misses.Miss(path=CASE, demande=DEMANDE, attendue=ROSES, enregistre=TODAY)
        await misses.save(env.service, USER_A, lost)

        await run(keeper(env, NightModel()))

        done = await case(env)
        assert done.cause == misses.NOT_INDEXED and done.statut == misses.GREEN
        assert done.attendue_id and await env.service.indexed(USER_A, ROSES) is not None
        assert "remise dans l'index" in journal(env)


class TestAFicheThatDidNotExist:
    SAID = f"{ROSES_TITLE}, surtout pour la fête des mères."

    async def test_the_fiche_is_written_from_the_conversation_where_the_owner_said_it(self, env, chat_db):
        when = NOW - timedelta(days=20)
        source = await conversation(chat_db, env, self.SAID, when)
        await miss(env, chemin="", souvenir=ROSES_TITLE)
        found = proposal(
            titre=ROSES_TITLE,
            dossiers=("Famille",),
            claims=[{"texte": ROSES_TITLE, "heure": minute(when)}],
        )
        found["fiches"][0]["evoque"] = ["fête des mères"]
        model = NightModel(miss_fiche=found)

        run_ = await run(keeper(env, model))

        assert env.local(ROSES) is not None and source in env.local(ROSES)
        done = await case(env)
        assert done.statut == misses.GREEN and done.cause == misses.NOT_A_FICHE
        assert done.attendue == ROSES and done.attendue_id
        assert run_.report["rappels_causes"][0]["cause"] == misses.NOT_A_FICHE
        assert f"fiche « {ROSES_TITLE} » créée" in journal(env)

    async def test_nothing_is_invented_when_no_conversation_says_it(self, env, chat_db):
        await conversation(chat_db, env, "On a parlé du gratin.", NOW - timedelta(days=20))
        await miss(env, chemin="", souvenir=ROSES_TITLE)
        model = NightModel()

        run_ = await run(keeper(env, model))

        assert env.local(ROSES) is None
        assert (await case(env)).statut == misses.RED
        assert run_.report["rappels_rouges"] and "toujours rouge" in journal(env)
        assert not model.asked("miss_fiche")

    async def test_a_fiche_the_owner_deleted_is_not_written_again(self, env):
        await write(env, ROSES, ROSES_TITLE, evoque=["fête des mères"])
        await miss(env)
        await env.service.delete(USER_A, ROSES)
        model = NightModel()

        await run(keeper(env, model))

        assert env.local(ROSES) is None and not model.asked("miss_fiche")
        assert (await case(env)).statut == misses.RED


class TestWhatTheOwnerExpectsIsNotForgotten:
    async def test_a_fiche_with_a_case_is_not_offered_to_the_forgetting(self, env, chat_db):
        old = NOW - timedelta(days=60)
        source = await conversation(chat_db, env, "Le plombier passe mardi.", old)
        await seed(env, "Le plombier passe mardi", "Le plombier passe mardi", source, minute(old), dossiers=("Maison",))
        plombier = "Maison/Le plombier passe mardi.md"
        await miss(env, demande="Quand passe le plombier", chemin=plombier)
        model = NightModel()

        await run(keeper(env, model))

        assert not any(plombier in asked for asked in model.asked("forget"))

    async def test_without_a_case_the_same_fiche_is_judged(self, env, chat_db):
        old = NOW - timedelta(days=60)
        source = await conversation(chat_db, env, "Le plombier passe mardi.", old)
        await seed(env, "Le plombier passe mardi", "Le plombier passe mardi", source, minute(old), dossiers=("Maison",))
        model = NightModel()

        await run(keeper(env, model))

        assert any("Maison/Le plombier passe mardi.md" in asked for asked in model.asked("forget"))


class TestReplayedEveryNight:
    async def test_a_green_case_stays_green_without_a_call_or_a_new_line_in_the_journal(self, env):
        await write(env, ROSES, ROSES_TITLE)
        await miss(env)
        await run(keeper(env, NightModel(miss={"evoque": ["fête des mères"], "voisines": []})))
        file_before, journal_before = env.local(CASE), journal(env)
        model = NightModel()

        second = await run(keeper(env, model), NOW + timedelta(days=1))

        assert not model.asked("miss")
        assert env.local(CASE) == file_before
        assert second.report["rappels_verts"] == [(await case(env)).stem]
        assert journal(env) == journal_before, "a quiet night writes nothing"

    async def test_a_case_that_fell_back_to_red_is_reported_and_repaired(self, env):
        await write(env, ROSES, ROSES_TITLE)
        await miss(env)
        await run(keeper(env, NightModel(miss={"evoque": ["fête des mères"], "voisines": []})))
        await write(env, ROSES, ROSES_TITLE)
        assert await evoque_of(env, ROSES) == []

        second = await run(keeper(env, NightModel()), NOW + timedelta(days=1))

        assert second.report["rappels_retours"] == [{"cas": (await case(env)).stem, "cause": misses.POOR_EVOQUE}]
        assert "retour au rouge" in journal(env)
        done = await case(env)
        assert done.statut == misses.GREEN
        assert any("retour au rouge" in line for line in done.historique)

    async def test_a_fiche_that_stays_out_is_red_and_the_night_says_so(self, env):
        await write(env, ROSES, ROSES_TITLE)
        for name in ("Un", "Deux", "Trois"):
            await write(env, f"Cadeaux/{name}.md", name, evoque=["fête des mères", "bouquet"])
        await miss(env)

        run_ = await run(keeper(env, NightModel()))

        done = await case(env)
        assert done.statut == misses.RED and done.vert_depuis == ""
        assert run_.report["rappels_rouges"] == [{"cas": done.stem, "cause": misses.POOR_EVOQUE}]
        assert "rejeu rouge" in done.historique[-1]
        assert f"toujours rouge : « {done.stem} »" in journal(env)
        assert "0/1 rappel(s) manqué(s) rejoué(s) au vert" in journal(env)

    async def test_the_owner_who_removes_a_case_is_not_asked_again(self, env):
        await write(env, ROSES, ROSES_TITLE)
        await miss(env)
        await env.service.delete(USER_A, CASE)
        model = NightModel()

        run_ = await run(keeper(env, model))

        assert not model.asked("miss") and run_.report["rappels_total"] == 0

    async def test_a_case_is_replayed_once_per_pass_even_when_the_pass_resumes(self, env, monkeypatch):
        from app.config import settings

        await write(env, ROSES, ROSES_TITLE)
        await miss(env)
        monkeypatch.setattr(settings, "memory_night_tick_calls", 1)
        model = NightModel(miss={"evoque": ["fête des mères"], "voisines": []})
        night = keeper(env, model)

        first = await run(night)
        resumed = await run(night, NOW + timedelta(minutes=10), force=False)

        assert first.status == "running"
        assert resumed.status == "done"
        assert len(model.asked("miss")) == 1
        assert (await case(env)).statut == misses.GREEN

    async def test_a_case_the_model_fails_on_does_not_stop_the_other_cases(self, env):
        await write(env, ROSES, ROSES_TITLE)
        await write(env, "Cuisine/Gratin.md", "Le gratin")
        await miss(env)
        await miss(env, demande="Quel plat pour dimanche", chemin="Cuisine/Gratin.md")
        model = NightModel(miss=["pas du JSON", {"evoque": ["plat du dimanche"], "voisines": []}])

        run_ = await run(keeper(env, model))

        assert run_.status == "done"
        states = {m.stem.split(" — ", 1)[1]: m.statut for m in await misses.cases(env.service, USER_A)}
        assert states["Quel plat pour dimanche"] == misses.OPEN, "left for the next night"
        assert states[DEMANDE] == misses.GREEN
        assert list(run_.report["echecs"].values()) == [1]


class TestTheCasesAreNotTheNightsToTidy:
    async def test_the_other_steps_neither_move_nor_forget_nor_index_a_case(self, env):
        await write(env, ROSES, ROSES_TITLE, evoque=["fête des mères"])
        await miss(env)
        model = NightModel()

        await run(keeper(env, model))

        assert env.local(CASE) is not None
        assert await env.service.indexed(USER_A, CASE) is None
        assert not any("Rappels manqués" in asked for step in ("forget", "tidy", "regularities") for asked in model.asked(step))


class TestTheSummary:
    def test_a_quiet_night_says_nothing_of_the_cases(self):
        from types import SimpleNamespace

        run_ = SimpleNamespace(report={"rappels_total": 2, "rappels_verts": ["a", "b"]}, night=TODAY, calls=0)

        assert summary_line(run_) is None

    def test_the_causes_the_returns_and_the_red_cases_are_told(self):
        from types import SimpleNamespace

        run_ = SimpleNamespace(
            report={
                "rappels_total": 3,
                "rappels_verts": ["a"],
                "rappels_causes": [{"cas": "a", "cause": misses.POOR_EVOQUE, "correction": "x"}],
                "rappels_retours": [{"cas": "b", "cause": misses.THRESHOLD}],
                "rappels_rouges": [{"cas": "c", "cause": misses.CROWDED_OUT}],
            },
            night=TODAY,
            calls=1,
        )

        line = summary_line(run_) or ""

        assert "rappel manqué « a » : `evoque` trop pauvre" in line
        assert "retour au rouge : « b » ne ressortait plus" in line
        assert "1/3 rappel(s) manqué(s) rejoué(s) au vert" in line
        assert "toujours rouge : « c »" in line

