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


MESSAGE = "Françoise n'aime pas les roses. Les concerts commencent à 19 heures."


async def conversation(env, user_id=USER_A) -> str:
    question = SimpleNamespace(
        id="m1",
        role="user",
        content=MESSAGE,
        created_at=datetime(2026, 10, 4, 16, 14, tzinfo=UTC),
        blocks=None,
        interrupted=False,
    )
    return await conversations.append_messages(user_id, "ctx1", "Cadeau", [question], tz=PARIS, service=env.service)


def claim(texte, source, provenance="dit", heure=HEURE, citation=MESSAGE):
    found = {"texte": texte, "provenance": provenance, "source": source, "heure": heure}
    return found | {"citation": citation} if provenance == "dit" else found


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

    async def test_something_said_cannot_cite_a_message_maggie_wrote(self, env):
        source = await conversation(env)
        answer = SimpleNamespace(
            id="m2",
            role="assistant",
            content="Les concerts ont lieu à l'UBU, à Nantes.",
            created_at=datetime(2026, 10, 4, 16, 20, tzinfo=UTC),
            blocks=None,
            interrupted=False,
        )
        await conversations.append_messages(USER_A, "ctx1", "Cadeau", [answer], tz=PARIS, service=env.service)

        with pytest.raises(FicheError, match="not the owner's"):
            await write(
                env, source, affirmations=[claim("La salle est à Nantes", source, "dit", "2026-10-04 18h20")]
            )
        written = await write(
            env, source, affirmations=[claim("La salle est à Nantes", source, "déduit", "2026-10-04 18h20")]
        )
        assert written["a_annoncer"] == ["La salle est à Nantes"]

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


async def conversation_with_maggie(env) -> str:
    owner = SimpleNamespace(
        id="m1",
        role="user",
        content="Françoise n'aime pas les roses.",
        created_at=datetime(2026, 10, 4, 16, 14, tzinfo=UTC),
        blocks=None,
        interrupted=False,
    )
    maggie = SimpleNamespace(
        id="m2",
        role="assistant",
        content="Les pivoines plaisent à Françoise.",
        created_at=datetime(2026, 10, 4, 16, 14, 40, tzinfo=UTC),
        blocks=None,
        interrupted=False,
    )
    return await conversations.append_messages(USER_A, "ctx2", "Cadeau", [owner, maggie], tz=PARIS, service=env.service)


class TestTheQuoteIsChecked:
    async def test_a_said_claim_without_a_citation_is_refused(self, env):
        source = await conversation(env)

        with pytest.raises(FicheError, match="citation"):
            await write(env, source, affirmations=[claim("Françoise n'aime pas les roses", source, citation="")])

        assert not [f for f in env.service.folder(USER_A).files() if f.startswith("Famille")]

    async def test_a_citation_absent_from_the_message_is_refused(self, env):
        source = await conversation(env)

        with pytest.raises(FicheError, match="is not in the owner's message"):
            await write(env, source, affirmations=[claim("Françoise adore les tulipes", source, citation="Elle adore les tulipes")])

        assert not [f for f in env.service.folder(USER_A).files() if f.startswith("Famille")]

    async def test_a_citation_only_in_maggies_message_is_refused(self, env):
        source = await conversation_with_maggie(env)

        with pytest.raises(FicheError, match="is not in the owner's message"):
            await write(
                env,
                source,
                affirmations=[claim("Françoise préfère les pivoines", source, citation="Les pivoines plaisent à Françoise")],
            )

    async def test_the_citation_matches_without_accents_case_or_typographic_apostrophes(self, env):
        source = await conversation(env)

        await write(env, source, affirmations=[claim("Françoise n'aime pas les roses", source, citation="FRANCOISE n’aime pas LES roses")])

        assert fiches.quoted(env.local(source), HEURE, "francoise n'aime pas les roses")

    async def test_a_claim_kept_from_the_fiche_is_not_asked_its_citation_again(self, env):
        source = await conversation(env)
        await write(env, source)
        kept = claim("Françoise n'aime pas les roses", source, citation="")

        written = await write(
            env, source, chemin=f"Famille/{TITLE}.md", affirmations=[kept, claim("Les concerts commencent à 19 heures", source)]
        )

        assert written["affirmations"] == 2

    async def test_an_observed_or_deduced_claim_needs_no_citation(self, env):
        source = await conversation(env)

        written = await write(env, source, affirmations=[claim("Françoise préfère les pivoines", source, "déduit")])

        assert written["cree"] is True

    async def test_the_citation_is_not_written_in_the_fiche(self, env):
        source = await conversation(env)

        written = await write(env, source, affirmations=[claim("Elle déteste les roses", source, citation="Françoise n'aime pas")])

        text = env.local(written["chemin"])
        assert f"- Elle déteste les roses — dit · {source} · {HEURE}" in text
        assert "citation" not in text and "Françoise n'aime pas —" not in text


