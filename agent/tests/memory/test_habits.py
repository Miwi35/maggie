"""The habits read in the app's data (MAG-18): a weekly step of the night, `observé`, each with its period and its proofs."""

from datetime import date, timedelta
from pathlib import Path
from zoneinfo import ZoneInfo

from app.config import settings
from app.db.memory_night_repository import memory_night_repo
from app.llm.fake import FakeAnthropicClient
from app.memory import carte as carte_mod
from app.memory import fiches, habits
from app.memory.night import summary_line
from tests.memory.conftest import USER_A
from tests.memory.fake_mcp import HABITS_TOOLS, FakeMcp, all_day_event, errand, timed_event
from tests.memory.test_night import NOW, NightModel, journal, keeper, run, wired  # noqa: F401

PARIS = ZoneInfo("Europe/Paris")
TODAY = date(2026, 10, 14)
SATURDAYS = ("2026-09-12", "2026-09-19", "2026-09-26", "2026-10-03")
SHOPPING = "Le propriétaire fait ses courses le samedi matin"
SHOPPING_FICHE = f"Habitudes/{SHOPPING}.md"
STONER = "Le propriétaire aime le stoner, le doom et le rock psychédélique"
BANDS = ("Black Wizards", "Moundrag", "Red Sun Atacama", "Kadavar", "Slift", "Monolord")


def saturday_errands():
    return [errand(f"{day}T10:{index:02d}:00+02:00") for index, day in enumerate(SATURDAYS, start=10)]


def shopping(proofs=("C1", "C2", "C3", "C4"), **extra):
    return {
        "titre": SHOPPING,
        "dossiers": ["Habitudes"],
        "resume": "Quand il fait ses courses",
        "evoque": ["courses", "samedi matin"],
        "preuves": list(proofs),
    } | extra


def concerts():
    days = ("2026-08-01", "2026-08-29", "2026-09-19", "2026-10-31", "2026-11-14", "2026-12-05")
    return {
        day: [all_day_event(day, band, location="Salle")] for day, band in zip(days[:3], BANDS[:3], strict=True)
    }, [all_day_event(day, band) for day, band in zip(days[3:], BANDS[3:], strict=True)]


async def night_with(env, mcp: FakeMcp, **answers):
    model = NightModel(**answers)
    night = keeper(env, model)
    night.mcp = mcp
    return night, model


def body_of(env, rel):
    return fiches.parse_body(carte_mod.parse(env.local(rel) or "").body)


class TestAHabitIsWrittenWithItsProof:
    async def test_the_habit_is_observed_sourced_on_its_period_and_followed_by_each_proof(self, env):
        night, model = await night_with(env, FakeMcp(errands=saturday_errands()), habits=[{"habitudes": [shopping()]}])

        await run(night)

        body = body_of(env, SHOPPING_FICHE)
        habit, *proofs = body.known
        assert (habit.texte, habit.provenance) == (SHOPPING, "observé")
        assert habit.source == "app : courses, sur la période du 2026-07-22 au 2026-10-14"
        assert fiches.is_observed_regularity(habit)
        assert len(proofs) == 4
        assert all(p.provenance == "observé" and p.source.startswith("app : courses, 2026-") for p in proofs)
        assert "samedi 2026-10-03" in proofs[-1].texte
        assert "Carrefour" in proofs[-1].texte
        assert not any(fiches.is_observed_regularity(p) for p in proofs)

    async def test_the_habit_is_announced_as_deduced_from_an_observation_and_its_proofs_are_not(self, env):
        night, _ = await night_with(env, FakeMcp(errands=saturday_errands()), habits=[{"habitudes": [shopping()]}])

        await run(night)

        assert fiches.to_announce(env.local(SHOPPING_FICHE)) == [SHOPPING]

    async def test_the_fiche_is_recalled_by_the_situations_the_model_gives(self, env):
        night, _ = await night_with(env, FakeMcp(errands=saturday_errands()), habits=[{"habitudes": [shopping()]}])

        await run(night)

        assert carte_mod.parse(env.local(SHOPPING_FICHE)).carte.evoque == ["courses", "samedi matin"]

    async def test_the_journal_says_the_habit_was_read_in_the_data(self, env):
        night, _ = await night_with(env, FakeMcp(errands=saturday_errands()), habits=[{"habitudes": [shopping()]}])

        done = await run(night)

        assert done is not None and "1 habitude(s) lue(s) dans les données" in (summary_line(done) or "")
        assert "lue(s) dans l'application" in journal(env)

    async def test_the_model_is_given_the_numbered_data_and_the_period(self, env):
        night, model = await night_with(env, FakeMcp(errands=saturday_errands()))

        await run(night)

        request = model.asked("habits")[0]
        assert "C4 | 2026-10-03 samedi 10:13 | courses | courses chez Carrefour" in request
        assert "du 2026-07-22 au 2026-10-14" in request

    async def test_the_mcp_is_asked_for_the_owner_and_the_whole_period(self, env):
        mcp = FakeMcp(errands=saturday_errands())
        night, _ = await night_with(env, mcp)

        await run(night)

        assert {user for _, _, user in mcp.calls} == {USER_A}
        reads = [arguments for name, arguments, _ in mcp.calls if name == "get_events_by_date"]
        assert [(r["date"], r["endDate"]) for r in reads] == [
            ("2026-07-22", "2026-08-21"),
            ("2026-08-22", "2026-09-21"),
            ("2026-09-22", "2026-10-14"),
        ]
        assert mcp.called("get_upcoming_events") == 1
        assert ("get_upcoming_events", {"days": settings.memory_habits_future_days}, USER_A) in mcp.calls


