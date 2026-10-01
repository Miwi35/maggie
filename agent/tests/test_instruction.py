from datetime import UTC, datetime
from unittest.mock import AsyncMock, MagicMock, patch

from app.db.instruction_model import Instruction, InstructionKind
from app.db.instruction_repository import instruction_repo


class TestInstructionModel:
    def test_instruction_to_dict(self):
        """Instruction.to_dict() should serialize correctly."""
        instruction = Instruction(
            id="abc123",
            user_id="user-1",
            content="Envoie-moi un résumé chaque matin à 9h",
            kind=InstructionKind.PLANNING,
            created_at=datetime(2026, 2, 20, 9, 0, tzinfo=UTC),
            updated_at=datetime(2026, 2, 20, 9, 0, tzinfo=UTC),
        )

        d = instruction.to_dict()
        assert d["id"] == "abc123"
        assert d["userId"] == "user-1"
        assert d["content"] == "Envoie-moi un résumé chaque matin à 9h"
        assert d["kind"] == "planning"
        assert d["createdAt"] == "2026-02-20T09:00:00+00:00"
        assert d["updatedAt"] == "2026-02-20T09:00:00+00:00"

    def test_a_behaviour_preference_serializes_its_kind(self):
        d = Instruction(id="abc123", user_id="user-1", content="Tutoie-moi", kind=InstructionKind.BEHAVIOR).to_dict()

        assert d["kind"] == "behavior"

    def test_to_dict_says_planning_when_no_kind_is_set(self):
        """So a row the ORM has not filled in yet serialises like the backfill reads (MAG-22)."""
        d = Instruction(id="abc123", user_id="user-1", content="Rule").to_dict()

        assert d["kind"] == "planning"


class TestInstructionRepository:
    """Real SQL on the `agent_db` fixture: the `kind` filter is the whole point of the column."""

    async def test_store_defaults_to_a_planning_rule(self, agent_db):
        stored = await instruction_repo.store("user-1", "Digest du matin")

        assert InstructionKind(stored.kind) is InstructionKind.PLANNING

    async def test_find_by_user_filters_on_the_kind(self, agent_db):
        await instruction_repo.store("user-1", "Digest du matin", kind=InstructionKind.PLANNING)
        await instruction_repo.store("user-1", "Tutoie-moi", kind=InstructionKind.BEHAVIOR)

        behaviour = await instruction_repo.find_by_user("user-1", kind=InstructionKind.BEHAVIOR)
        planning = await instruction_repo.find_by_user("user-1", kind=InstructionKind.PLANNING)

        assert [i.content for i in behaviour] == ["Tutoie-moi"]
        assert [i.content for i in planning] == ["Digest du matin"]

    async def test_no_kind_returns_both(self, agent_db):
        await instruction_repo.store("user-1", "Digest du matin", kind=InstructionKind.PLANNING)
        await instruction_repo.store("user-1", "Tutoie-moi", kind=InstructionKind.BEHAVIOR)

        assert len(await instruction_repo.find_by_user("user-1")) == 2

    async def test_find_user_ids_filters_on_the_kind(self, agent_db):
        await instruction_repo.store("planner", "Digest du matin", kind=InstructionKind.PLANNING)
        await instruction_repo.store("talker", "Tutoie-moi", kind=InstructionKind.BEHAVIOR)

        assert await instruction_repo.find_user_ids(kind=InstructionKind.PLANNING) == ["planner"]
        assert await instruction_repo.find_user_ids(kind=InstructionKind.BEHAVIOR) == ["talker"]
        assert await instruction_repo.find_user_ids() == ["planner", "talker"]


