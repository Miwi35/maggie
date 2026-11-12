"""Maggie hesitates: `ask_owner`, the question block, and its answer in the thread (MAG-717)."""

import json
from unittest.mock import AsyncMock, patch

import pytest
from sqlalchemy import select

from app.db.models import Message
from app.llm.fake import DEFAULT_FIXTURES_DIR, FakeAnthropicClient
from app.llm.questions import asked_question, blocks_of, handle_ask_owner
from app.llm.runner import run_tool_loop
from app.llm.tools import ToolRouter

TASK_ID = "01K6ZQ8R1V3J9F0C2M4N5P6T7W"
LIST = "Une tâche avec la liste"
PLANNED = "La programmer vendredi à 18 h, avec un rappel"
OFFERED = [{"name": "create_task", "input_schema": {}}, {"name": "get_tasks", "input_schema": {}}]


class FakeApi:
    def __init__(self):
        self.calls: list[tuple[str, dict]] = []

    async def ensure_connected(self) -> None:
        return None

    async def list_tools(self) -> list[dict]:
        return []

    async def call_tool(self, name: str, arguments: dict, user_id: str | None = None) -> str:
        self.calls.append((name, dict(arguments)))
        if name == "create_task":
            return json.dumps({"success": True, "task": {"id": TASK_ID, "title": arguments["title"]}})
        return json.dumps({"success": True})


@pytest.fixture()
def api():
    fake = FakeApi()
    with patch("app.llm.tools.mcp_client", fake), patch("app.llm.questions.mcp_client", fake):
        yield fake


async def ask(text: str) -> dict:
    with patch("app.llm.runner.record_llm_usage"):
        return await run_tool_loop(
            "Tu es Maggie.",
            [{"role": "user", "content": text}],
            OFFERED,
            client=FakeAnthropicClient(fixtures_dir=DEFAULT_FIXTURES_DIR),
            tool_router=ToolRouter(),
            user_id="user-1",
            model="fake",
            source="chat",
        )


def _question(**overrides) -> dict:
    block = {"type": "question", "id": "Q" * 26, "text": "Que fait-on ?", "options": [LIST, PLANNED], "multiple": True}
    return {**block, "answered": None, **overrides}


class TestAskOwner:
    async def test_it_builds_the_question_block(self, api):
        raw = await handle_ask_owner({"text": " Que fait-on ? ", "options": [LIST, f" {PLANNED} ", LIST], "multiple": True}, "u")

        block = json.loads(raw)["question"]
        assert (block["type"], block["text"], block["options"], block["multiple"], block["answered"]) == (
            "question",
            "Que fait-on ?",
            [LIST, PLANNED],
            True,
            None,
        )
        assert len(block["id"]) == 26
        assert api.calls == []

    @pytest.mark.parametrize("arguments", [{"text": "?", "options": ["Seule"]}, {"text": " ", "options": ["A", "B"]}])
    async def test_it_refuses_a_question_without_two_options_or_a_text(self, arguments):
        assert "error" in json.loads(await handle_ask_owner(arguments, "u"))

    async def test_outside_the_chat_the_question_also_interrupts(self, api):
        raw = await handle_ask_owner({"text": "Que fait-on ?", "options": [LIST, PLANNED]}, "u", "proaction")

        block = json.loads(raw)["question"]
        name, arguments = api.calls[0]
        assert name == "manage_notifications"
        assert arguments["type"] == "question"
        assert arguments["interaction"] == {"kind": "choice", "options": [LIST, PLANNED], "multiple": False, "questionId": block["id"]}


class TestTheTurn:
    async def test_an_ambiguous_request_ends_on_the_question(self, api):
        result = await ask("Je dois penser à prendre le chargeur, le passeport pour ce weekend")

        question = asked_question(result["tool_calls"])
        assert question["options"] == [LIST, PLANNED]
        assert question["multiple"] is True
        assert result["response"] == question["text"]
        assert result["ui_blocks"] == [question]
        assert api.calls == []

    async def test_both_boxes_create_the_task_with_its_card(self, api):
        result = await ask(f"{LIST} · {PLANNED}")

        name, arguments = api.calls[0]
        assert name == "create_task"
        assert arguments["description"].startswith("- [ ] ")
        assert (arguments["scheduledMoment"], arguments["scheduledTime"]) == ("time", "18:00")
        assert "reminders" not in arguments
        assert arguments["dueDate"] == arguments["scheduledDate"]
        assert result["ui_blocks"] == [{"type": "task", "id": TASK_ID}]

    async def test_a_clear_request_creates_the_task_without_a_question(self, api):
        result = await ask("Ajoute une tâche pour appeler le plombier")

        assert [name for name, _ in api.calls] == ["create_task"]
        assert asked_question(result["tool_calls"]) is None
        assert result["ui_blocks"] == [{"type": "task", "id": TASK_ID}]

    def test_a_failed_creation_leaves_no_card(self):
        calls = [{"name": "create_task", "input": {}, "result": json.dumps({"error": "boom"})}]

        assert blocks_of(calls) == []


