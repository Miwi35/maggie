import json
from unittest.mock import AsyncMock, patch

from app.db.instruction_model import Instruction
from app.db.memory_model import Memory
from app.llm.tools import ToolRouter

OWNER = "user-owner"
OTHER = "user-other"


async def _call(tool: str, arguments: dict, user_id: str) -> dict:
    return json.loads(await ToolRouter().call_tool(tool, arguments, user_id=user_id))


def _row(factory, model, row_id):
    with factory() as session:
        return session.get(model, row_id)


def _seed_memory(factory) -> str:
    with factory() as session:
        memory = Memory(user_id=OWNER, content="Allergique aux noix")
        session.add(memory)
        session.commit()
        return memory.id


def _seed_instruction(factory) -> str:
    with factory() as session:
        instruction = Instruction(user_id=OWNER, content="Résumé chaque matin")
        session.add(instruction)
        session.commit()
        return instruction.id


class TestMemoryIsolation:
    async def test_update_memory_of_another_user_is_refused(self, agent_db):
        memory_id = _seed_memory(agent_db)

        result = await _call("update_memory", {"memory_id": memory_id, "content": "piraté"}, OTHER)

        assert "error" in result
        assert _row(agent_db, Memory, memory_id).content == "Allergique aux noix"

    async def test_delete_memory_of_another_user_is_refused(self, agent_db):
        memory_id = _seed_memory(agent_db)

        result = await _call("delete_memory", {"memory_id": memory_id}, OTHER)

        assert "error" in result
        assert _row(agent_db, Memory, memory_id) is not None

    async def test_owner_can_update_and_delete_own_memory(self, agent_db):
        memory_id = _seed_memory(agent_db)

        updated = await _call("update_memory", {"memory_id": memory_id, "content": "Allergique aux noisettes"}, OWNER)
        assert updated["content"] == "Allergique aux noisettes"
        assert _row(agent_db, Memory, memory_id).content == "Allergique aux noisettes"

        deleted = await _call("delete_memory", {"memory_id": memory_id}, OWNER)
        assert deleted == {"deleted": True, "id": memory_id}
        assert _row(agent_db, Memory, memory_id) is None

    async def test_unknown_memory_and_foreign_memory_give_the_same_error(self, agent_db):
        memory_id = _seed_memory(agent_db)

        foreign = await _call("delete_memory", {"memory_id": memory_id}, OTHER)
        unknown = await _call("delete_memory", {"memory_id": "nope"}, OTHER)

        assert foreign["error"].replace(memory_id, "X") == unknown["error"].replace("nope", "X")


class TestInstructionIsolation:
    async def test_delete_instruction_of_another_user_is_refused(self, agent_db):
        instruction_id = _seed_instruction(agent_db)

        result = await _call("delete_instruction", {"instruction_id": instruction_id}, OTHER)

        assert "error" in result
        assert _row(agent_db, Instruction, instruction_id) is not None

    async def test_owner_can_delete_own_instruction(self, agent_db):
        instruction_id = _seed_instruction(agent_db)

        result = await _call("delete_instruction", {"instruction_id": instruction_id}, OWNER)

        assert result == {"deleted": True, "id": instruction_id}
        assert _row(agent_db, Instruction, instruction_id) is None

    async def test_delete_route_of_another_user_returns_404(self, agent_db, authed_client):
        instruction_id = _seed_instruction(agent_db)

        response = authed_client.delete(f"/instructions/{instruction_id}")

        assert response.status_code == 404
        assert _row(agent_db, Instruction, instruction_id) is not None

    async def test_repository_update_of_another_user_is_refused(self, agent_db):
        from app.db.instruction_repository import instruction_repo

        instruction_id = _seed_instruction(agent_db)

        assert await instruction_repo.update(OTHER, instruction_id, "piraté") is None
        assert _row(agent_db, Instruction, instruction_id).content == "Résumé chaque matin"

        updated = await instruction_repo.update(OWNER, instruction_id, "Résumé le soir")
        assert updated.content == "Résumé le soir"

    def test_delete_route_requires_auth(self, client):
        assert client.delete("/instructions/whatever").status_code in (401, 403)

    async def test_delete_publishes_mercure_only_for_the_owner(self, agent_db):
        instruction_id = _seed_instruction(agent_db)

        with patch("app.db.instruction_repository.instruction_repo.publisher.publish", new=AsyncMock()) as publish:
            await _call("delete_instruction", {"instruction_id": instruction_id}, OTHER)
            publish.assert_not_awaited()

            await _call("delete_instruction", {"instruction_id": instruction_id}, OWNER)
            publish.assert_awaited_once_with(f"/instructions/{OWNER}", {"id": instruction_id, "deleted": True})


