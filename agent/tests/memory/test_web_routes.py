"""The memory folder on the web (MAG-20): routes of `/memory`, always the signed-in user's own folder."""

import asyncio
import io
import zipfile
from datetime import UTC, date, datetime
from types import SimpleNamespace
from unittest.mock import patch
from zoneinfo import ZoneInfo

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

from app.api.memory_routes import router
from app.auth import get_current_user_id
from app.memory import carte as carte_mod
from app.memory import conversations, fiches, misses, recall
from app.memory.fiches import write_fiche
from app.memory.service import memory_service
from tests.memory.conftest import USER_A, USER_B, fiche

PARIS = ZoneInfo("Europe/Paris")
ROSES = "Famille/Françoise n'aime pas les roses.md"
HEURE = "2026-10-04 18h14"
URL = "/memory/file"

ROUTES = [
    ("get", "/memory/tree"),
    ("get", f"{URL}?path=x.md"),
    ("put", f"{URL}?path=x.md"),
    ("post", "/memory/file/move?path=x.md"),
    ("delete", f"{URL}?path=x.md"),
    ("get", "/memory/journal"),
    ("get", "/memory/sync"),
    ("post", "/memory/sync"),
    ("post", "/memory/rebuild"),
    ("get", "/memory/misses"),
    ("get", "/memory/export"),
]


@pytest.fixture(autouse=True)
def _clean_caches():
    conversations._files.clear()
    conversations._locks.clear()
    yield
    conversations._files.clear()
    conversations._locks.clear()


def build_app(env, user_id: str | None) -> FastAPI:
    app = FastAPI()
    app.include_router(router)
    if user_id is not None:
        app.dependency_overrides[get_current_user_id] = lambda: user_id
    return app


@pytest.fixture()
def owner(env):
    with patch("app.api.memory_routes.memory_service", env.service), TestClient(build_app(env, USER_A)) as c:
        yield c


@pytest.fixture()
def other(env):
    with patch("app.api.memory_routes.memory_service", env.service), TestClient(build_app(env, USER_B)) as c:
        yield c


async def conversation(env, user_id: str = USER_A) -> str:
    question = SimpleNamespace(
        id="m1",
        role="user",
        content="Françoise n'aime pas les roses. Les concerts commencent à 19 heures.",
        created_at=datetime(2026, 10, 4, 16, 14, tzinfo=UTC),
        blocks=None,
        interrupted=False,
    )
    return await conversations.append_messages(user_id, "ctx1", "Cadeau", [question], tz=PARIS, service=env.service)


async def seed_roses(env, user_id: str = USER_A) -> str:
    source = await conversation(env, user_id)
    await write_fiche(
        env.service,
        user_id,
        today=date(2026, 10, 13),
        titre="Françoise n'aime pas les roses",
        resume="Ce que Françoise pense des fleurs",
        dossiers=["Famille"],
        evoque=["bouquet", "fleurs"],
        affirmations=[{"texte": "Françoise n'aime pas les roses", "provenance": "dit", "source": source, "heure": HEURE}],
    )
    return source


def encode(path: str) -> dict:
    return {"path": path}


class TestWhoMayCall:
    def test_every_route_needs_a_token(self, env):
        with patch("app.api.memory_routes.memory_service", env.service), TestClient(build_app(env, None)) as c:
            for method, url in ROUTES:
                response = c.request(method, url, json={"text": "x", "to": "y.md"})
                assert response.status_code == 401, (method, url)

    def test_an_account_without_a_ulid_has_no_folder(self, env):
        app = build_app(env, "default")
        with patch("app.api.memory_routes.memory_service", env.service), TestClient(app) as c:
            assert c.get("/memory/tree").status_code == 404


