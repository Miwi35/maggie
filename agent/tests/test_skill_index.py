import json
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from app.db.skill_model import Skill
from app.llm.gateway import LLMGateway
from app.skills.index import SkillEntry, SkillIndex


def _index() -> SkillIndex:
    index = SkillIndex()
    index._publisher.publish = AsyncMock()
    return index


class TestSkillPersistence:
    async def test_skill_survives_a_restart(self, agent_db):
        """MAG-187: a skill created before a deploy is in the rebuilt index and loadable afterwards."""
        before = _index()
        await before.create("concert-link", "Lier un concert", ["concert"], "Cherche le lien", user_id="u")

        after_restart = _index()
        assert after_restart.entries == []
        await after_restart.rebuild()

        assert [e.name for e in after_restart.entries] == ["concert-link"]
        assert "- concert-link: Lier un concert" in after_restart.get_skills_index()
        assert "Cherche le lien" in (await after_restart.get("concert-link") or "")

    async def test_index_rebuilt_empty_after_restart_fails_the_build(self, agent_db):
        """The guard of MAG-187: stored skills must never come back as an empty index."""
        await _index().create("a-skill", "d", [], "body", user_id="u")

        restarted = _index()
        await restarted.rebuild()

        assert restarted.entries, "index rebuilt empty although a skill is stored"
        assert restarted.get_skills_index() != ""

    async def test_rebuild_empty_store(self, agent_db):
        index = _index()
        await index.rebuild()
        assert index.entries == []
        assert index.get_skills_index() == ""

    async def test_rebuild_propagates_a_store_failure(self):
        """An unreadable database must not be mistaken for 'no skill taught yet'."""
        index = _index()
        index.repo = MagicMock(list_all=AsyncMock(side_effect=RuntimeError("db down")))
        with pytest.raises(RuntimeError):
            await index.rebuild()

    async def test_rebuild_drops_skills_deleted_elsewhere(self, agent_db):
        index = _index()
        await index.create("gone", "d", [], "body", user_id="u")
        await index.repo.delete("gone")

        await index.rebuild()

        assert index.entries == []


class TestSkillIndex:
    async def test_get_returns_markdown_with_frontmatter(self, agent_db):
        index = _index()
        await index.create("test-skill", "Test", ["test"], "Full content here", user_id="u")

        result = await index.get("test-skill")

        assert result is not None
        assert result.startswith("---\n")
        assert "name: test-skill" in result
        assert "Full content here" in result

    async def test_get_unknown_skill(self, agent_db):
        assert await _index().get("nonexistent") is None

    async def test_list_all(self, agent_db):
        index = _index()
        await index.create("skill-a", "A", ["a"], "Content", user_id="u")
        await index.create("skill-b", "B", ["b"], "Content", user_id="u")

        assert {e.name for e in index.list_all()} == {"skill-a", "skill-b"}

    async def test_create_stores_a_row_and_publishes(self, agent_db):
        index = _index()

        entry = await index.create("new-skill", "A new skill", ["tag1", "tag2"], "Procedure content", user_id="u")

        assert entry.name == "new-skill"
        with agent_db() as session:
            row = session.query(Skill).one()
            assert (row.name, row.description, row.tags, row.content) == (
                "new-skill",
                "A new skill",
                ["tag1", "tag2"],
                "Procedure content",
            )
        index._publisher.publish.assert_awaited_once()

    async def test_create_same_name_replaces_the_skill(self, agent_db):
        index = _index()
        await index.create("my-skill", "Old", ["old"], "Old body", user_id="u")
        entry = await index.create("my-skill", "New", ["new"], "New body", user_id="u")

        assert index.list_all() == [entry]
        assert index.entries[0].description == "New"
        assert "New body" in (await index.get("my-skill") or "")
        with agent_db() as session:
            assert session.query(Skill).count() == 1

    async def test_update_changes_only_the_given_fields(self, agent_db):
        index = _index()
        await index.create("my-skill", "Desc", ["t"], "Body", user_id="u")

        entry = await index.update("my-skill", content="New body", user_id="u")

        assert entry is not None
        assert (entry.description, entry.tags) == ("Desc", ["t"])
        assert "New body" in (await index.get("my-skill") or "")

    async def test_update_unknown_skill(self, agent_db):
        assert await _index().update("nope", description="x", user_id="u") is None

    async def test_delete_skill(self, agent_db):
        index = _index()
        await index.create("test-skill", "Test", ["test"], "Content", user_id="u")

        assert await index.delete("test-skill", user_id="u") is True

        assert index.entries == []
        with agent_db() as session:
            assert session.query(Skill).count() == 0
        assert await index.delete("test-skill", user_id="u") is False

    async def test_publish_failure_does_not_fail_the_write(self, agent_db):
        index = _index()
        index._publisher.publish = AsyncMock(side_effect=RuntimeError("mercure down"))

        await index.create("s", "d", [], "body", user_id="u")

        assert [e.name for e in index.entries] == ["s"]

    def test_skills_index_lists_name_and_description(self):
        """The index lists every skill as '- name: description'."""
        index = SkillIndex()
        index.entries = [
            SkillEntry("concert-link", "Concert links", ["concert"]),
            SkillEntry("recipe-grocery-link", "Lier recette et courses", ["recette"]),
        ]

        context = index.get_skills_index()
        assert "Compétences disponibles" in context
        assert "- concert-link: Concert links" in context
        assert "- recipe-grocery-link: Lier recette et courses" in context

    async def test_skills_index_injects_no_body(self, agent_db):
        """Skill bodies and tags never appear in the index."""
        index = _index()
        await index.create("concert-link", "Concert links", ["concert", "musique"], "Cherche le lien", user_id="u")

        context = index.get_skills_index()
        assert "Cherche le lien" not in context
        assert "musique" not in context

    def test_skills_index_order_is_stable(self):
        """The index is sorted by name whatever the insertion order, so it can be cached."""
        entries = [SkillEntry(n, "d", []) for n in ("zebra", "alpha", "mid")]
        first, second = SkillIndex(), SkillIndex()
        first.entries = list(entries)
        second.entries = list(reversed(entries))

        assert first.get_skills_index() == second.get_skills_index()
        lines = first.get_skills_index().splitlines()
        assert [line.split(":")[0] for line in lines if line.startswith("- ")] == ["- alpha", "- mid", "- zebra"]

    def test_skills_index_empty(self):
        """No skills means no section at all."""
        assert SkillIndex().get_skills_index() == ""


