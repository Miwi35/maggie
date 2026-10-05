import logging
from pathlib import Path

import pytest

from app.agents.registry import SubagentRegistry, parse_subagent
from app.config import settings

SHIPPED_AGENTS = Path(__file__).parent.parent / "data" / "agents"
# The tools the API serves (api/contract/mcp-tools.json, out of reach of the agent's test container)
MCP_TOOL_NAMES = (
    "add_grocery_item",
    "assign_product_store",
    "check_conflicts",
    "check_grocery_item",
    "create_event",
    "create_recipe",
    "create_task",
    "delete_event",
    "delete_recipe",
    "delete_task",
    "end_errand",
    "generate_grocery_list",
    "get_daily_score",
    "get_events_by_date",
    "get_finance_dashboard",
    "get_grocery_list",
    "get_recipe",
    "get_tasks",
    "get_upcoming_events",
    "get_user_timezone",
    "manage_accounts",
    "manage_agendas",
    "manage_categories",
    "manage_categorization_rules",
    "manage_envelopes",
    "manage_ingredients",
    "manage_loans",
    "manage_meals",
    "manage_notifications",
    "manage_products",
    "manage_recurring_groceries",
    "manage_safety_cushion",
    "manage_stores",
    "manage_transactions",
    "monthly_review",
    "move_to_fallback",
    "remove_grocery_item",
    "reorder_grocery_items",
    "search",
    "search_ciqual_foods",
    "search_ingredients",
    "search_products",
    "search_recipes",
    "update_event",
    "update_recipe",
    "update_task",
)

VALID = """---
name: researcher
description: Recherche en lecture seule
model: haiku
tools: ["search*", "get_*"]
max_iterations: 4
---
Tu cherches et tu synthétises.
"""


def _write(directory: Path, filename: str, content: str) -> Path:
    path = directory / filename
    path.write_text(content, encoding="utf-8")
    return path


class TestParsing:
    def test_reads_every_field_and_the_body_as_prompt(self, tmp_path):
        definition = parse_subagent(_write(tmp_path, "researcher.md", VALID))

        assert definition.name == "researcher"
        assert definition.description == "Recherche en lecture seule"
        assert definition.model == "haiku"
        assert definition.tools == ["search*", "get_*"]
        assert definition.max_iterations == 4
        assert definition.prompt == "Tu cherches et tu synthétises."

    def test_max_iterations_defaults_to_8_and_name_to_the_file_stem(self, tmp_path):
        content = "---\ndescription: d\nmodel: sonnet\ntools: []\n---\nPrompt"
        definition = parse_subagent(_write(tmp_path, "cook.md", content))

        assert definition.max_iterations == 8
        assert definition.name == "cook"

    @pytest.mark.parametrize(
        ("content", "reason"),
        [
            ("no frontmatter", "frontmatter"),
            ("---\nnot: valid: yaml: [[\n---\nPrompt", "frontmatter"),
            ("---\nmodel: haiku\ntools: []\n---\nPrompt", "description"),
            ("---\ndescription: d\nmodel: haiku\ntools: search*\n---\nPrompt", "tools"),
            ("---\ndescription: d\nmodel: haiku\ntools: []\nmax_iterations: 0\n---\nPrompt", "max_iterations"),
            ("---\ndescription: d\nmodel: haiku\ntools: []\n---\n", "prompt"),
        ],
    )
    def test_invalid_file_is_refused(self, tmp_path, content, reason):
        with pytest.raises(ValueError, match=reason):
            parse_subagent(_write(tmp_path, "bad.md", content))

    def test_unknown_model_alias_is_refused(self, tmp_path):
        content = "---\ndescription: d\nmodel: gpt-9\ntools: []\n---\nPrompt"

        with pytest.raises(ValueError, match="unknown model 'gpt-9'"):
            parse_subagent(_write(tmp_path, "bad.md", content))


class TestModelAliases:
    def test_aliases_point_to_the_configured_models(self):
        assert settings.model_aliases == {
            "haiku": "claude-haiku-4-5-20251001",
            "sonnet": settings.anthropic_model,
            "opus": "claude-opus-5-5",
        }

    def test_definition_resolves_its_alias(self, tmp_path):
        definition = parse_subagent(_write(tmp_path, "r.md", VALID))

        assert definition.model_id == "claude-haiku-4-5-20251001"


class TestRegistry:
    def test_loads_valid_files_and_ignores_invalid_ones(self, tmp_path, caplog):
        _write(tmp_path, "researcher.md", VALID)
        _write(tmp_path, "broken.md", "no frontmatter")
        _write(tmp_path, "alien.md", "---\ndescription: d\nmodel: gpt-9\ntools: []\n---\nPrompt")
        registry = SubagentRegistry()

        with caplog.at_level(logging.WARNING):
            loaded = registry.load(tmp_path)

        assert loaded == 1
        assert [a.name for a in registry.list_all()] == ["researcher"]
        assert "broken.md" in caplog.text
        assert "alien.md" in caplog.text

    def test_first_file_wins_when_two_share_a_name(self, tmp_path):
        _write(tmp_path, "a.md", VALID)
        _write(tmp_path, "b.md", VALID.replace("Recherche en lecture seule", "Doublon"))
        registry = SubagentRegistry()

        assert registry.load(tmp_path) == 1
        assert registry.get("researcher").description == "Recherche en lecture seule"

    def test_missing_directory_gives_an_empty_registry(self, tmp_path):
        registry = SubagentRegistry()
        registry.load(tmp_path)

        assert registry.load(tmp_path / "absent") == 0
        assert registry.agents == {}

    def test_get_unknown_agent(self):
        assert SubagentRegistry().get("ghost") is None


class TestShippedAgents:
    def test_researcher_only_reaches_tools_that_read(self):
        from app.agents.delegate import select_tools
        from app.llm.dry_run import READ_ONLY_TOOLS
        from app.llm.tools import INSTRUCTION_TOOLS, MEMORY_TOOLS, PROACTION_TOOLS, SKILL_TOOLS

        registry = SubagentRegistry()
        registry.load(SHIPPED_AGENTS)
        researcher = registry.get("researcher")
        assert researcher is not None
        assert researcher.model == "haiku"

        native = MEMORY_TOOLS + INSTRUCTION_TOOLS + SKILL_TOOLS + PROACTION_TOOLS
        everything = [{"name": n} for n in MCP_TOOL_NAMES] + [{"name": t["name"]} for t in native]
        reachable = {t["name"] for t in select_tools(researcher, everything)}

        # get_grocery_list is the one read that may create the user's (empty) list, which dry-run does not allow
        # but the « courses » of a weekly review needs.
        assert reachable - READ_ONLY_TOOLS == {"get_grocery_list"}
        assert {"get_upcoming_events", "get_finance_dashboard", "search_memory", "get_skill"} <= reachable
