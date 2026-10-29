"""Maggie reads the day's tasks late-first, offers one undated task, and writes only after a yes (MAG-409).

Whether a real model follows the prompt is judged on the real model; what this proves is the
run itself with the shipped scenarios: the tools it reaches and their arguments, the order of
the answer, and that the task is written once, by its own id, only on the second message.
"""

import json
from datetime import timedelta
from unittest.mock import patch

import pytest

from app.llm.fake import DEFAULT_FIXTURES_DIR, FakeAnthropicClient, expand_days
from app.llm.runner import run_tool_loop
from app.llm.tool_blocks import replay
from app.llm.tools import ToolRouter

USER = "user-1"
TASK_ID = "01K6ZQ8R1V3J9F0C2M4N5P6T7W"
OTHER_ID = "01K6ZQ8R1V3J9F0C2M4N5P6T7X"
OFFERED = [
    {"name": name, "input_schema": {}}
    for name in ("get_events_by_date", "get_tasks", "update_task", "add_grocery_item", "create_task")
]


class FakeTaskApi:
    def __init__(self):
        self.calls: list[tuple[str, dict]] = []

    async def ensure_connected(self) -> None:
        return None

    async def list_tools(self) -> list[dict]:
        return []

    async def call_tool(self, name: str, arguments: dict, user_id: str | None = None) -> str:
        self.calls.append((name, dict(arguments)))
        if name == "get_tasks" and arguments.get("view") == "today":
            tasks = [{"id": OTHER_ID, "title": "Renouveler le passeport", "dueDate": "2030-11-11", "overdueDays": 2}]
        elif name == "get_tasks":
            tasks = [{"id": TASK_ID, "title": "Ranger le garage", "dueDate": None, "overdueDays": None}]
        else:
            return json.dumps({"success": True})
        return json.dumps({"view": arguments.get("view"), "tasks": tasks, "count": len(tasks)})


@pytest.fixture()
def api():
    fake = FakeTaskApi()
    with patch("app.llm.tools.mcp_client", fake):
        yield fake


async def ask(messages: list[dict]) -> dict:
    with patch("app.llm.runner.record_llm_usage"):
        return await run_tool_loop(
            "Tu es Maggie.",
            messages,
            OFFERED,
            client=FakeAnthropicClient(fixtures_dir=DEFAULT_FIXTURES_DIR),
            tool_router=ToolRouter(),
            user_id=USER,
            model="fake",
            source="chat",
        )


def called(result: dict) -> list[str]:
    return [call["name"] for call in result["tool_calls"]]


class TestTheDay:
    async def test_it_reads_the_agenda_and_today_then_someday_and_writes_nothing(self, api):
        result = await ask([{"role": "user", "content": "Qu'est-ce que j'ai aujourd'hui ?"}])

        assert called(result) == ["get_events_by_date", "get_tasks", "get_tasks"]
        assert [args for name, args in api.calls if name == "get_tasks"] == [{"view": "today"}, {"view": "someday"}]
        assert "update_task" not in called(result)

    async def test_the_late_task_is_named_before_the_one_offered(self, api):
        result = await ask([{"role": "user", "content": "Qu'est-ce que j'ai aujourd'hui ?"}])

        answer = result["response"]
        assert answer.index("Renouveler le passeport") < answer.index("Ranger le garage")
        assert "en retard de 2 jours" in answer
        assert answer.count("Ranger le garage") == 1, "one task offered, not a list"


    async def test_a_refusal_line_naming_another_scenario_does_not_hijack_the_question(self, api):
        merged = "Refusé : supprimer l'événement Vidange\nQu'est-ce que j'ai aujourd'hui ?"

        result = await ask([{"role": "user", "content": merged}])

        assert called(result) == ["get_events_by_date", "get_tasks", "get_tasks"]