@pytest.fixture()
def stored(chat_db):
    def add(message_id="m1", user_id="test-user", blocks=None):
        with chat_db.session()._session as session:
            session.add(
                Message(
                    id=message_id,
                    user_id=user_id,
                    role="assistant",
                    content="Que fait-on ?",
                    context_id="ctx-1",
                    ui_blocks=blocks or [_question()],
                )
            )
            session.commit()

    return add


def _messages(chat_db) -> list[Message]:
    with chat_db.session()._session as session:
        return list(session.execute(select(Message).order_by(Message.created_at)).scalars())


@pytest.fixture()
def gateway():
    with patch("app.api.routes.llm_gateway") as mock:
        mock.chat = AsyncMock(return_value={"response": "C'est fait.", "tool_calls": []})
        yield mock


URL = f"/questions/{'Q' * 26}/answer"


class TestTheAnswer:
    def test_it_requires_auth(self, client):
        assert client.post(URL, json={"choices": [LIST]}).status_code in (401, 403)

    def test_an_unknown_question_is_a_404(self, authed_client, chat_db, gateway):
        assert authed_client.post(URL, json={"choices": [LIST]}).status_code == 404

    def test_another_users_question_is_a_404(self, authed_client, chat_db, stored, gateway):
        stored(user_id="someone-else")

        assert authed_client.post(URL, json={"choices": [LIST]}).status_code == 404

    @pytest.mark.parametrize(
        "choices", [[], ["Autre chose"], [LIST, LIST], [LIST, "Autre chose"]]
    )
    def test_an_unknown_or_repeated_choice_is_a_400(self, authed_client, chat_db, stored, gateway, choices):
        stored()

        assert authed_client.post(URL, json={"choices": choices}).status_code in (400, 422)
        gateway.chat.assert_not_awaited()

    def test_several_choices_for_a_single_choice_question_is_a_400(self, authed_client, chat_db, stored, gateway):
        stored(blocks=[_question(multiple=False)])

        assert authed_client.post(URL, json={"choices": [LIST, PLANNED]}).status_code == 400

    def test_the_answer_is_written_in_the_thread_and_the_turn_resumes(self, authed_client, chat_db, stored, gateway):
        stored()

        response = authed_client.post(URL, json={"choices": [PLANNED, LIST]})

        assert response.status_code == 200
        assert response.json()["answered"] == [LIST, PLANNED]
        messages = _messages(chat_db)
        assert [(m.role, m.content) for m in messages] == [
            ("assistant", "Que fait-on ?"),
            ("user", f"{LIST} · {PLANNED}"),
            ("assistant", "C'est fait."),
        ]
        assert messages[0].ui_blocks[0]["answered"] == [LIST, PLANNED]
        assert messages[1].context_id == "ctx-1"
        assert gateway.chat.await_args.args[0] == f"{LIST} · {PLANNED}"
        updated = chat_db.chat_published.await_args_list[0].args[1]
        assert updated["blocks"][0]["answered"] == [LIST, PLANNED]

    def test_a_question_already_answered_is_a_409(self, authed_client, chat_db, stored, gateway):
        stored(blocks=[_question(answered=[LIST])])

        response = authed_client.post(URL, json={"choices": [PLANNED]})

        assert response.status_code == 409
        gateway.chat.assert_not_awaited()


def test_ask_owner_is_for_any_hesitation_that_fits_options():
    from app.llm.questions import ASK_OWNER_TOOL

    assert "any hesitation" in ASK_OWNER_TOOL["description"]
    assert "irreversible" in ASK_OWNER_TOOL["description"]