class TestNoProofNoHabit:
    async def test_a_key_that_was_not_read_leaves_the_habit_unwritten(self, env):
        night, _ = await night_with(
            env, FakeMcp(errands=saturday_errands()), habits=[{"habitudes": [shopping(("C1", "C2", "C9"))]}]
        )

        done = await run(night)

        assert done is not None
        assert env.local(SHOPPING_FICHE) is None
        assert "ne désigne aucune donnée lue" in done.report["laissees"][0]["pourquoi"]

    async def test_fewer_proofs_than_the_minimum_leave_it_unwritten(self, env):
        night, _ = await night_with(
            env, FakeMcp(errands=saturday_errands()), habits=[{"habitudes": [shopping(("C1", "C2"))]}]
        )

        done = await run(night)

        assert done is not None
        assert env.local(SHOPPING_FICHE) is None
        assert "2 jour(s) de preuve au lieu de 3" in done.report["laissees"][0]["pourquoi"]

    async def test_proofs_of_one_day_count_once(self, env):
        same_day = [errand("2026-10-03T10:10:00+02:00"), errand("2026-10-03T16:10:00+02:00", "Lidl")]
        night, _ = await night_with(env, FakeMcp(errands=same_day), habits=[{"habitudes": [shopping(("C1", "C2"))]}])

        await run(night)

        assert env.local(SHOPPING_FICHE) is None

    async def test_a_habit_without_any_proof_is_not_written(self, env):
        night, _ = await night_with(env, FakeMcp(errands=saturday_errands()), habits=[{"habitudes": [shopping(())]}])

        await run(night)

        assert env.local(SHOPPING_FICHE) is None

    async def test_data_older_than_the_period_is_not_shown(self, env):
        night, model = await night_with(env, FakeMcp(errands=[errand("2026-01-03T10:10:00+02:00")]))

        await run(night)

        assert model.asked("habits") == []


