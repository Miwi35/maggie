from datetime import UTC, date, datetime
from types import SimpleNamespace
from zoneinfo import ZoneInfo

import pytest

from app.memory import carte as carte_mod
from app.memory import conversations, fiches
from app.memory.fiches import FicheError, parse_body, write_fiche
from tests.memory.conftest import USER_A, USER_B

PARIS = ZoneInfo("Europe/Paris")
TODAY = date(2026, 10, 13)
TITLE = "Françoise n'aime pas les roses"
HEURE = "2026-10-04 18h14"


@pytest.fixture(autouse=True)
def _clean_caches():
    conversations._files.clear()
    conversations._locks.clear()
    yield
    conversations._files.clear()
    conversations._locks.clear()


async def conversation(env, user_id=USER_A) -> str:
    question = SimpleNamespace(
        id="m1",
        role="user",
        content="Françoise n'aime pas les roses. Les concerts commencent à 19 heures.",
        created_at=datetime(2026, 10, 4, 16, 14, tzinfo=UTC),
        blocks=None,
        interrupted=False,
    )
    return await conversations.append_messages(user_id, "ctx1", "Cadeau", [question], tz=PARIS, service=env.service)


def claim(texte, source, provenance="dit", heure=HEURE):
    return {"texte": texte, "provenance": provenance, "source": source, "heure": heure}


async def write(env, source, **kwargs):
    arguments = {
        "titre": TITLE,
        "resume": "Ce que Françoise pense des fleurs",
        "dossiers": ["Famille"],
        "affirmations": [claim("Françoise n'aime pas les roses", source)],
    } | kwargs
    return await write_fiche(env.service, USER_A, today=TODAY, **arguments)


class TestANewFiche:
    async def test_the_fiche_is_a_true_sentence_in_a_folder_of_maggie_with_its_claim_and_source(self, env):
        source = await conversation(env)

        written = await write(env, source)

        assert written["chemin"] == f"Famille/{TITLE}.md"
        assert written["cree"] is True
        text = env.local(written["chemin"])
        assert f"- Françoise n'aime pas les roses — dit · {source} · {HEURE}" in text
        parsed = carte_mod.parse(text)
        assert parsed.carte.resume == "Ce que Françoise pense des fleurs"
        assert parsed.carte.sources[0]["provenance"] == "dit"
        assert parsed.carte.id

    async def test_a_claim_of_the_app_is_observed_not_said(self, env):
        written = await write(
            env, "app : agenda", affirmations=[claim("Françoise a un rendez-vous le 12", "app : agenda", "observé", None)]
        )

        assert "observé · app : agenda" in env.local(written["chemin"])

    async def test_a_claim_deduced_is_announced(self, env):
        source = await conversation(env)

        written = await write(env, source, affirmations=[claim("Françoise préfère les pivoines", source, "déduit")])

        assert written["a_annoncer"] == ["Françoise préfère les pivoines"]
        assert fiches.to_announce(env.local(written["chemin"])) == ["Françoise préfère les pivoines"]

    async def test_the_new_fiche_is_found_by_the_next_search(self, env):
        source = await conversation(env)
        await write(env, source)

        found = await env.service.search(USER_A, "roses", 5)

        assert any(f.path == f"Famille/{TITLE}.md" for f in found)