class TestTheTree:
    async def test_it_lists_the_files_as_they_are_in_the_folder(self, env, owner):
        await seed_roses(env)
        await env.service.write(USER_A, "Maison/Le code du portail.md", fiche("1234", resume="Portail"))

        files = owner.get("/memory/tree").json()["files"]

        by_path = {f["path"]: f for f in files}
        assert set(by_path) == set(env.service.folder(USER_A).files())
        roses = by_path[ROSES]
        assert (roses["kind"], roses["editable"], roses["title"]) == ("fiche", True, "Françoise n'aime pas les roses")
        assert roses["resume"] == "Ce que Françoise pense des fleurs"
        assert by_path["journal.md"]["kind"] == "generated"
        assert by_path["journal.md"]["editable"] is False
        assert next(f for f in files if f["path"].startswith("Conversations/"))["kind"] == "conversation"

    async def test_it_shows_what_the_night_set_aside_and_its_questions(self, env, owner):
        await env.service.write(
            USER_A,
            "Maison/Ancien.md",
            "---\nid: aaaaaaaa1\nresume: vieux\narchivee: 2026-10-01\nquestion: Toujours vrai ?\n---\n\nTexte.\n",
        )

        entry = next(f for f in owner.get("/memory/tree").json()["files"] if f["path"] == "Maison/Ancien.md")

        assert entry["archived"] is True
        assert entry["question"] == "Toujours vrai ?"

    async def test_another_user_sees_nothing_of_it(self, env, other):
        await seed_roses(env)

        assert other.get("/memory/tree").json() == {"files": []}


class TestOpeningAFile:
    async def test_a_fiche_comes_with_its_carte_its_claims_and_where_each_comes_from(self, env, owner):
        source = await seed_roses(env)

        body = owner.get(URL, params=encode(ROSES)).json()

        assert body["kind"] == "fiche"
        assert body["editable"] is True
        assert "n'aime pas les roses" in body["text"]
        assert body["carte"]["resume"] == "Ce que Françoise pense des fleurs"
        assert body["carte"]["evoque"] == ["bouquet", "fleurs"]
        assert body["known"] == [
            {
                "texte": "Françoise n'aime pas les roses",
                "provenance": "dit",
                "source": source,
                "heure": HEURE,
                "sourcePath": source,
                "sourceKind": "conversation",
                "sourceExists": True,
                "replacedOn": None,
                "motif": "",
            }
        ]

    async def test_the_source_conversation_opens_at_the_cited_minute(self, env, owner):
        source = await seed_roses(env)

        body = owner.get(URL, params=encode(source)).json()

        assert body["kind"] == "conversation"
        assert body["editable"] is False
        assert f"## {HEURE} — Propriétaire" in body["text"]

    async def test_an_app_claim_has_no_file_to_open(self, env, owner):
        await env.service.write(
            USER_A,
            "Maison/Compteur.md",
            "---\nid: aaaaaaaa2\nresume: eau\n---\n\n## Ce qu'on sait\n\n- 12 m³ ce mois — observé · app : relevé d'eau\n",
        )

        claim = owner.get(URL, params=encode("Maison/Compteur.md")).json()["known"][0]

        assert (claim["provenance"], claim["sourceKind"], claim["sourcePath"]) == ("observé", "app", None)

    async def test_what_was_replaced_is_listed_apart(self, env, owner):
        await env.service.write(
            USER_A,
            "Famille/Adresse.md",
            "---\nid: aaaaaaaa3\nresume: adresse\n---\n\n## Ce qu'on sait\n\n- Habite à Rennes — déduit · ancienne mémoire\n\n"
            "## Ce qui n'est plus vrai\n\n- ~~Habite à Nantes~~ — déduit · ancienne mémoire — remplacé le 2026-10-13 : a déménagé\n",
        )

        body = owner.get(URL, params=encode("Famille/Adresse.md")).json()

        assert [c["texte"] for c in body["known"]] == ["Habite à Rennes"]
        assert (body["replaced"][0]["texte"], body["replaced"][0]["motif"]) == ("Habite à Nantes", "a déménagé")

    async def test_a_broken_carte_is_said_not_hidden(self, env, owner):
        env.service.folder(USER_A).write("Famille/Cassée.md", "---\nresume: [pas fermé\n---\n\nTexte.\n")

        body = owner.get(URL, params=encode("Famille/Cassée.md")).json()

        assert body["unreadable"]
        assert body["text"].startswith("---")

    async def test_a_file_that_is_not_there_is_unknown(self, owner):
        assert owner.get(URL, params=encode("Famille/Rien.md")).status_code == 404

    async def test_another_users_file_is_an_unknown_path(self, env, other):
        await seed_roses(env)

        assert other.get(URL, params=encode(ROSES)).status_code == 404

    @pytest.mark.parametrize("path", ["../autre/x.md", "Famille/../../x.md", "/etc/passwd", "a\\b.md", ".caché.md"])
    async def test_a_path_that_leaves_the_folder_is_refused(self, owner, path):
        assert owner.get(URL, params=encode(path)).status_code == 400