class TestInstructionTools:
    @patch("app.llm.tools.instruction_repo")
    async def test_add_instruction(self, mock_repo):
        """add_instruction tool should store via instruction_repo."""
        from app.llm.tools import ToolRouter

        mock_instruction = MagicMock()
        mock_instruction.to_dict.return_value = {
            "id": "inst123",
            "content": "Morning summary at 9",
        }
        mock_repo.store = AsyncMock(return_value=mock_instruction)

        router = ToolRouter()
        result = await router.call_tool(
            "add_instruction",
            {"content": "Morning summary at 9", "kind": "planning"},
            user_id="test-user",
        )

        assert "inst123" in result
        mock_repo.store.assert_awaited_once_with(
            "test-user", "Morning summary at 9", kind=InstructionKind.PLANNING
        )

    @patch("app.llm.tools.instruction_repo")
    async def test_add_a_behaviour_preference(self, mock_repo):
        """What the user says in the chat — « tutoie-moi » — has to land as a behaviour preference."""
        from app.llm.tools import ToolRouter

        mock_repo.store = AsyncMock(return_value=MagicMock(to_dict=lambda: {"id": "inst9"}))

        await ToolRouter().call_tool(
            "add_instruction",
            {"content": "Tutoie-moi", "kind": "behavior"},
            user_id="test-user",
        )

        mock_repo.store.assert_awaited_once_with("test-user", "Tutoie-moi", kind=InstructionKind.BEHAVIOR)

    @patch("app.llm.tools.instruction_repo")
    async def test_add_instruction_forgives_the_casing_of_the_kind(self, mock_repo):
        """A model answering "Behavior" meant the right thing; a round trip to say so is waste."""
        from app.llm.tools import ToolRouter

        mock_repo.store = AsyncMock(return_value=MagicMock(to_dict=lambda: {"id": "inst9"}))

        await ToolRouter().call_tool(
            "add_instruction",
            {"content": "Tutoie-moi", "kind": " Behavior "},
            user_id="test-user",
        )

        mock_repo.store.assert_awaited_once_with("test-user", "Tutoie-moi", kind=InstructionKind.BEHAVIOR)

    @patch("app.llm.tools.instruction_repo")
    async def test_add_instruction_refuses_an_unknown_kind(self, mock_repo):
        """Guessing here would file a behaviour preference where nobody reads it."""
        from app.llm.tools import ToolRouter

        mock_repo.store = AsyncMock()

        result = await ToolRouter().call_tool(
            "add_instruction",
            {"content": "Tutoie-moi", "kind": "tone"},
            user_id="test-user",
        )

        assert "error" in result
        mock_repo.store.assert_not_awaited()

    @patch("app.llm.tools.instruction_repo")
    async def test_add_instruction_refuses_a_missing_kind(self, mock_repo):
        from app.llm.tools import ToolRouter

        mock_repo.store = AsyncMock()

        result = await ToolRouter().call_tool("add_instruction", {"content": "Tutoie-moi"}, user_id="test-user")

        assert "error" in result
        mock_repo.store.assert_not_awaited()

    @patch("app.llm.tools.instruction_repo")
    async def test_list_instructions(self, mock_repo):
        """list_instructions tool should return all instructions."""
        from app.llm.tools import ToolRouter

        mock_inst = MagicMock()
        mock_inst.to_dict.return_value = {"id": "inst1", "content": "Rule 1"}
        mock_repo.find_by_user = AsyncMock(return_value=[mock_inst])

        router = ToolRouter()
        result = await router.call_tool(
            "list_instructions",
            {},
            user_id="test-user",
        )

        assert "Rule 1" in result
        mock_repo.find_by_user.assert_awaited_once_with("test-user", kind=None)

    @patch("app.llm.tools.instruction_repo")
    async def test_list_instructions_of_one_kind(self, mock_repo):
        """What the daily planning prompt asks for: the planning rules, not the tone."""
        from app.llm.tools import ToolRouter

        mock_repo.find_by_user = AsyncMock(return_value=[])

        await ToolRouter().call_tool("list_instructions", {"kind": "planning"}, user_id="test-user")

        mock_repo.find_by_user.assert_awaited_once_with("test-user", kind=InstructionKind.PLANNING)

    @patch("app.llm.tools.instruction_repo")
    async def test_list_instructions_refuses_an_unknown_kind(self, mock_repo):
        from app.llm.tools import ToolRouter

        mock_repo.find_by_user = AsyncMock()

        result = await ToolRouter().call_tool("list_instructions", {"kind": "tone"}, user_id="test-user")

        assert "error" in result
        mock_repo.find_by_user.assert_not_awaited()

    @patch("app.llm.tools.instruction_repo")
    async def test_delete_instruction(self, mock_repo):
        """delete_instruction tool should remove via instruction_repo."""
        from app.llm.tools import ToolRouter

        mock_repo.delete = AsyncMock(return_value=True)

        router = ToolRouter()
        result = await router.call_tool(
            "delete_instruction",
            {"instruction_id": "inst123"},
            user_id="test-user",
        )

        assert "deleted" in result
        mock_repo.delete.assert_awaited_once_with("test-user", "inst123")


