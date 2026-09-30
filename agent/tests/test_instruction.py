from datetime import UTC, datetime
from unittest.mock import AsyncMock, MagicMock, patch

from app.db.instruction_model import Instruction


class TestInstructionModel:
    def test_instruction_to_dict(self):
        """Instruction.to_dict() should serialize correctly."""
        instruction = Instruction(
            id="abc123",
            user_id="user-1",
            content="Envoie-moi un résumé chaque matin à 9h",
            created_at=datetime(2026, 2, 20, 9, 0, tzinfo=UTC),
            updated_at=datetime(2026, 2, 20, 9, 0, tzinfo=UTC),
        )

        d = instruction.to_dict()
        assert d["id"] == "abc123"
        assert d["userId"] == "user-1"
        assert d["content"] == "Envoie-moi un résumé chaque matin à 9h"
        assert d["createdAt"] == "2026-02-20T09:00:00+00:00"
        assert d["updatedAt"] == "2026-02-20T09:00:00+00:00"


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
            {"content": "Morning summary at 9"},
            user_id="test-user",
        )

        assert "inst123" in result
        mock_repo.store.assert_awaited_once_with("test-user", "Morning summary at 9")

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
        mock_repo.find_by_user.assert_awaited_once_with("test-user")

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
