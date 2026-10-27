"""Missed recalls (MAG-228): « tu aurais dû t'en souvenir » recorded as a case, replayed against the index."""

import json
from datetime import date
from unittest.mock import AsyncMock, patch
from zoneinfo import ZoneInfo

import pytest

from app.memory import misses, paths, recall, tools
from app.memory.carte import UnreadableFile
from tests.memory.conftest import USER_A, USER_B, fiche

TODAY = date(2026, 10, 14)
ROSES = "Famille/Françoise n'aime pas les roses.md"
DEMANDE = "Un bouquet pour la fête des mères"
CASE = "Rappels manqués/2026-10-14 — Un bouquet pour la fête des mères.md"


@pytest.fixture(autouse=True)
def wired(env):
    with (
        patch.object(tools, "memory_service", env.service),
        patch.object(tools, "resolve_user_timezone", AsyncMock(return_value=ZoneInfo("Europe/Paris"))),
    ):
        yield


async def roses(env, evoque: list[str] | None = None, *, extra: str = "") -> None:
    text = fiche("Françoise n'aime pas les roses.", resume="Françoise n'aime pas les roses", evoque=evoque)
    await env.service.write(USER_A, ROSES, text.replace("---\n\n", f"{extra}---\n\n", 1) if extra else text)


async def record(env, **kw):
    kw = {"demande": DEMANDE, "souvenir": "", "chemin": ROSES} | kw
    return await misses.record(env.service, USER_A, today=TODAY, **kw)


class TestTheFileOfACase:
    def test_a_case_is_read_back_as_it_was_written(self):
        miss = misses.Miss(
            path=CASE,
            demande=DEMANDE,
            attendue=ROSES,
            attendue_id="01J9Z3K5Q8W2X4Y6A7B8C9D0E9",
            ressorti=["Cuisine/Gratin.md"],
            statut=misses.GREEN,
            enregistre="2026-10-14",
            vert_depuis="2026-10-15",
            cause=misses.POOR_EVOQUE,
        )
        miss.log("2026-10-14", "enregistré")

        assert misses.parse(CASE, misses.render(miss)) == miss

    def test_the_file_says_what_was_asked_what_should_come_back_and_how_to_remove_it(self):
        text = misses.render(misses.Miss(path=CASE, demande=DEMANDE, attendue=ROSES, statut=misses.RED))

        assert f"**Tu demandais** : {DEMANDE}" in text
        assert f"**Aurait dû ressortir** : `{ROSES}`" in text
        assert "**Est ressorti** : rien" in text
        assert "supprime ce fichier" in text

    def test_a_file_without_frontmatter_or_without_demand_is_unreadable(self):
        with pytest.raises(UnreadableFile):
            misses.parse(CASE, "Juste du texte.\n")
        with pytest.raises(UnreadableFile):
            misses.parse(CASE, "---\nstatut: vert\n---\n\nTexte.\n")

    def test_an_unknown_state_is_an_open_case(self):
        assert misses.parse(CASE, "---\nstatut: bizarre\ndemande: x y\n---\n").statut == misses.OPEN

    def test_the_path_names_the_day_and_the_demand_and_never_collides(self):
        first = paths.miss_relative("2026-10-14", DEMANDE, [])

        assert first == CASE
        assert paths.miss_relative("2026-10-14", DEMANDE, [first]) == CASE.replace(".md", "-2.md")


class TestWhereTheCasesLive:
    async def test_a_case_is_never_indexed_so_the_recall_cannot_surface_it(self, env):
        await roses(env)
        await record(env)

        assert env.local(CASE) is not None
        assert await env.service.indexed(USER_A, CASE) is None
        assert not paths.is_indexable(CASE)
        assert CASE not in [c.path for c in await env.service.cards(USER_A)]
        assert paths.is_syncable(CASE)

    async def test_a_case_travels_to_the_bucket_like_any_file(self, env):
        await roses(env)
        await record(env)

        await env.service.sync_user(USER_A)

        assert "Tu demandais" in (env.remote(CASE) or "")

    async def test_nothing_but_the_recording_may_write_there(self, env):
        with pytest.raises(paths.UnsafePath):
            await env.service.write(USER_A, "Rappels manqués/à la main.md", fiche("Texte."))
        with pytest.raises(paths.UnsafePath):
            await env.service.write(USER_A, "Rappels manqués.md", fiche("Texte."), miss=True)

    async def test_the_owner_removes_a_case_by_deleting_its_file(self, env):
        await roses(env)
        await record(env)
        assert [m.path for m in await misses.cases(env.service, USER_A)] == [CASE]

        assert await env.service.delete(USER_A, CASE) is True

        assert await misses.cases(env.service, USER_A) == []

    async def test_a_case_unreadable_is_left_alone_and_the_others_are_kept(self, env):
        await roses(env)
        await record(env)
        env.service.folder(USER_A).write("Rappels manqués/cassé.md", "pas de frontmatter\n")

        assert [m.path for m in await misses.cases(env.service, USER_A)] == [CASE]

    async def test_the_cases_of_a_user_are_not_the_cases_of_another(self, env):
        await roses(env)
        await record(env)

        assert await misses.cases(env.service, USER_B) == []