class TestNoSourceNoClaim:
    async def test_a_claim_without_a_source_is_refused(self, env):
        with pytest.raises(FicheError, match="source"):
            await write(env, "", affirmations=[claim("Françoise n'aime pas les roses", "")])

    async def test_a_claim_without_a_provenance_is_refused(self, env):
        source = await conversation(env)
        with pytest.raises(FicheError, match="provenance"):
            await write(env, source, affirmations=[claim("Françoise n'aime pas les roses", source, "")])

    async def test_a_source_that_does_not_exist_is_refused(self, env):
        with pytest.raises(FicheError, match="does not exist"):
            await write(env, "Conversations/2026/10/inventée.md")

    async def test_a_minute_that_is_not_in_the_conversation_is_refused(self, env):
        source = await conversation(env)
        with pytest.raises(FicheError, match="no message at 2026-10-04 19h00"):
            await write(env, source, affirmations=[claim("Françoise n'aime pas les roses", source, heure="2026-10-04 19h00")])

    async def test_a_conversation_source_needs_its_minute(self, env):
        source = await conversation(env)
        with pytest.raises(FicheError, match="heure"):
            await write(env, source, affirmations=[claim("Françoise n'aime pas les roses", source, heure=None)])

    async def test_something_said_cannot_come_from_app_data(self, env):
        with pytest.raises(FicheError, match="dit"):
            await write(env, "app : agenda", affirmations=[claim("Françoise n'aime pas les roses", "app : agenda")])

    async def test_a_path_outside_the_folder_is_refused(self, env):
        for source in ("../../etc/passwd", "/etc/passwd", "%2e%2e/secret.md"):
            with pytest.raises(FicheError):
                await write(env, source, affirmations=[claim("Françoise n'aime pas les roses", source)])

    async def test_the_conversation_of_another_owner_is_not_a_source(self, env):
        other = await conversation(env, USER_B)
        with pytest.raises(FicheError, match="does not exist"):
            await write(env, other)

    async def test_the_legacy_source_is_refused_to_the_model(self, env):
        with pytest.raises(FicheError):
            await write(env, "ancienne mémoire", affirmations=[claim("Françoise n'aime pas les roses", "ancienne mémoire", "déduit", None)])

    async def test_the_legacy_source_is_accepted_for_the_import(self, env):
        source = "ancienne mémoire (sans source), enregistrée le 01/09/2026"
        written = await write_fiche(
            env.service,
            USER_A,
            titre=TITLE,
            resume="x",
            today=TODAY,
            affirmations=[claim("Françoise n'aime pas les roses", source, "déduit", None)],
            allow_legacy=True,
        )

        assert "déduit · ancienne mémoire" in env.local(written["chemin"])


class TestShapeOfTheFiche:
    async def test_too_many_nested_folders_are_refused(self, env):
        source = await conversation(env)
        with pytest.raises(FicheError, match="shallow"):
            await write(env, source, dossiers=["a", "b", "c", "d"])

    async def test_a_resume_is_required_for_a_new_fiche(self, env):
        source = await conversation(env)
        with pytest.raises(FicheError, match="resume"):
            await write(env, source, resume="")

    async def test_the_same_title_cannot_be_created_twice(self, env):
        source = await conversation(env)
        await write(env, source)
        with pytest.raises(FicheError, match="already has this title"):
            await write(env, source)

    async def test_a_name_with_a_separator_stays_inside_the_folder(self, env):
        source = await conversation(env)
        written = await write(env, source, titre="Il aime les roses/les pivoines", dossiers=["../dehors"])
        assert ".." not in written["chemin"]

    async def test_the_same_claim_twice_is_refused(self, env):
        source = await conversation(env)
        c = claim("Françoise n'aime pas les roses", source)
        with pytest.raises(FicheError, match="twice"):
            await write(env, source, affirmations=[c, dict(c)])

    async def test_a_conversation_cannot_be_rewritten_as_a_fiche(self, env):
        source = await conversation(env)
        with pytest.raises(FicheError):
            await write(env, source, chemin=source)


