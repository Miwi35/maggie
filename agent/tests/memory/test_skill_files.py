from dataclasses import dataclass
from unittest.mock import AsyncMock, patch

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

from app.db.memory_index_repository import memory_outbox_repo
from app.db.skill_model import Skill
from app.db.skill_repository import skill_repo
from app.e2e import setup_e2e
from app.memory import paths
from app.memory.bucket import BucketUnavailable, FakeBucket
from app.memory.service import MemoryService
from app.skills.document import parse_markdown, render_markdown
from app.skills.index import SkillIndex
from app.skills.store import SkillFiles
from tests.conftest import _SyncSessionAsAsync
from tests.memory.conftest import USER_A, Clock

SCOPE = paths.SKILLS_SCOPE
RAPPEL = ("rappel-livraison", "Quand un rappel arrive", ["rappel", "moment:proaction"], "Une notification push.")


@dataclass
class Skills:
    files: SkillFiles
    index: SkillIndex
    bucket: FakeBucket
    root: object

    def key(self, rel: str) -> str:
        return f"Compétences/{rel}"

    def remote(self, rel: str) -> str | None:
        obj = self.bucket.objects.get(self.key(rel))
        return obj.body.decode("utf-8") if obj else None

    def local(self, rel: str) -> str | None:
        return self.files.folder(SCOPE).read(rel)

    async def names(self) -> list[str]:
        return sorted(row.name for row in await skill_repo.list_all())


@pytest.fixture()
def skills(memory_db, tmp_path):
    from app.db.memory_index_model import MemoryOutbox  # noqa: F401  (the table is made by memory_db)

    Skill.__table__.create(memory_db.kw["bind"])

    def open_session():
        return _SyncSessionAsAsync(memory_db())

    clock = Clock()
    bucket = FakeBucket(clock=clock)
    files = SkillFiles(bucket, tmp_path / "local", clock=clock, push_after_write=False)
    index = SkillIndex(store=files)
    index._publisher.publish = AsyncMock()
    with patch("app.db.skill_repository.agent_session", open_session):
        yield Skills(files, index, bucket, tmp_path / "local")