class TestRecording:
    async def test_a_miss_records_what_was_asked_the_fiche_expected_and_what_came_back(self, env):
        await roses(env)
        await env.service.write(USER_A, "Cuisine/Gratin.md", fiche("Gratin.", resume="gratin", evoque=["bouquet"]))

        answer = await record(env)

        assert answer == {"cas": CASE, "nouveau": True, "ressortait_deja": False}
        miss = (await misses.cases(env.service, USER_A))[0]
        assert miss.demande == DEMANDE and miss.attendue == ROSES and miss.statut == misses.OPEN
        indexed = await env.service.indexed(USER_A, ROSES)
        assert miss.attendue_id == indexed.card_id
        assert miss.ressorti == ["Cuisine/Gratin.md"]
        assert miss.enregistre == "2026-10-14" and "enregistré" in miss.historique[0]

    async def test_recording_is_in_the_journal(self, env):
        await roses(env)

        await record(env)

        assert f"`{CASE}`" in (env.local("journal.md") or "")

    async def test_a_fiche_that_already_comes_back_is_told_so(self, env):
        await roses(env, ["fête des mères"])

        assert (await record(env))["ressortait_deja"] is True

    async def test_the_same_miss_said_twice_is_one_case_back_to_open(self, env):
        await roses(env)
        await record(env)
        case = (await misses.cases(env.service, USER_A))[0]
        case.statut, case.vert_depuis = misses.GREEN, "2026-10-15"
        await misses.save(env.service, USER_A, case)

        again = await record(env, demande=DEMANDE.upper())

        assert again["nouveau"] is False and again["cas"] == CASE
        cases = await misses.cases(env.service, USER_A)
        assert len(cases) == 1
        assert cases[0].statut == misses.OPEN and cases[0].vert_depuis == ""
        assert "signalé de nouveau" in cases[0].historique[-1]

    async def test_the_same_demand_for_another_fiche_is_another_case(self, env):
        await roses(env)
        await env.service.write(USER_A, "Famille/Françoise.md", fiche("Françoise.", resume="Françoise"))

        await record(env)
        other = await record(env, chemin="Famille/Françoise.md")

        assert other["nouveau"] is True and other["cas"] != CASE
        assert len(await misses.cases(env.service, USER_A)) == 2

    async def test_a_fiche_that_did_not_exist_is_a_case_with_what_the_owner_expected(self, env):
        answer = await record(env, chemin="", souvenir="Françoise n'aime pas les roses")

        assert answer["nouveau"] is True and answer["ressortait_deja"] is False
        miss = (await misses.cases(env.service, USER_A))[0]
        assert miss.attendue == "" and miss.attendue_id == "" and miss.souvenir == "Françoise n'aime pas les roses"
        assert "Françoise n'aime pas les roses" in env.local(miss.path)

    async def test_a_demand_with_no_word_to_recall_by_is_refused(self, env):
        await roses(env)

        with pytest.raises(misses.MissError, match="demande"):
            await record(env, demande="le la les")

    async def test_a_fiche_that_does_not_exist_at_that_path_is_refused(self, env):
        with pytest.raises(misses.MissError, match="No fiche"):
            await record(env, chemin="Famille/Inconnue.md")

    async def test_a_conversation_is_not_a_fiche_to_expect(self, env):
        env.service.folder(USER_A)
        await env.service.write(
            USER_A, "Conversations/2026/10/2026-10-01 10h00 — Fil.md", "---\ncontexte: abc\n---\n\nTexte.\n",
            conversation=True,
        )

        with pytest.raises(misses.MissError, match="No fiche"):
            await record(env, chemin="Conversations/2026/10/2026-10-01 10h00 — Fil.md")

    async def test_neither_a_fiche_nor_a_description_is_refused(self, env):
        with pytest.raises(misses.MissError, match="chemin"):
            await record(env, chemin="", souvenir="")

    async def test_a_path_out_of_the_folder_is_refused(self, env):
        with pytest.raises(misses.MissError, match="not a path"):
            await record(env, chemin="../../etc/passwd")