class TestNothingIsErased:
    async def written(self, env):
        source = await conversation(env)
        await write(
            env,
            source,
            affirmations=[
                claim("Françoise n'aime pas les roses", source),
                claim("Françoise adore les tulipes", source),
            ],
        )
        return source, f"Famille/{TITLE}.md"

    async def test_a_claim_dropped_without_a_word_is_refused(self, env):
        source, chemin = await self.written(env)

        with pytest.raises(FicheError, match="nothing is erased"):
            await write_fiche(
                env.service,
                USER_A,
                titre=TITLE,
                chemin=chemin,
                today=TODAY,
                affirmations=[claim("Françoise adore les tulipes", source)],
            )

        assert "n'aime pas les roses" in env.local(chemin)

    async def test_a_replaced_claim_is_dated_struck_and_kept(self, env):
        source, chemin = await self.written(env)

        written = await write_fiche(
            env.service,
            USER_A,
            titre=TITLE,
            chemin=chemin,
            today=TODAY,
            affirmations=[claim("Françoise adore les tulipes", source), claim("Françoise aime les roses blanches", source)],
            remplacees=[{"texte": "Françoise n'aime pas les roses", "motif": "elle a changé d'avis"}],
        )

        assert written["cree"] is False and written["remplacees"] == 1
        body = parse_body(carte_mod.parse(env.local(chemin)).body)
        assert [c.texte for c in body.known] == ["Françoise adore les tulipes", "Françoise aime les roses blanches"]
        assert body.replaced[0].texte == "Françoise n'aime pas les roses"
        assert body.replaced[0].replaced_on == "2026-10-13"
        assert body.replaced[0].motif == "elle a changé d'avis"

    async def test_what_was_replaced_stays_through_the_next_rewrite(self, env):
        source, chemin = await self.written(env)
        both = [claim("Françoise adore les tulipes", source)]
        await write_fiche(
            env.service, USER_A, titre=TITLE, chemin=chemin, today=TODAY, affirmations=both,
            remplacees=[{"texte": "Françoise n'aime pas les roses"}],
        )

        await write_fiche(env.service, USER_A, titre=TITLE, chemin=chemin, today=TODAY, affirmations=both)

        assert "~~Françoise n'aime pas les roses~~" in env.local(chemin)

    async def test_only_what_is_written_can_be_marked_replaced(self, env):
        source, chemin = await self.written(env)
        with pytest.raises(FicheError, match="not in the fiche"):
            await write_fiche(
                env.service, USER_A, titre=TITLE, chemin=chemin, today=TODAY,
                affirmations=[claim("Françoise adore les tulipes", source)],
                remplacees=[{"texte": "Autre chose"}],
            )

    async def test_the_id_of_the_fiche_survives_a_rewrite(self, env):
        source, chemin = await self.written(env)
        before = carte_mod.parse(env.local(chemin)).carte.id

        await write_fiche(
            env.service, USER_A, titre=TITLE, chemin=chemin, today=TODAY,
            affirmations=[claim("Françoise adore les tulipes", source), claim("Françoise n'aime pas les roses", source)],
        )

        assert carte_mod.parse(env.local(chemin)).carte.id == before

    async def test_the_lines_the_owner_wrote_by_hand_are_kept(self, env):
        source, chemin = await self.written(env)
        text = env.local(chemin)
        await env.service.write(USER_A, chemin, text + "\nElle m'a dit ça au téléphone.\n")

        await write_fiche(
            env.service, USER_A, titre=TITLE, chemin=chemin, today=TODAY,
            affirmations=[claim("Françoise adore les tulipes", source), claim("Françoise n'aime pas les roses", source)],
        )

        assert "Elle m'a dit ça au téléphone." in env.local(chemin)

    async def test_a_fiche_that_does_not_exist_cannot_be_rewritten(self, env):
        source = await conversation(env)
        with pytest.raises(FicheError, match="does not exist"):
            await write_fiche(
                env.service, USER_A, titre=TITLE, chemin="Famille/Inconnue.md", today=TODAY,
                affirmations=[claim("Françoise adore les tulipes", source)],
            )


class TestTwoOwners:
    async def test_a_fiche_of_one_owner_is_not_in_the_folder_of_the_other(self, env):
        source = await conversation(env)
        await write(env, source)

        assert env.local(f"Famille/{TITLE}.md", USER_B) is None