class TestOnceAWeek:
    async def test_nothing_to_read_asks_the_model_nothing(self, env):
        night, model = await night_with(env, FakeMcp())

        await run(night)

        assert model.asked("habits") == []

    async def test_the_week_is_read_once(self, env):
        mcp = FakeMcp(errands=saturday_errands())
        night, model = await night_with(env, mcp)
        await run(night)
        reads = mcp.called("list_past_errands")

        await run(night, NOW + timedelta(days=1))

        assert len(model.asked("habits")) == 1
        assert mcp.called("list_past_errands") == reads

    async def test_the_next_week_reads_again(self, env):
        night, model = await night_with(env, FakeMcp(errands=saturday_errands()))
        await run(night)

        await run(night, NOW + timedelta(days=7))

        assert len(model.asked("habits")) == 2

    async def test_a_module_that_does_not_answer_is_read_again_the_next_night(self, env):
        mcp = FakeMcp(errands=saturday_errands(), down=("manage_meals",))
        night, model = await night_with(env, mcp)

        first = await run(night)
        await run(night, NOW + timedelta(days=1))

        assert len(model.asked("habits")) == 2
        assert "Illisibles cette nuit : repas" in model.asked("habits")[0]
        assert first is not None and "habitudes : repas illisible(s)" in first.report["reste"][0]

    async def test_no_module_answering_asks_nothing_and_is_tried_again(self, env):
        mcp = FakeMcp(down=HABITS_TOOLS)
        night, model = await night_with(env, mcp)

        await run(night)

        assert model.asked("habits") == []
        assert await memory_night_repo.seen(USER_A, "habits") == {}


class TestAHabitThatGrowsOrStops:
    async def written(self, env, mcp):
        night, model = await night_with(env, mcp, habits=[{"habitudes": [shopping(("C1", "C2", "C3"))]}])
        await run(night)
        return night, model

    async def test_a_new_proof_completes_the_fiche_and_moves_the_period_on(self, env):
        night, model = await self.written(env, FakeMcp(errands=saturday_errands()[:3]))
        night.mcp = FakeMcp(errands=saturday_errands())
        model.answers["habits"] = [{"habitudes": [shopping(("C4",))], "cessees": []}]

        await run(night, NOW + timedelta(days=7))

        habit, *proofs = body_of(env, SHOPPING_FICHE).known
        assert len(proofs) == 4
        assert habit.source == "app : courses, sur la période du 2026-07-29 au 2026-10-21"

    async def test_nothing_new_leaves_the_fiche_alone(self, env):
        night, model = await self.written(env, FakeMcp(errands=saturday_errands()))
        before = env.local(SHOPPING_FICHE)
        model.answers["habits"] = [{"habitudes": [shopping(("C1", "C2", "C3"))], "cessees": []}]

        await run(night, NOW + timedelta(days=7))

        assert env.local(SHOPPING_FICHE) == before

    async def test_a_habit_that_stops_is_dated_and_marked_replaced_not_erased(self, env):
        night, model = await self.written(env, FakeMcp(errands=saturday_errands()))
        night.mcp = FakeMcp()
        ceased = {"chemin": SHOPPING_FICHE, "texte": SHOPPING, "motif": "plus de courses le samedi depuis six semaines"}
        model.answers["habits"] = [{"habitudes": [], "cessees": [ceased]}]

        done = await run(night, NOW + timedelta(days=7))

        assert done is not None
        body = body_of(env, SHOPPING_FICHE)
        assert SHOPPING not in [c.texte for c in body.known]
        old = body.replaced[0]
        assert (old.texte, old.replaced_on) == (SHOPPING, "2026-10-21")
        assert "six semaines" in old.motif
        assert len(body.known) == 3
        assert done.report["habitudes_cessees"][0]["chemin"] == SHOPPING_FICHE
        assert fiches.to_announce(env.local(SHOPPING_FICHE)) == []

    async def test_silence_in_a_module_that_did_not_answer_ends_nothing(self, env):
        night, model = await self.written(env, FakeMcp(errands=saturday_errands()))
        night.mcp = FakeMcp(down=("list_past_errands",))
        ceased = {"chemin": SHOPPING_FICHE, "texte": SHOPPING, "motif": "plus de courses"}
        model.answers["habits"] = [{"habitudes": [], "cessees": [ceased]}]

        done = await run(night, NOW + timedelta(days=7))

        assert done is not None
        assert SHOPPING in [c.texte for c in body_of(env, SHOPPING_FICHE).known]
        assert "courses illisible cette nuit" in done.report["laissees"][0]["pourquoi"]

    async def test_a_proof_line_cannot_be_ended_as_a_habit(self, env):
        night, model = await self.written(env, FakeMcp(errands=saturday_errands()))
        proof = body_of(env, SHOPPING_FICHE).known[1].texte
        model.answers["habits"] = [
            {"habitudes": [], "cessees": [{"chemin": SHOPPING_FICHE, "texte": proof, "motif": "x"}]}
        ]

        await run(night, NOW + timedelta(days=7))

        assert proof in [c.texte for c in body_of(env, SHOPPING_FICHE).known]

    async def test_a_habit_the_owner_deleted_is_not_written_again(self, env):
        night, model = await self.written(env, FakeMcp(errands=saturday_errands()))
        await env.service.delete(USER_A, SHOPPING_FICHE, reason="le propriétaire l'a supprimée")
        model.answers["habits"] = [{"habitudes": [shopping()], "cessees": []}]

        await run(night, NOW + timedelta(days=7))

        assert env.local(SHOPPING_FICHE) is None

    async def test_a_habit_the_owner_corrected_is_not_written_back(self, env):
        night, model = await self.written(env, FakeMcp(errands=saturday_errands()))
        text = env.local(SHOPPING_FICHE).replace(f"- {SHOPPING} —", f"- ~~{SHOPPING}~~ —", 1)
        await env.service.write(USER_A, SHOPPING_FICHE, text, reason="corrigée par le propriétaire")
        model.answers["habits"] = [{"habitudes": [shopping()], "cessees": []}]

        await run(night, NOW + timedelta(days=7))

        assert SHOPPING not in [c.texte for c in body_of(env, SHOPPING_FICHE).known]

    async def test_the_trace_of_a_long_title_fits_its_column_and_a_dash_in_it_does_not_split_the_claim(self, env):
        long_title = "Le propriétaire fait ses courses le samedi matin — et jamais le dimanche, ni en semaine, " * 2
        mcp = FakeMcp(errands=saturday_errands())
        night, _ = await night_with(env, mcp, habits=[{"habitudes": [shopping() | {"titre": long_title}]}])

        await run(night)

        seen = await memory_night_repo.seen(USER_A, "habits")
        assert seen and all(len(card) <= 64 and len(digest) <= 64 for card, digest in seen.items())
        [path] = [p for p in env.service.folder(USER_A).files() if p.startswith("Habitudes/")]
        first = body_of(env, path).known[0]
        assert fiches.is_observed_regularity(first) and " — " not in first.texte

        night.mcp = FakeMcp(errands=saturday_errands())
        await run(night, NOW + timedelta(days=7))
        assert len(body_of(env, path).known) == 5