class TestTheTool:
    async def call(self, **arguments) -> dict:
        return json.loads(await tools.handle_log_miss(arguments, USER_A))

    def test_the_tool_is_one_of_the_memory_tools_and_asks_for_the_demand(self):
        spec = next(t for t in tools.MEMORY_TOOLS if t["name"] == "memory_log_miss")

        assert "memory_log_miss" in tools.NATIVE_HANDLERS
        assert spec["input_schema"]["required"] == ["demande"]

    async def test_the_miss_is_recorded_and_the_answer_says_so(self, env):
        await roses(env)

        answer = await self.call(demande=DEMANDE, chemin=ROSES)

        assert answer["enregistre"] is True and answer["nouveau"] is True and answer["ressortait_deja"] is False
        assert answer["cas"].startswith("Rappels manqués/") and answer["cas"].endswith(f"— {DEMANDE}.md")
        assert env.local(answer["cas"]) is not None

    async def test_a_refusal_says_what_to_correct_and_that_nothing_was_recorded(self, env):
        answer = await self.call(demande=DEMANDE, chemin="Famille/Inconnue.md")

        assert answer["enregistre"] is False and "search first" in answer["error"]
        assert await misses.cases(env.service, USER_A) == []

    async def test_the_tool_cannot_be_used_to_write_in_the_reserved_folder(self, env):
        answer = await self.call(demande=DEMANDE, chemin="Rappels manqués/x.md")

        assert answer["enregistre"] is False

    async def test_the_cases_are_not_opened_as_fiches(self, env):
        await roses(env)
        case = (await self.call(demande=DEMANDE, chemin=ROSES))["cas"]

        opened = json.loads(await tools.handle_open({"chemin": case}, USER_A))

        assert "a_annoncer" not in opened


class TestTheReplay:
    async def cards(self, env):
        return await env.service.cards(USER_A)

    async def miss(self, env) -> misses.Miss:
        await record(env)
        return (await misses.cases(env.service, USER_A))[0]

    async def test_a_fiche_that_comes_back_is_a_green_replay(self, env):
        await roses(env, ["fête des mères"])
        miss = await self.miss(env)

        result = misses.replay(await self.cards(env), miss, TODAY)

        assert result.ok is True and result.ressorti == [ROSES]
        assert result.card is not None and result.card.path == ROSES

    async def test_a_fiche_that_does_not_come_back_is_a_red_replay_that_shows_what_came_instead(self, env):
        await roses(env)
        await env.service.write(USER_A, "Cuisine/Gratin.md", fiche("Gratin.", evoque=["fête des mères"]))
        miss = await self.miss(env)

        result = misses.replay(await self.cards(env), miss, TODAY)

        assert result.ok is False and result.ressorti == ["Cuisine/Gratin.md"]

    async def test_the_fiche_is_followed_by_its_id_when_it_moves(self, env):
        await roses(env, ["fête des mères"])
        miss = await self.miss(env)
        await env.service.move(USER_A, ROSES, "Cadeaux/Les roses de Françoise.md")

        result = misses.replay(await self.cards(env), miss, TODAY)

        assert result.ok is True
        assert result.card is not None and result.card.path == "Cadeaux/Les roses de Françoise.md"

    async def test_a_fiche_deleted_is_a_red_replay_without_a_card(self, env):
        await roses(env, ["fête des mères"])
        miss = await self.miss(env)
        await env.service.delete(USER_A, ROSES)

        result = misses.replay(await self.cards(env), miss, TODAY)

        assert result.ok is False and result.card is None