class TestLegacyImport:
    async def test_files_left_in_the_old_directory_move_to_the_database(self, agent_db, tmp_path):
        (tmp_path / "concert.md").write_text(
            "---\nname: concert-link\ndescription: Concert links\ntags: [concert]\n---\n\nCherche le lien\n"
        )
        (tmp_path / "invalid.md").write_text("no frontmatter here")
        (tmp_path / "broken.md").write_text("---\nnot: valid: yaml: [[\n---\n\nContent\n")
        index = _index()

        imported = await index.import_legacy_files(tmp_path)
        await index.rebuild()

        assert imported == 1
        assert [(e.name, e.description, e.tags) for e in index.entries] == [
            ("concert-link", "Concert links", ["concert"])
        ]
        assert "Cherche le lien" in (await index.get("concert-link") or "")

    async def test_import_never_overwrites_a_stored_skill(self, agent_db, tmp_path):
        (tmp_path / "s.md").write_text("---\nname: s\ndescription: old file\ntags: []\n---\n\nOld body\n")
        index = _index()
        await index.create("s", "kept", [], "Kept body", user_id="u")

        assert await index.import_legacy_files(tmp_path) == 0

        assert "Kept body" in (await index.get("s") or "")

    async def test_missing_directory_is_a_no_op(self, agent_db, tmp_path):
        assert await _index().import_legacy_files(tmp_path / "absent") == 0


class TestSystemPromptSkills:
    @pytest.mark.asyncio
    async def test_system_prompt_carries_index_without_body(self, agent_db):
        """The system prompt (used by chat and proactions) has the skill index but no skill body."""
        index = _index()
        await index.create("recipe-grocery-link", "Lier recette et courses", [], "Corps secret", user_id="u")

        gateway = LLMGateway.__new__(LLMGateway)
        gateway.personality = MagicMock(get_system_prompt=AsyncMock(return_value="BASE"))
        gateway.agent_memory = MagicMock(get_memory_context=AsyncMock(return_value=""))

        with patch("app.llm.gateway.skill_index", index):
            prompt = await gateway._build_system_prompt("user-1")

        text = "".join(block["text"] for block in prompt)
        assert "- recipe-grocery-link: Lier recette et courses" in text
        assert "Corps secret" not in text


class TestSkillRoutes:
    def test_skills_endpoint_requires_auth(self, client):
        """GET /skills without auth returns 401 or 403."""
        response = client.get("/skills")
        assert response.status_code in (401, 403)

    @patch("app.api.routes.skill_index")
    def test_list_skills_with_auth(self, mock_index, authed_client):
        """GET /skills with valid auth returns skills list."""
        mock_index.list_all.return_value = [
            SkillEntry(name="s1", description="Skill 1", tags=["a"]),
        ]

        response = authed_client.get("/skills")

        assert response.status_code == 200
        data = response.json()
        assert len(data) == 1
        assert data[0]["name"] == "s1"


