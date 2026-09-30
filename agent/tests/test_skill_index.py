from pathlib import Path
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from app.llm.gateway import LLMGateway
from app.skills.index import SkillIndex


class TestSkillIndex:
    def test_rebuild_empty_directory(self, tmp_path):
        """Rebuild with no skill files should produce empty entries."""
        index = SkillIndex(tmp_path)
        index.rebuild()
        assert index.entries == []

    def test_rebuild_creates_directory_if_missing(self, tmp_path):
        """Rebuild should create the directory if it doesn't exist."""
        skill_dir = tmp_path / "skills"
        index = SkillIndex(skill_dir)
        index.rebuild()
        assert skill_dir.exists()
        assert index.entries == []

    def test_rebuild_parses_skill_files(self, tmp_path):
        """Rebuild should parse valid skill files with YAML frontmatter."""
        skill_file = tmp_path / "concert.md"
        skill_file.write_text(
            "---\nname: concert-link\ndescription: Add concert links\ntags: [concert, lien]\n---\n\nContent here\n"
        )

        index = SkillIndex(tmp_path)
        index.rebuild()

        assert len(index.entries) == 1
        assert index.entries[0].name == "concert-link"
        assert index.entries[0].description == "Add concert links"
        assert index.entries[0].tags == ["concert", "lien"]

    def test_get_existing_skill(self, tmp_path):
        """Get should return full file content for a known skill."""
        skill_file = tmp_path / "test.md"
        content = "---\nname: test-skill\ndescription: Test\ntags: [test]\n---\n\nFull content here\n"
        skill_file.write_text(content)

        index = SkillIndex(tmp_path)
        index.rebuild()

        result = index.get("test-skill")
        assert result is not None
        assert "Full content here" in result

    def test_get_unknown_skill(self, tmp_path):
        """Get should return None for an unknown skill."""
        index = SkillIndex(tmp_path)
        index.rebuild()

        result = index.get("nonexistent")
        assert result is None

    def test_list_all(self, tmp_path):
        """list_all should return all entries."""
        for name in ["a", "b"]:
            (tmp_path / f"{name}.md").write_text(
                f"---\nname: skill-{name}\ndescription: Skill {name}\ntags: [{name}]\n---\n\nContent\n"
            )

        index = SkillIndex(tmp_path)
        index.rebuild()

        entries = index.list_all()
        assert len(entries) == 2

    async def test_create_skill(self, tmp_path):
        """Create should write a new file and add to index."""
        index = SkillIndex(tmp_path)
        index._publisher.publish = AsyncMock()
        index.rebuild()

        entry = await index.create(
            name="new-skill",
            description="A new skill",
            tags=["tag1", "tag2"],
            content="Procedure content",
            user_id="test-user",
        )

        assert entry.name == "new-skill"
        assert len(index.entries) == 1
        assert (tmp_path / "new-skill.md").exists()
        file_content = (tmp_path / "new-skill.md").read_text()
        assert "Procedure content" in file_content

    async def test_delete_skill(self, tmp_path):
        """Delete should remove file and entry from index."""
        index = SkillIndex(tmp_path)
        index._publisher.publish = AsyncMock()
        skill_file = tmp_path / "test.md"
        skill_file.write_text("---\nname: test-skill\ndescription: Test\ntags: [test]\n---\n\nContent\n")

        index.rebuild()
        assert len(index.entries) == 1

        deleted = await index.delete("test-skill", user_id="test-user")
        assert deleted is True
        assert len(index.entries) == 0
        assert not skill_file.exists()

    def test_skills_index_lists_name_and_description(self, tmp_path):
        """The index lists every skill as '- name: description'."""
        (tmp_path / "concert.md").write_text(
            "---\nname: concert-link\ndescription: Concert links\ntags: [concert]\n---\n\nContent\n"
        )
        (tmp_path / "recipe.md").write_text(
            "---\nname: recipe-grocery-link\ndescription: Lier recette et courses\ntags: [recette]\n---\n\nBody\n"
        )

        index = SkillIndex(tmp_path)
        index.rebuild()

        context = index.get_skills_index()
        assert "Compétences disponibles" in context
        assert "- concert-link: Concert links" in context
        assert "- recipe-grocery-link: Lier recette et courses" in context

    def test_skills_index_injects_no_body(self, tmp_path):
        """Skill bodies and tags never appear in the index."""
        (tmp_path / "concert.md").write_text(
            "---\nname: concert-link\ndescription: Concert links\ntags: [concert, musique]\n---\n\nCherche le lien\n"
        )

        index = SkillIndex(tmp_path)
        index.rebuild()

        context = index.get_skills_index()
        assert "Cherche le lien" not in context
        assert "musique" not in context

    def test_skills_index_order_is_stable(self, tmp_path):
        """The index is sorted by name whatever the insertion order, so it can be cached."""
        for name in ("zebra", "alpha", "mid"):
            (tmp_path / f"{name}.md").write_text(f"---\nname: {name}\ndescription: d\ntags: []\n---\n\nB\n")

        first = SkillIndex(tmp_path)
        first.rebuild()
        second = SkillIndex(tmp_path)
        second.rebuild()
        second.entries.reverse()

        assert first.get_skills_index() == second.get_skills_index()
        lines = first.get_skills_index().splitlines()
        assert [line.split(":")[0] for line in lines if line.startswith("- ")] == ["- alpha", "- mid", "- zebra"]

    def test_skills_index_empty(self, tmp_path):
        """No skills means no section at all."""
        index = SkillIndex(tmp_path)
        index.rebuild()

        assert index.get_skills_index() == ""

    def test_skip_invalid_files(self, tmp_path):
        """Rebuild should skip files without valid frontmatter."""
        (tmp_path / "valid.md").write_text("---\nname: valid\ndescription: ok\ntags: []\n---\n\nContent\n")
        (tmp_path / "invalid.md").write_text("no frontmatter here")
        (tmp_path / "broken.md").write_text("---\nnot: valid: yaml: [[\n---\n\nContent\n")

        index = SkillIndex(tmp_path)
        index.rebuild()

        assert len(index.entries) == 1
        assert index.entries[0].name == "valid"