class TestTheFilesAreTheSkills:
    async def test_a_skill_created_by_maggie_is_a_file_in_its_own_folder_and_reaches_the_bucket(self, skills):
        await skills.index.create(*RAPPEL, user_id="u")

        rel = "rappel-livraison.md"
        assert parse_markdown(skills.local(rel)) is not None
        assert skills.local(rel) == render_markdown(*RAPPEL)
        assert await memory_outbox_repo.get(SCOPE, rel) is not None

        await skills.files.sync_user(SCOPE)

        assert skills.remote(rel) == render_markdown(*RAPPEL)
        assert await memory_outbox_repo.count(SCOPE) == 0
        assert [k for k in skills.bucket.objects if k.startswith(f"{USER_A}/")] == []

    async def test_a_skill_corrected_in_the_file_is_taken_at_the_next_sync_and_reaches_the_prompt(self, skills):
        await skills.index.create(*RAPPEL, user_id="u")
        await skills.files.sync_user(SCOPE)

        corrected = render_markdown(RAPPEL[0], RAPPEL[1], RAPPEL[2], "Un SMS, jamais de push.")
        skills.bucket.seed(skills.key("rappel-livraison.md"), corrected)
        await skills.files.sync_user(SCOPE)
        await skills.index.refresh()

        assert "Un SMS, jamais de push." in skills.index.skills_for_moment("proaction")
        assert "Une notification push." not in skills.index.skills_for_moment("proaction")
        assert "Un SMS, jamais de push." in (await skills.index.get(RAPPEL[0]) or "")

    async def test_a_file_the_owner_adds_becomes_a_skill_and_one_he_deletes_goes(self, skills):
        skills.bucket.seed(skills.key("Mes conventions.md"), render_markdown("conventions", "Mes règles", [], "Texte."))
        skills.bucket.seed(skills.key("autre.md"), render_markdown("autre", "D", [], "Texte."))
        await skills.files.sync_user(SCOPE)
        assert await skills.names() == ["autre", "conventions"]

        skills.bucket.remove(skills.key("Mes conventions.md"))
        await skills.files.sync_user(SCOPE)

        assert await skills.names() == ["autre"]

    async def test_the_last_skill_deleted_in_the_bucket_goes_without_an_outage(self, skills):
        skills.bucket.seed(skills.key("seule.md"), render_markdown("seule", "D", [], "Texte."))
        await skills.files.sync_user(SCOPE)
        assert await skills.names() == ["seule"]

        skills.bucket.remove(skills.key("seule.md"))
        await skills.files.sync_user(SCOPE)

        assert await skills.names() == []
        assert skills.local("seule.md") is None
        assert skills.files.degraded() is False

    async def test_the_last_skill_deleted_by_maggie_stays_deleted_after_a_restart(self, skills):
        await skills.index.create(*RAPPEL, user_id="u")
        await skills.files.sync_user(SCOPE)

        assert await skills.index.delete(RAPPEL[0]) is True
        await skills.files.sync_user(SCOPE)
        await skills.files.start_up()

        assert await skills.names() == []
        assert skills.remote("rappel-livraison.md") is None

    async def test_a_dashes_line_inside_a_value_does_not_cut_the_frontmatter(self, skills):
        await skills.index.create("tirets", "avant --- après", ["a---b"], "Corps.", user_id="u")
        await skills.files.sync_user(SCOPE)

        row = await skill_repo.get("tirets")
        assert (row.description, list(row.tags), row.content) == ("avant --- après", ["a---b"], "Corps.")

    async def test_a_frontmatter_without_name_takes_the_file_name(self, skills):
        skills.bucket.seed(skills.key("sans-nom.md"), "---\ndescription: D\n---\n\nCorps.\n")
        await skills.files.sync_user(SCOPE)

        assert await skills.names() == ["sans-nom"]

    async def test_an_unreadable_file_is_left_out_without_stopping_the_others(self, skills):
        skills.bucket.seed(skills.key("cassé.md"), "pas de frontmatter")
        skills.bucket.seed(skills.key("bon.md"), render_markdown("bon", "D", [], "Corps."))
        await skills.files.sync_user(SCOPE)

        assert await skills.names() == ["bon"]

    async def test_the_set_aside_versions_of_a_conflict_are_not_skills(self, skills):
        skills.bucket.seed(skills.key("Conflits/vieux.md"), render_markdown("vieux", "D", [], "Corps."))
        await skills.files.sync_user(SCOPE)

        assert await skills.names() == []

    async def test_update_changes_only_the_given_fields_in_the_file(self, skills):
        await skills.index.create(*RAPPEL, user_id="u")

        await skills.index.update(RAPPEL[0], content="Autre texte.")

        name, description, tags, _ = RAPPEL
        assert skills.local("rappel-livraison.md") == render_markdown(name, description, tags, "Autre texte.")

    async def test_delete_removes_the_file_here_and_in_the_bucket(self, skills):
        await skills.index.create(*RAPPEL, user_id="u")
        await skills.files.sync_user(SCOPE)

        assert await skills.index.delete(RAPPEL[0]) is True
        await skills.files.sync_user(SCOPE)

        assert skills.local("rappel-livraison.md") is None
        assert skills.remote("rappel-livraison.md") is None
        assert await skills.names() == []

    async def test_delete_unknown_skill(self, skills):
        assert await skills.index.delete("inconnue") is False


