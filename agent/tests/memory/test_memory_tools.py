import json
from unittest.mock import AsyncMock, patch
from zoneinfo import ZoneInfo

import pytest

from app.memory import conversations, tools
from tests.memory.conftest import USER_A, USER_B
from tests.memory.test_fiches import HEURE, TITLE, claim, conversation


@pytest.fixture(autouse=True)
def _wired(env):
    conversations._files.clear()
    conversations._locks.clear()
    with (
        patch.object(tools, "memory_service", env.service),
        patch.object(tools, "resolve_user_timezone", AsyncMock(return_value=ZoneInfo("Europe/Paris"))),
    ):
        yield
    conversations._files.clear()
    conversations._locks.clear()


async def save(env, source) -> dict:
    return json.loads(
        await tools.handle_write_fiche(
            {
                "titre": TITLE,
                "dossiers": ["Famille"],
                "resume": "Ce que Françoise pense des fleurs",
                "affirmations": [claim("Françoise n'aime pas les roses", source)],
            },
            USER_A,
        )
    )


class TestToolList:
    def test_there_is_no_tool_to_delete_or_to_write_a_conversation(self):
        assert sorted(tools.MEMORY_TOOL_NAMES) == ["memory_open", "memory_search", "memory_write_fiche"]


class TestWriteFiche:
    async def test_the_fiche_is_written_and_found_on_the_next_turn(self, env):
        source = await conversation(env)

        saved = await save(env, source)
        found = json.loads(await tools.handle_search({"requete": "roses"}, USER_A))

        assert saved["enregistre"] is True and saved["chemin"] == f"Famille/{TITLE}.md"
        assert saved["chemin"] in [hit["chemin"] for hit in found["resultats"]]

    async def test_a_refusal_says_what_to_change_and_that_nothing_was_saved(self, env):
        answer = json.loads(
            await tools.handle_write_fiche(
                {"titre": TITLE, "resume": "x", "affirmations": [claim("Françoise n'aime pas les roses", "Conversations/inventée.md")]},
                USER_A,
            )
        )

        assert answer["enregistre"] is False and "does not exist" in answer["error"]
        assert not [f for f in env.service.folder(USER_A).files() if f.startswith("Famille")]

    async def test_a_path_that_is_not_writable_is_an_error_not_an_exception(self, env):
        source = await conversation(env)
        answer = json.loads(
            await tools.handle_write_fiche(
                {"titre": TITLE, "chemin": "../x.md", "affirmations": [claim("a", source)]}, USER_A
            )
        )
        assert answer["enregistre"] is False


class TestOpen:
    async def test_a_fiche_comes_back_whole_with_its_deduced_claims(self, env):
        source = await conversation(env)
        await tools.handle_write_fiche(
            {
                "titre": TITLE,
                "resume": "x",
                "affirmations": [claim("Françoise préfère les pivoines", source, "déduit")],
            },
            USER_A,
        )

        opened = json.loads(await tools.handle_open({"chemin": f"{TITLE}.md"}, USER_A))

        assert "Françoise préfère les pivoines" in opened["contenu"]
        assert opened["a_annoncer"] == ["Françoise préfère les pivoines"]

    async def test_a_conversation_is_opened_whole_to_check_a_source(self, env):
        source = await conversation(env)

        opened = json.loads(await tools.handle_open({"chemin": source}, USER_A))

        assert f"## {HEURE} — Propriétaire" in opened["contenu"]
        assert "a_annoncer" not in opened

    async def test_a_passage_can_be_asked_for(self, env):
        source = await conversation(env)

        opened = json.loads(await tools.handle_open({"chemin": source, "debut": 1, "fin": 2}, USER_A))

        assert opened["passage"] == "1-2"
        assert len(opened["contenu"].splitlines()) == 2

    async def test_a_missing_file_says_to_search_first(self, env):
        opened = json.loads(await tools.handle_open({"chemin": "Nulle part.md"}, USER_A))
        assert "search first" in opened["error"]

    @pytest.mark.parametrize("chemin", ["../secret.md", "/etc/passwd", "%2e%2e/secret.md", "a/../../b.md", ""])
    async def test_a_path_outside_the_folder_is_refused(self, env, chemin):
        opened = json.loads(await tools.handle_open({"chemin": chemin}, USER_A))
        assert "error" in opened and "contenu" not in opened

    async def test_the_folder_of_another_owner_is_out_of_reach(self, env):
        source = await conversation(env, USER_B)

        opened = json.loads(await tools.handle_open({"chemin": source}, USER_A))

        assert "error" in opened


class TestSearch:
    async def test_a_result_is_a_path_a_summary_and_an_excerpt_with_a_reminder_to_open(self, env):
        source = await conversation(env)
        await save(env, source)

        found = json.loads(await tools.handle_search({"requete": "roses"}, USER_A))

        hit = next(h for h in found["resultats"] if h["genre"] == "fiche")
        assert hit["resume"] == "Ce que Françoise pense des fleurs"
        assert "roses" in hit["extrait"]
        assert "memory_open" in found["rappel"]

    async def test_the_conversations_are_searched_too_so_a_habit_said_in_passing_is_found(self, env):
        source = await conversation(env)

        found = json.loads(await tools.handle_search({"requete": "concerts"}, USER_A))

        assert source in [h["chemin"] for h in found["resultats"]]

    async def test_the_search_stays_in_the_folder_of_the_owner(self, env):
        await conversation(env, USER_B)

        found = json.loads(await tools.handle_search({"requete": "roses"}, USER_A))

        assert found["resultats"] == []

    async def test_an_empty_query_is_an_error(self, env):
        assert "error" in json.loads(await tools.handle_search({"requete": " "}, USER_A))


class TestUnavailable:
    @pytest.mark.parametrize("handler", ["handle_search", "handle_open", "handle_write_fiche"])
    async def test_without_the_folder_the_tools_say_so_instead_of_pretending(self, env, handler):
        env.service.bucket = None

        answer = json.loads(await getattr(tools, handler)({"requete": "x", "chemin": "x.md", "titre": "x", "affirmations": []}, USER_A))

        assert "not available" in answer["error"]