class TestSkillToolsAfterRestart:
    async def test_get_skill_tool_finds_a_skill_created_before_the_restart(self, agent_db):
        """MAG-187 journey, at the tool level: create_skill, restart, get_skill finds it and the prompt lists it."""
        from app.llm.tools import ToolRouter

        before = _index()
        with patch("app.llm.tools.skill_index", before):
            created = await ToolRouter().call_tool(
                "create_skill",
                {"name": "concert-link", "description": "Lier un concert", "tags": ["concert"], "content": "Étapes"},
                user_id="u",
            )
        assert "error" not in created

        restarted = _index()
        await restarted.rebuild()
        with patch("app.llm.tools.skill_index", restarted):
            found = await ToolRouter().call_tool("get_skill", {"name": "concert-link"}, user_id="u")

        assert "Étapes" in json.loads(found)["content"]
        assert "- concert-link: Lier un concert" in restarted.get_skills_index()


def test_skill_table_is_registered_for_create_all():
    """A model missing from app.main's imports is never created in maggie_agent, and every skill write then fails."""
    import app.main  # noqa: F401
    from app.db.proaction_model import AgentBase

    assert "skill" in AgentBase.metadata.tables


class TestSkillRoutesOnDatabase:
    """The REST endpoints against a real (SQLite) store, so a missed `await` or a wrong field fails here."""

    def test_create_then_read_back(self, agent_db, authed_client):
        index = _index()
        with patch("app.api.routes.skill_index", index):
            created = authed_client.post(
                "/skills", json={"name": "concert-link", "description": "Lier", "tags": ["c"], "content": "Étapes"}
            )
            detail = authed_client.get("/skills/concert-link")
            listing = authed_client.get("/skills")

        assert created.status_code == 201
        assert detail.status_code == 200
        body = detail.json()
        assert (body["name"], body["description"], body["tags"]) == ("concert-link", "Lier", ["c"])
        assert "Étapes" in body["content"]
        assert [s["name"] for s in listing.json()] == ["concert-link"]

    def test_detail_unknown_is_404(self, agent_db, authed_client):
        with patch("app.api.routes.skill_index", _index()):
            assert authed_client.get("/skills/nope").status_code == 404

    def test_create_rejects_a_too_long_name(self, agent_db, authed_client):
        with patch("app.api.routes.skill_index", _index()):
            response = authed_client.post(
                "/skills", json={"name": "x" * 201, "description": "d", "tags": [], "content": "c"}
            )
        assert response.status_code == 422

    def test_update_and_delete(self, agent_db, authed_client):
        index = _index()
        with patch("app.api.routes.skill_index", index):
            authed_client.post("/skills", json={"name": "s", "description": "d", "tags": [], "content": "old"})
            updated = authed_client.put("/skills/s", json={"content": "new"})
            deleted = authed_client.delete("/skills/s")
            gone = authed_client.get("/skills/s")

        assert updated.status_code == 200
        assert deleted.status_code == 200
        assert gone.status_code == 404

    def test_update_unknown_is_404(self, agent_db, authed_client):
        with patch("app.api.routes.skill_index", _index()):
            assert authed_client.put("/skills/missing", json={"content": "x"}).status_code == 404
            assert authed_client.delete("/skills/missing").status_code == 404

    def test_endpoints_require_auth(self, client):
        assert client.get("/skills/any").status_code in (401, 403)
        body = {"name": "s", "description": "d", "tags": [], "content": "c"}
        assert client.post("/skills", json=body).status_code in (401, 403)
        assert client.put("/skills/s", json={"content": "x"}).status_code in (401, 403)
        assert client.delete("/skills/s").status_code in (401, 403)


class TestCreateSkillToolValidation:
    @pytest.mark.parametrize(
        "arguments",
        [
            {"name": "x" * 201, "content": "c"},
            {"name": "s", "content": "c", "tags": "not-a-list"},
            {"name": "", "content": "c"},
            {"name": "s", "content": ""},
        ],
    )
    async def test_bad_input_is_refused_without_storing(self, agent_db, arguments):
        from app.llm.tools import ToolRouter

        index = _index()
        with patch("app.llm.tools.skill_index", index):
            result = await ToolRouter().call_tool("create_skill", arguments, user_id="u")

        assert "error" in json.loads(result)
        with agent_db() as session:
            assert session.query(Skill).count() == 0


    async def test_update_refuses_non_list_tags(self, agent_db):
        from app.llm.tools import ToolRouter

        index = _index()
        await index.create("s", "d", ["t"], "body", user_id="u")
        with patch("app.llm.tools.skill_index", index):
            result = await ToolRouter().call_tool("update_skill", {"name": "s", "tags": "oops"}, user_id="u")

        assert "error" in json.loads(result)
        assert index.entries[0].tags == ["t"]