class TestCorrectingAFiche:
    async def test_the_file_the_index_and_the_journal_all_follow(self, env, owner):
        await seed_roses(env)
        opened = owner.get(URL, params=encode(ROSES)).json()
        fixed = opened["text"].replace("n'aime pas les roses", "adore les roses")

        response = owner.put(URL, params=encode(ROSES), json={"text": fixed, "hash": opened["hash"]})

        assert response.status_code == 200
        assert "adore les roses" in env.local(ROSES)
        indexed = await env.service.indexed(USER_A, ROSES)
        assert "adore les roses" in indexed.body
        journal = env.local("journal.md")
        assert f"modifié `{ROSES}` — corrigé par le propriétaire depuis l'onglet Mémoire" in journal

    async def test_the_correction_is_pushed_to_the_bucket(self, env, owner):
        await seed_roses(env)
        opened = owner.get(URL, params=encode(ROSES)).json()

        owner.put(URL, params=encode(ROSES), json={"text": opened["text"].replace("pas les", "bien les")})
        await env.service.sync_user(USER_A)

        assert "n'aime bien les roses" in env.remote(ROSES)

    async def test_the_next_recall_reflects_the_correction(self, env, owner):
        await seed_roses(env)
        opened = owner.get(URL, params=encode(ROSES)).json()
        fixed = opened["text"].replace("resume: Ce que Françoise pense des fleurs", "resume: Françoise adore les roses")

        owner.put(URL, params=encode(ROSES), json={"text": fixed})
        cards = await env.service.cards(USER_A)
        found = recall.rank(cards, "Je cherche un bouquet pour Françoise", date(2026, 10, 14))

        assert [r.path for r in found if r.path == ROSES] == [ROSES]
        assert next(r.resume for r in found if r.path == ROSES) == "Françoise adore les roses"

    async def test_a_version_that_drops_the_id_keeps_the_fiche_identity(self, env, owner):
        await seed_roses(env)
        opened = owner.get(URL, params=encode(ROSES)).json()
        card_id = opened["carte"]["id"]
        without_id = opened["text"].replace(f"id: {card_id}\n", "")
        assert card_id not in without_id

        owner.put(URL, params=encode(ROSES), json={"text": without_id})

        assert owner.get(URL, params=encode(ROSES)).json()["carte"]["id"] == card_id

    async def test_a_file_that_changed_since_it_was_opened_is_not_overwritten(self, env, owner):
        await seed_roses(env)
        opened = owner.get(URL, params=encode(ROSES)).json()
        await env.service.write(USER_A, ROSES, opened["text"] + "\nUne ligne de plus.\n")

        response = owner.put(URL, params=encode(ROSES), json={"text": opened["text"] + "x", "hash": opened["hash"]})

        assert response.status_code == 409
        assert "Une ligne de plus." in env.local(ROSES)

    async def test_a_carte_that_cannot_be_read_is_refused_with_the_reason(self, env, owner):
        await seed_roses(env)

        response = owner.put(URL, params=encode(ROSES), json={"text": "---\nresume: [pas fermé\n---\n\nx\n"})

        assert response.status_code == 400
        assert "carte" in response.json()["detail"]
        assert "Ce que Françoise pense" in env.local(ROSES)

    async def test_a_fiche_whose_carte_is_broken_can_be_repaired_from_here(self, env, owner):
        env.service.folder(USER_A).write("Famille/Cassée.md", "---\nresume: [pas fermé\n---\n\nTexte.\n")
        repaired = fiche("Réparée", resume="Une carte lisible")

        response = owner.put(URL, params=encode("Famille/Cassée.md"), json={"text": repaired})

        assert response.status_code == 200
        assert response.json()["unreadable"] is None
        assert response.json()["carte"]["resume"] == "Une carte lisible"
        assert "corrigé par le propriétaire" in env.local("journal.md")

    async def test_a_conversation_and_a_generated_file_are_not_rewritten_from_here(self, env, owner):
        source = await seed_roses(env)
        before = env.local(source)

        assert owner.put(URL, params=encode(source), json={"text": "Réécrit."}).status_code == 409
        assert owner.put(URL, params=encode("journal.md"), json={"text": "Réécrit."}).status_code == 409
        assert env.local(source) == before

    async def test_another_users_fiche_is_not_reachable(self, env, other):
        await seed_roses(env)

        assert other.put(URL, params=encode(ROSES), json={"text": "piraté"}).status_code == 404
        assert "piraté" not in (env.local(ROSES) or "")

    async def test_a_save_that_changes_nothing_writes_nothing(self, env, owner):
        await seed_roses(env)
        opened = owner.get(URL, params=encode(ROSES)).json()
        journal = env.local("journal.md")

        assert owner.put(URL, params=encode(ROSES), json={"text": opened["text"]}).status_code == 200
        assert env.local("journal.md") == journal