class TestInstructionRoutes:
    def test_instructions_endpoint_requires_auth(self, client):
        """GET /instructions without auth returns 401 or 403."""
        response = client.get("/instructions")
        assert response.status_code in (401, 403)

    @patch("app.api.routes.instruction_repo")
    def test_list_instructions_with_auth(self, mock_repo, authed_client):
        """GET /instructions with valid auth returns instructions list."""
        mock_inst = MagicMock()
        mock_inst.to_dict.return_value = {
            "id": "inst123",
            "userId": "test-user",
            "content": "Morning summary",
        }
        mock_repo.find_by_user = AsyncMock(return_value=[mock_inst])

        response = authed_client.get("/instructions")

        assert response.status_code == 200
        data = response.json()
        assert len(data) == 1
        assert data[0]["id"] == "inst123"

    @patch("app.api.routes.instruction_repo")
    def test_list_instructions_of_one_kind(self, mock_repo, authed_client):
        """GET /instructions?kind=behavior narrows to the behaviour preferences."""
        mock_repo.find_by_user = AsyncMock(return_value=[])

        response = authed_client.get("/instructions?kind=behavior")

        assert response.status_code == 200
        mock_repo.find_by_user.assert_awaited_once_with("test-user", kind=InstructionKind.BEHAVIOR)

    def test_list_instructions_refuses_an_unknown_kind(self, authed_client):
        assert authed_client.get("/instructions?kind=tone").status_code == 422

    @patch("app.api.routes.instruction_repo")
    def test_create_instruction_with_auth(self, mock_repo, authed_client):
        """POST /instructions with valid auth creates an instruction."""
        mock_inst = MagicMock()
        mock_inst.to_dict.return_value = {
            "id": "inst456",
            "userId": "test-user",
            "content": "No notifications after 9pm",
        }
        mock_repo.store = AsyncMock(return_value=mock_inst)

        response = authed_client.post("/instructions", json={"content": "No notifications after 9pm"})

        assert response.status_code == 201
        assert response.json()["id"] == "inst456"
        # No kind in the body: what the admin's form sent before MAG-22, and a planning rule.
        mock_repo.store.assert_awaited_once_with(
            "test-user", "No notifications after 9pm", kind=InstructionKind.PLANNING
        )

    @patch("app.api.routes.instruction_repo")
    def test_create_a_behaviour_preference(self, mock_repo, authed_client):
        mock_repo.store = AsyncMock(return_value=MagicMock(to_dict=lambda: {"id": "inst7"}))

        response = authed_client.post("/instructions", json={"content": "Tutoie-moi", "kind": "behavior"})

        assert response.status_code == 201
        mock_repo.store.assert_awaited_once_with("test-user", "Tutoie-moi", kind=InstructionKind.BEHAVIOR)

    def test_create_instruction_refuses_an_unknown_kind(self, authed_client):
        response = authed_client.post("/instructions", json={"content": "Tutoie-moi", "kind": "tone"})

        assert response.status_code == 422

    @patch("app.api.routes.instruction_repo")
    def test_delete_instruction_with_auth(self, mock_repo, authed_client):
        """DELETE /instructions/{id} with valid auth deletes the instruction."""
        mock_repo.delete = AsyncMock(return_value=True)

        response = authed_client.delete("/instructions/inst123")

        assert response.status_code == 200
        assert response.json()["deleted"] is True

    @patch("app.api.routes.instruction_repo")
    def test_delete_instruction_not_found(self, mock_repo, authed_client):
        """DELETE /instructions/{id} returns 404 if not found."""
        mock_repo.delete = AsyncMock(return_value=False)

        response = authed_client.delete("/instructions/unknown")

        assert response.status_code == 404
