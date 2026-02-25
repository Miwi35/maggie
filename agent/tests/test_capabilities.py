from app.llm.capabilities import (
    _extract_noun,
    _normalize_noun,
    generate_capability_summary,
)


class TestExtractNoun:
    def test_simple_verb_prefix(self):
        assert _extract_noun("create_event") == "event"

    def test_get_prefix(self):
        assert _extract_noun("get_recipe") == "recipe"

    def test_search_prefix(self):
        assert _extract_noun("search_memory") == "memory"

    def test_by_qualifier_stripped(self):
        assert _extract_noun("get_events_by_date") == "events"

    def test_no_verb_prefix(self):
        assert _extract_noun("search") == "search"

    def test_compound_noun(self):
        assert _extract_noun("add_recurring_grocery") == "recurring_grocery"

    def test_list_prefix(self):
        assert _extract_noun("list_proactions") == "proactions"

    def test_store_prefix(self):
        assert _extract_noun("store_memory") == "memory"

    def test_schedule_prefix(self):
        assert _extract_noun("schedule_proaction") == "proaction"

    def test_delete_prefix(self):
        assert _extract_noun("delete_instruction") == "instruction"


class TestNormalizeNoun:
    def test_direct_match(self):
        assert _normalize_noun("event") == "event"

    def test_plural_to_singular(self):
        assert _normalize_noun("events") == "event"

    def test_compound_noun_component_match(self):
        assert _normalize_noun("recurring_grocery") == "grocery"

    def test_compound_noun_plural_component(self):
        assert _normalize_noun("upcoming_events") == "event"

    def test_unknown_noun_passthrough(self):
        assert _normalize_noun("weather") == "weather"

    def test_proactions_plural(self):
        assert _normalize_noun("proactions") == "proaction"

    def test_search_direct(self):
        assert _normalize_noun("search") == "search"


class TestGenerateCapabilitySummary:
    def test_empty_tools_returns_empty_string(self):
        assert generate_capability_summary([]) == ""
        assert generate_capability_summary(None) == ""

    def test_calendar_groups_events_and_tasks(self):
        tools = [
            {"name": "create_event"},
            {"name": "get_events_by_date"},
            {"name": "update_event"},
            {"name": "delete_event"},
            {"name": "get_tasks"},
            {"name": "create_task"},
        ]
        summary = generate_capability_summary(tools)
        assert "Calendrier (6 outils)" in summary
        assert "événements" in summary
        assert "tâches" in summary

    def test_cuisine_groups_recipes_ingredients_meals_grocery(self):
        tools = [
            {"name": "search_recipe"},
            {"name": "create_recipe"},
            {"name": "get_ingredient"},
            {"name": "add_product"},
            {"name": "create_meal"},
            {"name": "get_grocery"},
        ]
        summary = generate_capability_summary(tools)
        assert "Cuisine (6 outils)" in summary
        assert "recettes" in summary
        assert "ingrédients" in summary

    def test_memory_tools(self):
        tools = [
            {"name": "store_memory"},
            {"name": "search_memory"},
            {"name": "update_memory"},
            {"name": "delete_memory"},
        ]
        summary = generate_capability_summary(tools)
        assert "Mémoire (4 outils)" in summary

    def test_unknown_tools_auto_labeled(self):
        tools = [
            {"name": "get_weather"},
            {"name": "set_weather"},
        ]
        summary = generate_capability_summary(tools)
        assert "Weather" in summary
        assert "2 outils" in summary

    def test_unknown_compound_tools_separate_categories(self):
        tools = [
            {"name": "get_weather"},
            {"name": "set_weather_alert"},
        ]
        summary = generate_capability_summary(tools)
        assert "Weather" in summary
        assert "Weather Alert" in summary

    def test_single_tool_uses_singular_unit(self):
        tools = [{"name": "search"}]
        summary = generate_capability_summary(tools)
        assert "1 outil)" in summary

    def test_header_line_present(self):
        tools = [{"name": "store_memory"}]
        summary = generate_capability_summary(tools)
        assert summary.startswith("Capacités disponibles :")

    def test_mixed_domains(self):
        tools = [
            {"name": "create_event"},
            {"name": "store_memory"},
            {"name": "schedule_proaction"},
            {"name": "add_instruction"},
        ]
        summary = generate_capability_summary(tools)
        assert "Calendrier" in summary
        assert "Mémoire" in summary
        assert "Proactions" in summary
        assert "Directives" in summary