class TestMovingAndDeleting:
    async def test_a_fiche_is_renamed_keeping_its_id_and_the_journal_says_so(self, env, owner):
        await seed_roses(env)
        card_id = owner.get(URL, params=encode(ROSES)).json()["carte"]["id"]

        response = owner.post("/memory/file/move", params=encode(ROSES), json={"to": "Amis/Françoise et les roses.md"})

        assert response.status_code == 200
        assert response.json()["carte"]["id"] == card_id
        assert env.local(ROSES) is None
        assert env.local("Amis/Françoise et les roses.md") is not None
        assert f"déplacé `{ROSES}` → `Amis/Françoise et les roses.md`" in env.local("journal.md")

    async def test_a_name_that_is_taken_or_reserved_is_refused(self, env, owner):
        await seed_roses(env)
        await env.service.write(USER_A, "Amis/Pris.md", fiche("x", resume="pris"))

        assert owner.post("/memory/file/move", params=encode(ROSES), json={"to": "Amis/Pris.md"}).status_code == 409
        assert owner.post("/memory/file/move", params=encode(ROSES), json={"to": "Conversations/x.md"}).status_code == 400
        assert owner.post("/memory/file/move", params=encode(ROSES), json={"to": "../x.md"}).status_code == 400
        assert env.local(ROSES) is not None

    async def test_a_move_onto_itself_changes_nothing_and_loses_nothing(self, env, owner):
        await seed_roses(env)
        before = env.local(ROSES)

        response = owner.post("/memory/file/move", params=encode(ROSES), json={"to": ROSES})

        assert response.status_code == 200
        assert env.local(ROSES) == before
        assert "déplacé" not in (env.local("journal.md") or "")
        assert owner.get(URL, params=encode(ROSES)).status_code == 200

    async def test_a_name_that_differs_only_by_its_case_is_a_rename_not_a_clash(self, env, owner):
        await seed_roses(env)
        renamed = ROSES.replace("Famille/", "famille/", 1)

        response = owner.post("/memory/file/move", params=encode(ROSES), json={"to": renamed})

        assert response.status_code == 200
        assert env.local(renamed) is not None

    async def test_a_conversation_is_not_moved(self, env, owner):
        source = await seed_roses(env)

        assert owner.post("/memory/file/move", params=encode(source), json={"to": "Amis/x.md"}).status_code == 409

    async def test_a_fiche_is_deleted_from_the_folder_the_index_and_the_bucket(self, env, owner):
        await seed_roses(env)
        await env.service.sync_user(USER_A)
        assert env.remote(ROSES) is not None

        assert owner.delete(URL, params=encode(ROSES)).status_code == 204
        await env.service.sync_user(USER_A)

        assert env.local(ROSES) is None
        assert await env.service.indexed(USER_A, ROSES) is None
        assert env.remote(ROSES) is None
        assert f"supprimé `{ROSES}`" in env.local("journal.md")
        assert owner.get(URL, params=encode(ROSES)).status_code == 404

    async def test_a_conversation_is_not_deleted_from_here(self, env, owner):
        source = await seed_roses(env)

        assert owner.delete(URL, params=encode(source)).status_code == 409
        assert env.local(source) is not None

    async def test_another_users_fiche_cannot_be_deleted_nor_moved(self, env, other):
        await seed_roses(env)

        assert other.delete(URL, params=encode(ROSES)).status_code == 404
        assert other.post("/memory/file/move", params=encode(ROSES), json={"to": "Amis/x.md"}).status_code == 404
        assert env.local(ROSES) is not None