class TestTheYes:
    async def test_tomorrow_is_written_on_the_task_the_read_returned(self, api):
        first = await ask([{"role": "user", "content": "Qu'est-ce que j'ai aujourd'hui ?"}])
        history = [
            {"role": "user", "content": "Qu'est-ce que j'ai aujourd'hui ?"},
            *replay(first["blocks"]),
            {"role": "assistant", "content": first["response"]},
            {"role": "user", "content": "Oui, demain"},
        ]
        api.calls.clear()

        result = await ask(history)

        assert called(result) == ["update_task"]
        assert api.calls == [("update_task", {"id": TASK_ID, "dueDate": expand_days("${tomorrow}")})]
        assert "Ranger le garage" in result["response"]

    async def test_without_the_read_in_the_history_nothing_is_written(self, api):
        result = await ask([{"role": "user", "content": "Oui, demain"}])

        assert called(result) == []
        assert result["response"].startswith("[fake-llm]")


class TestRelativeDays:
    def test_today_and_tomorrow_are_paris_days(self):
        from datetime import date

        today = date.fromisoformat(expand_days("${today}"))

        assert expand_days("${tomorrow}") == (today + timedelta(days=1)).isoformat()

    def test_a_text_without_a_placeholder_is_untouched(self):
        assert expand_days("rien à changer $5") == "rien à changer $5"


class TestTaskReminder:
    """MAG-703: « rappelle-moi d'aller faire X » files a task due that day, never an unbacked promise."""

    async def test_the_errand_becomes_a_task_due_tomorrow_and_no_proaction(self, api):
        result = await ask(
            [{"role": "user", "content": "Ajoute jambon à la liste des courses demain et rappelle-moi d'aller faire les courses demain matin"}]
        )

        assert called(result) == ["add_grocery_item", "create_task"]
        assert ("create_task", {"title": "Faire les courses", "dueDate": expand_days("${tomorrow}")}) in api.calls
        assert "schedule_proaction" not in called(result)
        assert "tâches de demain" in result["response"]


class FakeShelfApi(FakeTaskApi):
    STORE_ID = "01K6ZQ8R1V3J9F0C2M4N5P6T7Y"
    PRODUCT_ID = "01K6ZQ8R1V3J9F0C2M4N5P6T7Z"

    async def call_tool(self, name: str, arguments: dict, user_id: str | None = None) -> str:
        self.calls.append((name, dict(arguments)))
        if name == "manage_stores":
            return json.dumps({"stores": [{"id": self.STORE_ID, "name": "Super U"}]})
        if name == "add_grocery_item":
            return json.dumps({"success": True, "itemId": OTHER_ID, "productId": self.PRODUCT_ID})
        return json.dumps({"success": True})


class TestMag714:
    async def test_the_shelf_is_set_on_the_product_after_reading_the_stores_and_without_a_question(self):
        api = FakeShelfApi()
        with patch("app.llm.tools.mcp_client", api):
            result = await ask([{"role": "user", "content": "Ajoute huile moteur, je l'achète à super U au rayon pile/voiture"}])

        assert called(result) == ["manage_stores", "add_grocery_item", "assign_product_store"]
        assert api.calls[-1] == (
            "assign_product_store",
            {"productId": api.PRODUCT_ID, "preferredStoreId": api.STORE_ID, "aisle": "pile/voiture"},
        )
        assert "?" not in result["response"]

    async def test_a_list_to_not_forget_is_a_checklist_due_the_friday_before(self, api):
        result = await ask(
            [{"role": "user", "content": "Pour mon weekend je dois ajouter une tâche de préparation avec des choses à ne pas oublier : chargeur, passeport, clés"}]
        )

        assert called(result) == ["create_task"]
        _, args = api.calls[0]
        assert args["description"].splitlines() == ["- [ ] Chargeur", "- [ ] Passeport", "- [ ] Clés"]
        assert args["dueDate"] == expand_days("${friday}")

    async def test_a_bare_done_calls_no_reminder_tool(self, api):
        result = await ask([{"role": "user", "content": "c'est fait"}])

        assert "answer_task_reminder" not in called(result)
        assert called(result) == ["get_tasks"]