class TestContextRouterIsolation:
    """The context id comes from the fast model: it is untrusted input like any client id (MAG-203)."""

    @staticmethod
    def _router(model_answer: str):
        from datetime import UTC, datetime
        from unittest.mock import MagicMock

        from app.llm.streaming import StreamingGateway

        def context(context_id: str, label: str, summary: str) -> MagicMock:
            ctx = MagicMock()
            ctx.id = context_id
            ctx.label = label
            ctx.summary = summary
            ctx.updated_at = datetime.now(UTC)
            return ctx

        contexts = {
            OWNER: [context("ctx-owner", "Courses", "Résumé du propriétaire")],
            OTHER: [context("ctx-neighbour", "Budget", "Résumé du voisin")],
        }

        created = MagicMock()
        created.id = "ctx-created"
        context_repo = MagicMock()
        context_repo.find_active = AsyncMock(side_effect=lambda user_id: contexts[user_id])
        context_repo.create = AsyncMock(return_value=created)
        context_repo.set_summary = AsyncMock()
        message_repo = MagicMock()
        message_repo.update_context = AsyncMock()

        response = MagicMock()
        response.content = [MagicMock(text=model_answer)]
        response.usage.input_tokens = 50
        response.usage.output_tokens = 10
        gateway = StreamingGateway()
        gateway.client = MagicMock()
        gateway.client.messages.create = AsyncMock(return_value=response)
        return gateway, context_repo, message_repo

    async def test_context_of_another_user_is_never_written_on_the_message(self, caplog):
        gateway, context_repo, message_repo = self._router('{"context_id": "ctx-neighbour"}')

        with (
            patch("app.llm.streaming.context_repo", context_repo),
            patch("app.llm.streaming.message_repo", message_repo),
            caplog.at_level("WARNING", logger="app.llm.streaming"),
        ):
            result = await gateway._resolve_context("Où en est mon budget ?", OWNER, "msg-1")

        assert result["action"] == "created"
        assert result["id"] == "ctx-created"
        message_repo.update_context.assert_awaited_once_with("msg-1", "ctx-created")
        context_repo.create.assert_awaited_once_with(OWNER, "Où en est mon budget ?")
        context_repo.set_summary.assert_not_called()
        assert "ctx-neighbour" in caplog.text

    async def test_unknown_context_id_opens_a_new_context_with_the_label_of_the_model(self):
        gateway, context_repo, message_repo = self._router('{"context_id": "made-up", "label": "Budget"}')

        with (
            patch("app.llm.streaming.context_repo", context_repo),
            patch("app.llm.streaming.message_repo", message_repo),
        ):
            result = await gateway._resolve_context("Où en est mon budget ?", OWNER, "msg-1")

        assert result["action"] == "created"
        context_repo.create.assert_awaited_once_with(OWNER, "Budget")
        message_repo.update_context.assert_awaited_once_with("msg-1", "ctx-created")

    async def test_own_context_is_still_matched(self):
        gateway, context_repo, message_repo = self._router('{"context_id": "ctx-owner"}')

        with (
            patch("app.llm.streaming.context_repo", context_repo),
            patch("app.llm.streaming.message_repo", message_repo),
        ):
            result = await gateway._resolve_context("Et du beurre", OWNER, "msg-1")

        assert result["action"] == "matched"
        assert result["id"] == "ctx-owner"
        assert result["summary"] == "Résumé du propriétaire"
        message_repo.update_context.assert_awaited_once_with("msg-1", "ctx-owner")
        context_repo.create.assert_not_called()