class TestTheMusicalTastesOfAnAgenda:
    async def test_the_concert_agenda_gives_a_tastes_fiche_citing_the_events_as_proofs(self, env):
        past, upcoming = concerts()
        mcp = FakeMcp(events=past, upcoming=upcoming)
        taste = {
            "titre": STONER,
            "dossiers": ["Goûts"],
            "resume": "Les styles de musique qu'il va voir en concert",
            "evoque": ["concert", "sortie du soir", "idée de cadeau"],
            "preuves": ["A1", "A2", "A3", "A4", "A5", "A6"],
        }
        night, model = await night_with(env, mcp, habits=[{"habitudes": [taste]}])

        await run(night)

        known = body_of(env, f"Goûts/{STONER}.md").known
        assert (known[0].texte, known[0].provenance) == (STONER, "observé")
        assert known[0].source.startswith("app : agenda « Concerts », sur la période du 2026-07-22 au ")
        assert [c.texte.split(" : ")[1].split(" (")[0].split(" @")[0] for c in known[1:]] == list(BANDS)
        assert all(c.provenance == "observé" and c.source.startswith("app : agenda « Concerts », 2026-") for c in known[1:])
        assert "Kadavar" in model.asked("habits")[0]

    async def test_a_recurring_series_is_not_a_habit_to_read(self):
        series = timed_event("2026-10-05", "Sport") | {"recurring": True}

        assert habits.agenda_item(series) is None

    async def test_a_cancelled_event_is_not_read(self):
        assert habits.agenda_item(timed_event("2026-10-05", "Sport") | {"status": "cancelled"}) is None

    async def test_a_timed_event_keeps_its_local_day_and_hour(self):
        day, when, where, label, _ = habits.agenda_item(timed_event("2026-10-06", "Sport", hour="19:30", location="Gym"))

        assert (day, when, where, label) == (date(2026, 10, 6), "19:30", "agenda « Défaut »", "Sport @ Gym")