class TestSystemPromptSkills:
    @pytest.mark.asyncio
    async def test_system_prompt_carries_index_without_body(self, tmp_path):
        """The system prompt (used by chat and proactions) has the skill index but no skill body."""
        (tmp_path / "recipe.md").write_text(
            "---\nname: recipe-grocery-link\ndescription: Lier recette et courses\ntags: []\n---\n\nCorps secret\n"
        )
        index = SkillIndex(tmp_path)
        index.rebuild()

        gateway = LLMGateway.__new__(LLMGateway)
        gateway.personality = MagicMock(get_system_prompt=AsyncMock(return_value="BASE"))
        gateway.agent_memory = MagicMock(get_memory_context=AsyncMock(return_value=""))

        with patch("app.llm.gateway.skill_index", index):
            prompt = await gateway._build_system_prompt("user-1")

        assert "- recipe-grocery-link: Lier recette et courses" in prompt
        assert "Corps secret" not in prompt


class TestSkillRoutes:
    def test_skills_endpoint_requires_auth(self, client):
        """GET /skills without auth returns 401 or 403."""
        response = client.get("/skills")
        assert response.status_code in (401, 403)

    @patch("app.api.routes.skill_index")
    def test_list_skills_with_auth(self, mock_index, authed_client):
        """GET /skills with valid auth returns skills list."""
        from app.skills.index import SkillEntry

        mock_index.list_all.return_value = [
            SkillEntry(name="s1", description="Skill 1", tags=["a"], file_path=Path("/tmp/s1.md")),
        ]

        response = authed_client.get("/skills")

        assert response.status_code == 200
        data = response.json()
        assert len(data) == 1
        assert data[0]["name"] == "s1"