class TestTheJournal:
    async def test_it_reads_back_newest_first_and_marks_the_night(self, env, owner):
        await env.service.journal(USER_A, "créé `Maison/A.md` — première fois")
        env.clock.advance(minutes=5)
        await env.service.journal(USER_A, "entretien nocturne du 2026-10-13 : 2 fiches complétées")

        entries = owner.get("/memory/journal").json()["entries"]

        assert [e["text"] for e in entries] == [
            "entretien nocturne du 2026-10-13 : 2 fiches complétées",
            "créé `Maison/A.md` — première fois",
        ]
        assert [e["night"] for e in entries] == [True, False]
        assert entries[0]["at"] == "2026-10-13 09h35"

    async def test_the_limit_is_honoured(self, env, owner):
        for i in range(5):
            await env.service.journal(USER_A, f"ligne {i}")

        assert len(owner.get("/memory/journal", params={"limit": 2}).json()["entries"]) == 2

    async def test_another_users_journal_is_not_shown(self, env, other):
        await env.service.journal(USER_A, "secret")

        assert other.get("/memory/journal").json() == {"entries": []}


class TestTheLinkWithTheBucket:
    async def test_a_bucket_that_answers_says_when_it_last_did(self, env, owner):
        await env.service.write(USER_A, "Maison/A.md", fiche("a", resume="a"))
        await env.service.sync_user(USER_A)

        state = owner.get("/memory/sync").json()

        assert state["enabled"] is True
        assert state["degraded"] is False
        assert state["lastSyncAt"].startswith("2026-10-13T09:30")
        assert state["pending"] == 0

    async def test_a_bucket_that_is_down_says_since_when_and_what_waits(self, env, owner):
        await env.service.write(USER_A, "Maison/A.md", fiche("a", resume="a"))
        await env.service.sync_user(USER_A)
        env.bucket.down = True
        env.clock.advance(hours=2)
        await env.service.write(USER_A, "Maison/B.md", fiche("b", resume="b"))

        state = owner.post("/memory/sync").json()

        assert state["degraded"] is True
        assert state["degradedSince"].startswith("2026-10-13T11:30")
        assert state["lastSyncAt"].startswith("2026-10-13T09:30")
        assert state["pending"] >= 1
        assert "Maison/B.md" in state["pendingPaths"]
        assert state["lastError"]

    async def test_the_pending_writes_are_the_users_own(self, env, other):
        env.bucket.down = True
        await env.service.write(USER_A, "Maison/B.md", fiche("b", resume="b"))

        assert other.get("/memory/sync").json()["pending"] == 0

    async def test_the_versions_set_aside_are_listed(self, env, owner):
        await env.service.write(USER_A, "Maison/A.md", fiche("a", resume="a"))
        await env.service.sync_user(USER_A)
        await env.service.write(USER_A, "Maison/A.md", fiche("de Maggie", resume="a"))
        env.bucket.seed(env.key("Maison/A.md"), fiche("du propriétaire", resume="a"))
        await env.service.sync_user(USER_A)

        conflicts = owner.get("/memory/sync").json()["conflicts"]

        assert len(conflicts) == 1
        assert conflicts[0].startswith("Conflits/")

    async def test_a_sync_pushes_what_was_waiting(self, env, owner):
        env.bucket.down = True
        await env.service.write(USER_A, "Maison/B.md", fiche("b", resume="b"))
        owner.post("/memory/sync")
        env.bucket.down = False

        state = owner.post("/memory/sync").json()

        assert state["degraded"] is False
        assert state["pending"] == 0
        assert env.remote("Maison/B.md") is not None

    async def test_rebuild_restores_the_index_from_the_files(self, env, owner):
        await seed_roses(env)
        from app.db.memory_index_repository import memory_index_repo

        await memory_index_repo.clear_user(USER_A)
        assert await env.service.indexed(USER_A, ROSES) is None

        response = owner.post("/memory/rebuild")

        assert response.status_code == 200
        assert response.json()["files"] >= 2
        assert (await env.service.indexed(USER_A, ROSES)).resume == "Ce que Françoise pense des fleurs"