class TestWhatTheModelCannotPutInAFiche:
    def test_separators_of_the_fiche_format_are_stripped_from_data(self):
        assert habits.clean("Concert — Kadavar · Salle ~~x~~") == "Concert, Kadavar, Salle x"

    def test_a_label_is_one_short_line(self):
        assert habits.clean("a\nb  c" + "x" * 300).count("\n") == 0
        assert len(habits.clean("x" * 300)) == habits.MAX_LABEL

    def test_a_period_source_is_recognised_and_a_proof_source_is_not(self):
        start, end = date(2026, 7, 22), date(2026, 10, 14)
        source = fiches.period_source("courses", start, end)

        assert fiches.is_observed_regularity(fiches.Claim("x", "observé", source))
        assert not fiches.is_observed_regularity(fiches.Claim("x", "observé", "app : courses, 2026-10-03"))
        assert not fiches.is_observed_regularity(fiches.Claim("x", "dit", source))
        assert not fiches.is_observed_regularity(fiches.Claim("x", "observé", "Conversations/2026/10/a.md"))
        assert fiches.announced(fiches.Claim("x", "déduit", "app : x"))


class TestTheShippedScenarios:
    """The fake model of the e2e stack, driven through the same step (the journey's data, without the stack)."""

    SHIPPED = Path(__file__).resolve().parents[2] / "fixtures" / "fake-llm"

    def night(self, env, mcp: FakeMcp):
        night = keeper(env, NightModel())
        night.client = FakeAnthropicClient(fixtures_dir=self.SHIPPED)
        night.mcp = mcp
        return night

    async def test_the_concert_shows_give_the_tastes_fiche_with_each_show_as_a_proof(self, env):
        days = ("2026-07-30", "2026-08-13", "2026-08-27", "2026-09-10", "2026-09-24")
        events = {day: [all_day_event(day, band)] for day, band in zip(days, BANDS[:5], strict=True)}

        await run(self.night(env, FakeMcp(events=events)))

        [path] = [p for p in env.service.folder(USER_A).files() if p.startswith("Goûts/")]
        known = body_of(env, path).known
        assert known[0].provenance == "observé"
        assert known[0].source.startswith("app : agenda « Concerts », sur la période du ")
        assert [c.source.rsplit(", ", 1)[1] for c in known[1:]] == list(days)
        assert fiches.announced(known[0]) and not fiches.announced(known[1])

    async def test_the_chat_prompt_is_never_answered_by_the_night(self):
        library = FakeAnthropicClient(fixtures_dir=self.SHIPPED).library
        personality = (Path(__file__).resolve().parents[2] / "app" / "personality" / "default.yaml").read_text()
        for scenario in library.scenarios:
            if scenario.name.startswith("memory-night-habits"):
                assert not scenario.matches("Qu'ai-je de prévu demain ?", personality)

    async def test_any_other_data_gives_no_habit(self, env):
        events = {day: [timed_event(day, "Dentiste")] for day in ("2026-09-01", "2026-09-02")}

        await run(self.night(env, FakeMcp(events=events)))

        assert [p for p in env.service.folder(USER_A).files() if p.startswith("Goûts/")] == []


async def test_the_agenda_is_read_in_stretches_one_call_at_a_time():
    # One MCP session answers parallel calls with empty or swapped replies: the pass takes one call at a time.
    mcp = FakeMcp(errands=saturday_errands(), upcoming=[timed_event("2026-10-20", "Kadavar")])

    obs = await habits.observe(mcp, USER_A, TODAY, PARIS)

    assert obs.ok == set(habits.MODULES)
    assert mcp.called("get_events_by_date") == 3
    assert not mcp.overlapped


async def test_an_event_of_any_stretch_is_read():
    first, middle, last = "2026-07-22", "2026-08-30", "2026-10-14"
    mcp = FakeMcp(events={day: [timed_event(day, "Kadavar")] for day in (first, middle, last)})

    obs = await habits.observe(mcp, USER_A, TODAY, PARIS)

    assert [i.day.isoformat() for i in obs.items.values() if i.module == habits.AGENDA] == [first, middle, last]
