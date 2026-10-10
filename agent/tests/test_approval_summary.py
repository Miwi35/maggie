"""The sentence a validation card shows (MAG-7, recette return).

« Supprime l'événement Test validation » must reach the user as a sentence naming the
event, not as `delete_event` and a ULID. The title is read once, when the action is held,
through a read tool; nothing may leak an identifier or a tool name, found or not.
"""

import json
from datetime import datetime
from unittest.mock import AsyncMock, patch

import pytest

from app.policy.summary import action_label, build_summary

ULID = "01KNZ8J6AGQ0K5D3WXYZ123456"


def reply(payload) -> str:
    return json.dumps(payload)


@pytest.fixture()
def mcp():
    with patch("app.policy.summary.mcp_client") as client:
        client.call_tool = AsyncMock(return_value=reply({"error": "Not found"}))
        yield client


class TestDeletingAnEvent:
    async def test_the_summary_names_the_event_and_when_it_is(self, mcp):
        mcp.call_tool.return_value = reply(
            {
                "event": {
                    "id": ULID,
                    "summary": "Test validation",
                    "allDay": False,
                    "startAt": f"{datetime.now().year}-10-08T10:00:00+02:00",
                    "endAt": f"{datetime.now().year}-10-08T11:00:00+02:00",
                }
            }
        )

        summary = await build_summary("delete_event", {"id": ULID}, "user-1")

        assert summary == "Supprimer l'événement « Test validation » — le 8 octobre à 10:00"
        mcp.call_tool.assert_awaited_once_with("get_event", {"id": ULID}, user_id="user-1")

    async def test_an_all_day_event_has_no_hour_and_a_year_that_is_not_this_one_is_said(self, mcp):
        mcp.call_tool.return_value = reply(
            {"event": {"summary": "Vacances", "allDay": True, "startAt": None, "startDate": "2099-12-24"}}
        )

        summary = await build_summary("delete_event", {"id": ULID}, "user-1")

        assert summary == "Supprimer l'événement « Vacances » — le 24 décembre 2099"

    async def test_an_event_that_cannot_be_found_falls_back_to_the_label_without_the_id(self, mcp):
        summary = await build_summary("delete_event", {"id": ULID}, "user-1")

        assert summary == "Supprimer l'événement"
        assert ULID not in summary


class TestDeletingARecipe:
    async def test_the_summary_names_the_recipe(self, mcp):
        mcp.call_tool.return_value = reply({"recipe": {"id": ULID, "name": "Couscous"}})

        summary = await build_summary("delete_recipe", {"recipeId": ULID}, "user-1")

        assert summary == "Supprimer la recette « Couscous »"
        mcp.call_tool.assert_awaited_once_with("get_recipe", {"recipeId": ULID}, user_id="user-1")

    async def test_a_recipe_that_cannot_be_found_falls_back_to_the_label(self, mcp):
        summary = await build_summary("delete_recipe", {"recipeId": ULID}, "user-1")

        assert summary == "Supprimer la recette"


class TestDeletingATask:
    async def test_the_title_is_found_among_the_users_tasks(self, mcp):
        mcp.call_tool.return_value = reply(
            {
                "tasks": [
                    {"id": "01OTHER", "title": "Appeler la banque"},
                    {"id": ULID.lower(), "title": "Payer la cantine"},
                ]
            }
        )

        summary = await build_summary("delete_task", {"id": ULID}, "user-1")

        assert summary == "Supprimer la tâche « Payer la cantine »"
        mcp.call_tool.assert_awaited_once_with("get_tasks", {"status": "all"}, user_id="user-1")

    async def test_a_task_missing_from_the_list_falls_back_to_the_label(self, mcp):
        mcp.call_tool.return_value = reply({"tasks": [{"id": "01OTHER", "title": "Appeler la banque"}]})

        summary = await build_summary("delete_task", {"id": ULID}, "user-1")

        assert summary == "Supprimer la tâche"


class TestDeletingASkill:
    async def test_the_skill_is_named_by_its_own_name_with_no_lookup(self, mcp):
        summary = await build_summary("delete_skill", {"name": "courses"}, "user-1")

        assert summary == "Supprimer la compétence « courses »"
        mcp.call_tool.assert_not_awaited()


class TestManageToolsWithTheDeleteAction:
    async def test_an_agenda_is_found_in_the_list_action(self, mcp):
        mcp.call_tool.return_value = reply({"agendas": [{"id": ULID, "name": "Famille"}], "count": 1})

        summary = await build_summary("manage_agendas", {"action": "delete", "agendaId": ULID}, "user-1")

        assert summary == "Supprimer l'agenda « Famille »"
        mcp.call_tool.assert_awaited_once_with("manage_agendas", {"action": "list"}, user_id="user-1")

    async def test_the_list_action_is_never_the_one_that_deletes(self, mcp):
        await build_summary("manage_stores", {"action": "delete", "storeId": ULID}, "user-1")

        for call in mcp.call_tool.await_args_list:
            assert call.args[1] != {"action": "delete", "storeId": ULID}
            assert call.args[1].get("action") == "list"

    async def test_a_tool_whose_list_needs_arguments_keeps_to_the_label(self, mcp):
        summary = await build_summary("manage_meals", {"action": "delete", "id": ULID}, "user-1")

        assert summary == "Supprimer le repas"
        mcp.call_tool.assert_not_awaited()

    async def test_a_manage_tool_the_table_does_not_know_still_has_a_sentence_without_its_name(self, mcp):
        summary = await build_summary("manage_widgets", {"action": "delete", "widgetId": ULID}, "user-1")

        assert summary == "Supprimer un élément"
        assert "widget" not in summary
        assert ULID not in summary


class TestWhenTheLookupFails:
    async def test_an_exception_never_blocks_the_card(self, mcp):
        mcp.call_tool.side_effect = RuntimeError("mcp down")

        summary = await build_summary("delete_event", {"id": ULID}, "user-1")

        assert summary == "Supprimer l'événement"

    async def test_an_answer_that_is_not_json_is_a_missing_title(self, mcp):
        mcp.call_tool.return_value = "<html>502</html>"

        summary = await build_summary("delete_recipe", {"recipeId": ULID}, "user-1")

        assert summary == "Supprimer la recette"

    async def test_a_call_with_no_id_is_not_looked_up(self, mcp):
        summary = await build_summary("delete_event", {}, "user-1")

        assert summary == "Supprimer l'événement"
        mcp.call_tool.assert_not_awaited()


class TestTheLabel:
    @pytest.mark.parametrize(
        ("tool", "arguments", "label"),
        [
            ("delete_event", {"id": ULID}, "Supprimer l'événement"),
            ("delete_recipe", {"recipeId": ULID}, "Supprimer la recette"),
            ("delete_task", {"id": ULID}, "Supprimer la tâche"),
            ("delete_skill", {"name": "courses"}, "Supprimer la compétence"),
            ("manage_loans", {"action": "delete", "loanId": ULID}, "Supprimer le prêt"),
            (
                "manage_recurring_operations",
                {"action": "delete", "recurringOperationId": ULID},
                "Supprimer l'opération récurrente",
            ),
            ("delete_unheard_of", {"id": ULID}, "Supprimer un élément"),
            ("send_email", {"to": "a@b.c"}, "Action en attente de validation"),
        ],
    )
    def test_it_says_the_action_with_no_tool_name_and_no_id(self, tool, arguments, label):
        assert action_label(tool, arguments) == label