class TestMissedRecalls:
    async def test_the_cases_are_listed_and_a_case_can_be_removed(self, env, owner):
        await env.service.write(USER_A, ROSES, fiche("Françoise n'aime pas les roses.", resume="Roses"))
        with patch("app.memory.tools.memory_service", env.service):
            await misses.record(
                env.service, USER_A, demande="Un bouquet pour la fête des mères", souvenir="", chemin=ROSES, today=date(2026, 10, 14)
            )

        listed = owner.get("/memory/misses").json()["misses"]

        assert len(listed) == 1
        assert (listed[0]["statut"], listed[0]["attendue"]) == ("ouvert", ROSES)
        assert listed[0]["demande"] == "Un bouquet pour la fête des mères"
        assert owner.delete(URL, params=encode(listed[0]["path"])).status_code == 204
        assert owner.get("/memory/misses").json() == {"misses": []}
        assert "supprimé" in env.local("journal.md")

    async def test_another_users_cases_are_not_shown(self, env, other):
        await env.service.write(USER_A, ROSES, fiche("x", resume="Roses"))
        with patch("app.memory.tools.memory_service", env.service):
            await misses.record(env.service, USER_A, demande="Un bouquet", souvenir="", chemin=ROSES, today=date(2026, 10, 14))

        assert other.get("/memory/misses").json() == {"misses": []}


class TestTheExport:
    async def test_it_is_a_zip_of_the_whole_folder_readable_without_the_app(self, env, owner):
        source = await seed_roses(env)
        await env.service.sync_user(USER_A)

        response = owner.get("/memory/export")

        assert response.status_code == 200
        assert response.headers["content-type"] == "application/zip"
        assert "attachment" in response.headers["content-disposition"]
        archive = zipfile.ZipFile(io.BytesIO(response.content))
        names = set(archive.namelist())
        assert {f"Mémoire/{ROSES}", f"Mémoire/{source}", "Mémoire/LISEZMOI.md", "Mémoire/journal.md"} <= names
        assert archive.read(f"Mémoire/{ROSES}").decode() == env.local(ROSES)

    async def test_it_holds_nothing_of_another_user(self, env, other):
        await seed_roses(env)

        names = zipfile.ZipFile(io.BytesIO(other.get("/memory/export").content)).namelist()

        assert not any("Françoise" in name for name in names)


class TestTheWebFollowsLive:
    async def test_a_write_is_announced_once_with_what_changed(self, env):
        told: list[tuple[str, dict]] = []

        async def notifier(user_id: str, payload: dict) -> None:
            told.append((user_id, payload))

        env.service.notifier = notifier
        await env.service.write(USER_A, "Maison/A.md", fiche("a", resume="a"))
        await env.service.write(USER_A, "Maison/B.md", fiche("b", resume="b"))
        await asyncio.sleep(0.6)

        assert len(told) == 1
        assert told[0][0] == USER_A
        assert {"Maison/A.md", "Maison/B.md", "journal.md"} <= set(told[0][1]["paths"])

    async def test_a_file_pulled_from_the_bucket_is_announced(self, env):
        told: list[dict] = []

        async def notifier(user_id: str, payload: dict) -> None:
            told.append(payload)

        await env.service.write(USER_A, "Maison/A.md", fiche("a", resume="a"))
        await env.service.sync_user(USER_A)
        env.service.notifier = notifier
        env.bucket.seed(env.key("Maison/A.md"), fiche("modifié dans le bucket", resume="a"))
        await env.service.sync_user(USER_A)
        await asyncio.sleep(0.6)

        assert any("Maison/A.md" in (p["paths"] or []) for p in told)

    async def test_a_write_during_a_slow_announcement_is_announced_too(self, env):
        told: list[list[str]] = []
        release = asyncio.Event()

        async def notifier(user_id: str, payload: dict) -> None:
            told.append(payload["paths"])
            if len(told) == 1:
                await release.wait()

        env.service.notifier = notifier
        await env.service.write(USER_A, "Maison/A.md", fiche("a", resume="a"))
        await asyncio.sleep(0.6)
        assert len(told) == 1

        await env.service.write(USER_A, "Maison/B.md", fiche("b", resume="b"))
        release.set()
        await asyncio.sleep(0.9)

        assert len(told) == 2
        assert "Maison/B.md" in told[1]

    async def test_a_broken_hub_does_not_break_a_write(self, env):
        async def notifier(user_id: str, payload: dict) -> None:
            raise RuntimeError("hub down")

        env.service.notifier = notifier

        await env.service.write(USER_A, "Maison/A.md", fiche("a", resume="a"))
        await asyncio.sleep(0.6)

        assert env.local("Maison/A.md") is not None

    def test_the_singleton_has_nobody_to_tell_until_the_app_starts(self):
        assert memory_service.notifier is None


def test_fiches_are_the_only_editable_kind():
    assert carte_mod.parse(fiche("x", resume="y")).carte.resume == "y"
    assert fiches.KNOWN_HEADING