class TestApartFromTheKnowledge:
    async def test_the_archive_of_a_user_holds_no_skill(self, skills, tmp_path):
        await skills.index.create(*RAPPEL, user_id="u")
        await skills.files.sync_user(SCOPE)
        knowledge = MemoryService(skills.bucket, skills.root, clock=Clock(), push_after_write=False)

        assert knowledge.folder(USER_A).files() == []
        assert not (skills.root / USER_A).exists()
        assert await knowledge.users() == []
        assert not any(key.startswith(f"{USER_A}/") for key in skills.bucket.objects)
        assert all(key.startswith("Compétences/") for key in skills.bucket.objects)
        assert "Compétences/rappel-livraison.md" in skills.bucket.objects

    async def test_the_knowledge_service_never_syncs_the_skills_scope(self, skills):
        knowledge = MemoryService(skills.bucket, skills.root, clock=Clock(), push_after_write=False)

        with pytest.raises(paths.UnsafePath):
            await knowledge.sync_user(SCOPE)

    async def test_the_skills_service_refuses_a_users_scope(self, skills):
        with pytest.raises(paths.UnsafePath):
            await skills.files.sync_user(USER_A)

    async def test_an_outage_of_the_bucket_is_felt_by_the_skills_folder_too(self, skills):
        skills.bucket.down = True
        await skills.index.create(*RAPPEL, user_id="u")

        with pytest.raises(BucketUnavailable):
            await skills.files.sync_user(SCOPE)

        assert await skills.names() == ["rappel-livraison"]
        assert await memory_outbox_repo.count(SCOPE) == 1


class TestTheTableIsDerived:
    async def test_the_table_emptied_then_rebuilt_gives_the_same_skills(self, skills):
        await skills.index.create(*RAPPEL, user_id="u")
        await skills.index.create("courses", "Faire les courses", ["courses"], "Liste d'abord.", user_id="u")
        await skills.files.sync_user(SCOPE)
        before = [(s.name, s.description, list(s.tags), s.content) for s in await skill_repo.list_all()]

        for row in await skill_repo.list_all():
            await skill_repo.delete(row.name)
        assert await skills.names() == []
        rebuilt = await skills.files.rebuild_table()

        after = [(s.name, s.description, list(s.tags), s.content) for s in await skill_repo.list_all()]
        assert rebuilt == 2
        assert sorted(after) == sorted(before)

    async def test_the_rebuild_comes_from_the_bucket_on_a_pod_with_no_disk(self, skills):
        skills.bucket.seed(skills.key("rappel-livraison.md"), render_markdown(*RAPPEL))

        assert await skills.files.rebuild_table() == 1
        assert await skills.names() == ["rappel-livraison"]

    async def test_with_no_file_the_table_is_never_emptied_it_is_moved_to_files(self, skills):
        await skill_repo.upsert("seule", "D", [], "Corps.")

        await skills.files.rebuild_table()

        assert await skills.names() == ["seule"]
        assert skills.remote("seule.md") is not None

    async def test_a_row_whose_file_waits_in_the_outbox_survives_a_pass(self, skills):
        skills.bucket.down = True
        await skills.index.create(*RAPPEL, user_id="u")
        skills.bucket.down = False
        skills.files.folder(SCOPE).delete("rappel-livraison.md")

        await skills.files._reconcile_index(SCOPE, _report())

        assert await skills.names() == ["rappel-livraison"]


class TestFirstStartMovesTheTableToFiles:
    async def test_the_skills_of_the_table_become_files_and_reach_the_bucket(self, skills):
        await skill_repo.upsert(*RAPPEL)
        await skill_repo.upsert("courses", "Faire les courses", ["courses"], "Liste d'abord.")

        await skills.files.start_up()
        skills.files.start = lambda: None
        await skills.files.sync_user(SCOPE)

        assert parse_markdown(skills.remote("rappel-livraison.md"))[1].strip() == RAPPEL[3]
        assert parse_markdown(skills.remote("courses.md"))[0].description == "Faire les courses"
        assert await skills.names() == ["courses", "rappel-livraison"]

    async def test_a_file_already_there_is_never_overwritten(self, skills):
        skills.bucket.seed(skills.key("rappel-livraison.md"), render_markdown(RAPPEL[0], "Version du fichier", [], "Fichier."))
        await skill_repo.upsert(*RAPPEL)

        await skills.files.start_up()

        assert "Version du fichier" in skills.remote("rappel-livraison.md")
        assert (await skill_repo.get(RAPPEL[0])).description == "Version du fichier"

    async def test_the_move_happens_once_a_table_skill_deleted_later_stays_deleted(self, skills):
        await skill_repo.upsert(*RAPPEL)
        await skills.files.start_up()
        await skills.index.delete(RAPPEL[0])
        await skills.files.sync_user(SCOPE)

        await skills.files.start_up()

        assert await skills.names() == []
        assert skills.remote("rappel-livraison.md") is None

    async def test_a_bucket_that_does_not_answer_leaves_the_table_as_it_is(self, skills):
        await skill_repo.upsert(*RAPPEL)
        skills.bucket.down = True

        await skills.files.start_up()

        assert await skills.names() == ["rappel-livraison"]

    async def test_with_no_bucket_the_files_live_on_the_local_copy(self, skills):
        files = SkillFiles(None, skills.root, push_after_write=False)
        await skill_repo.upsert(*RAPPEL)

        await files.start_up()

        assert files.folder(SCOPE).read("rappel-livraison.md") == render_markdown(*RAPPEL)