class TestTheDiagnosis:
    async def diagnose(self, env, *, on_disk: bool = False):
        miss = (await misses.cases(env.service, USER_A))[0]
        return misses.diagnose(await env.service.cards(USER_A), miss, TODAY, on_disk=on_disk)

    async def test_a_fiche_that_never_existed(self, env):
        await record(env, chemin="", souvenir="Françoise n'aime pas les roses")

        assert await self.diagnose(env) == (misses.NOT_A_FICHE, None)

    async def test_a_fiche_on_disk_that_the_index_lacks(self, env):
        await record(env, chemin="", souvenir="Françoise n'aime pas les roses")
        miss = (await misses.cases(env.service, USER_A))[0]
        miss.attendue = ROSES
        await misses.save(env.service, USER_A, miss)

        assert await self.diagnose(env, on_disk=True) == (misses.NOT_INDEXED, None)

    async def test_a_fiche_set_aside(self, env):
        await roses(env, ["fête des mères"], extra="archivee: 2026-10-01\n")
        await record(env)

        cause, card = await self.diagnose(env)

        assert cause == misses.SET_ASIDE
        assert card is not None and card.path == ROSES

    async def test_a_carte_that_evokes_nothing_of_the_demand(self, env):
        await roses(env)
        await record(env)

        assert (await self.diagnose(env))[0] == misses.POOR_EVOQUE

    async def test_a_carte_that_touches_the_demand_without_reaching_the_threshold(self, env):
        await roses(env)
        await record(env, demande="Un bouquet de roses")

        assert (await self.diagnose(env))[0] == misses.THRESHOLD

    async def test_a_carte_that_other_cartes_outrank(self, env):
        await roses(env, ["fête des mères"])
        for name in ("Un", "Deux", "Trois"):
            await env.service.write(
                USER_A, f"Cadeaux/{name}.md", fiche(name, evoque=["fête des mères", "bouquet"])
            )
        await record(env)

        assert (await self.diagnose(env))[0] == misses.CROWDED_OUT

    async def test_a_fiche_that_comes_back_has_nothing_to_diagnose(self, env):
        await roses(env, ["fête des mères"])
        await record(env)

        assert (await self.diagnose(env))[0] == misses.FINE

    async def test_the_threshold_is_never_the_thing_that_changes(self):
        from app.config import settings

        assert settings.memory_recall_threshold == 2.5 and recall.W_EVOQUE >= settings.memory_recall_threshold


class TestTheFichesAtRisk:
    async def cards(self, env):
        return await env.service.cards(USER_A)

    async def test_the_fiches_with_the_same_people_are_close(self, env):
        await env.service.write(USER_A, ROSES, fiche("Roses.", resume="Roses").replace("---\n\n", "personnes: [Françoise]\n---\n\n", 1))
        await env.service.write(
            USER_A, "Famille/Son anniversaire.md",
            fiche("Anniversaire.", resume="Anniversaire").replace("---\n\n", "personnes: [Françoise]\n---\n\n", 1),
        )
        await env.service.write(USER_A, "Cuisine/Gratin.md", fiche("Gratin.", resume="Gratin"))
        cards = await self.cards(env)
        card = next(c for c in cards if c.path == ROSES)

        assert [c.path for c in misses.neighbours(cards, card)] == ["Famille/Son anniversaire.md"]

    async def test_two_shared_words_of_the_title_make_a_theme(self, env):
        await env.service.write(USER_A, "Famille/Françoise aime les roses rouges.md", fiche("A.", resume="A"))
        await env.service.write(USER_A, "Maison/Les roses rouges du jardin.md", fiche("B.", resume="B"))
        await env.service.write(USER_A, "Maison/Les roses du jardin.md", fiche("C.", resume="C"))
        cards = await self.cards(env)
        card = next(c for c in cards if c.path == "Famille/Françoise aime les roses rouges.md")

        assert [c.path for c in misses.neighbours(cards, card)] == ["Maison/Les roses rouges du jardin.md"]

    async def test_a_fiche_already_rich_in_associations_is_not_at_risk(self, env):
        await env.service.write(USER_A, ROSES, fiche("Roses.", resume="Roses").replace("---\n\n", "personnes: [Françoise]\n---\n\n", 1))
        await env.service.write(
            USER_A, "Famille/Son anniversaire.md",
            fiche("A.", resume="A", evoque=["un", "deux", "trois"]).replace("---\n\n", "personnes: [Françoise]\n---\n\n", 1),
        )
        cards = await self.cards(env)

        assert misses.neighbours(cards, next(c for c in cards if c.path == ROSES)) == []

    def test_the_fallback_term_is_the_heart_of_the_demand(self):
        assert misses.fallback_term(DEMANDE) == "bouquet fête mères"
        assert misses.fallback_term("le la les") == ""


class TestFollowingAMerge:
    async def test_the_cases_that_expected_an_absorbed_fiche_expect_the_one_that_took_it_in(self, env):
        await roses(env)
        await record(env)

        await misses.repoint(env.service, USER_A, (await misses.cases(env.service, USER_A))[0].attendue_id, "NEW")

        assert (await misses.cases(env.service, USER_A))[0].attendue_id == "NEW"