class TestNoSecretInAFiche:
    async def refused(self, env, **kwargs):
        source = await conversation(env)
        with pytest.raises(FicheError, match="secret") as error:
            await write(env, source, **kwargs)
        assert not [f for f in env.service.folder(USER_A).files() if f.startswith("Famille")]
        return str(error.value)

    async def test_a_card_number_valid_for_luhn_is_refused(self, env):
        source = await conversation(env)
        await self.refused(env, affirmations=[claim("Sa carte est 4111 1111 1111 1111", source)])

    async def test_a_number_that_fails_luhn_is_kept(self, env):
        source = await conversation(env)

        written = await write(env, source, affirmations=[claim("Le code est 4111 1111 1111 1112", source)])

        assert written["cree"] is True

    async def test_an_api_key_or_a_jwt_is_refused(self, env):
        source = await conversation(env)
        key = "sk-" + "a1B2c3D4e5F6g7H8i9J0k1L2"
        jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dozjgNryP4J3jVmNHl0w5N_XgL0n3I9PlFUP0THsR8U"
        await self.refused(env, affirmations=[claim(f"Sa clé est {key}", source)])
        await self.refused(env, affirmations=[claim(f"Son jeton est {jwt}", source)])

    async def test_a_login_link_or_a_link_with_a_token_is_refused(self, env):
        source = await conversation(env)
        await self.refused(env, affirmations=[claim("Il se connecte sur https://jean:motdepasse@exemple.fr", source)])
        await self.refused(env, affirmations=[claim("Lien https://exemple.fr/accès?access_token=abc123def", source)])

    async def test_a_secret_in_the_citation_the_title_or_the_resume_is_refused(self, env):
        source = await conversation(env)
        card = "4111 1111 1111 1111"
        await self.refused(env, affirmations=[claim("Françoise n'aime pas les roses", source, citation=card)])
        await self.refused(env, titre=f"Carte {card}")
        await self.refused(env, resume=f"Carte {card}")
        await self.refused(env, evoque=[card])

    async def test_the_refusal_never_repeats_the_secret(self, env):
        source = await conversation(env)

        message = await self.refused(env, affirmations=[claim("Sa carte est 4111 1111 1111 1111", source)])

        assert "4111" not in message


class TestAnOldClaimIsNotRefusedForASecret:
    async def test_a_claim_already_in_the_fiche_is_kept_even_if_it_looks_like_a_secret(self, env):
        source = await conversation(env)
        await write(env, source)
        path = f"Famille/{TITLE}.md"
        text = env.local(path)
        old = f"- Son code est 4111 1111 1111 1111 — dit · {source} · {HEURE}"
        await env.service.write(USER_A, path, text.replace("## Ce qui n'est plus vrai", old + "\n\n## Ce qui n'est plus vrai") if "## Ce qui n'est plus vrai" in text else text.rstrip("\n") + "\n" + old + "\n", reason="legacy")
        held = claim("Son code est 4111 1111 1111 1111", source, citation="")

        written = await write(env, source, chemin=path, affirmations=[claim("Françoise n'aime pas les roses", source), held])

        assert written["affirmations"] == 2