class TestParseMarkdown:
    TEXT = render_markdown(*RAPPEL)

    def test_it_reads_text_bytes_and_a_file(self, tmp_path):
        path = tmp_path / "rappel.md"
        path.write_text(self.TEXT, encoding="utf-8")

        for source in (self.TEXT, self.TEXT.encode("utf-8"), path):
            entry, body = parse_markdown(source)
            assert (entry.name, entry.description, entry.tags) == RAPPEL[:3]
            assert body.strip() == RAPPEL[3]

    def test_a_file_without_name_takes_its_stem(self, tmp_path):
        path = tmp_path / "sans-nom.md"
        path.write_text("---\ndescription: D\n---\n\nCorps.\n", encoding="utf-8")

        assert parse_markdown(path)[0].name == "sans-nom"

    def test_no_frontmatter_is_none(self):
        assert parse_markdown("juste du texte", default_name="x") is None

    def test_a_single_tag_is_a_list(self):
        assert parse_markdown("---\nname: a\ntags: seul\n---\n\nC\n")[0].tags == ["seul"]


def _report():
    from app.memory.service import PassReport

    return PassReport()


class TestE2eRoutes:
    TOKEN = "token-for-the-test"
    HEADERS = {"X-E2E-Token": TOKEN}

    @pytest.fixture()
    def client(self, monkeypatch, skills):
        monkeypatch.setattr("app.e2e.settings.tts_provider", "fake")
        monkeypatch.setattr("app.e2e.settings.e2e_login_token", self.TOKEN)
        monkeypatch.setattr("app.e2e.memory_service", MemoryService(skills.bucket, skills.root))
        monkeypatch.setattr("app.e2e.skill_files", skills.files)
        app = FastAPI()
        setup_e2e(app)
        with TestClient(app) as c:
            yield c

    def test_the_owner_edits_a_skill_in_the_bucket_and_a_sync_takes_it(self, client, skills):
        text = render_markdown("e2e", "D", [], "Texte corrigé.")

        assert client.put("/e2e/skills/bucket/e2e.md", json={"text": text}).status_code == 401
        assert client.put("/e2e/skills/bucket/e2e.md", json={"text": text}, headers=self.HEADERS).status_code == 200
        assert client.get("/e2e/skills/bucket/e2e.md", headers=self.HEADERS).json()["text"] == text
        assert client.post("/e2e/skills/sync", headers=self.HEADERS).json()["ok"] is True
        assert skills.local("e2e.md") == text

        assert client.delete("/e2e/skills/bucket/e2e.md", headers=self.HEADERS).status_code == 200
        assert client.get("/e2e/skills/bucket/e2e.md", headers=self.HEADERS).status_code == 404

    def test_a_path_that_climbs_out_of_the_folder_is_refused(self, client):
        response = client.put("/e2e/skills/bucket/..%2Fx.md", json={"text": "x"}, headers=self.HEADERS)

        assert response.status_code in (400, 404)
