"""A reply meant for the ear is written for the ear (ADR-014, MAG-689)."""

import asyncio
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from app.api import routes
from app.llm.gateway import LLMGateway
from app.llm.streaming import StreamingGateway, answer_message_id
from app.llm.turns import TurnRunner
from app.memory.agent_memory import MemoryBlocks
from app.db.message_repository import message_repo
from app.personality.engine import PersonalityEngine

SPOKEN_MARKER = "ÉCRIRE POUR L'ORAL"
DRIVING_MARKER = "EN VOITURE"


def _gateway(cls):
    gateway = cls()
    gateway.personality = PersonalityEngine()
    gateway.personality.get_system_prompt = AsyncMock(return_value="Tu es Maggie.")
    gateway.agent_memory = MagicMock()
    gateway.agent_memory.blocks = AsyncMock(return_value=MemoryBlocks())
    return gateway


async def _prompt(gateway, module: str, **kwargs) -> list[dict]:
    with (
        patch("app.llm.contexts.context_repo") as contexts,
        patch(f"{module}.skill_index") as skills,
    ):
        contexts.find_active = AsyncMock(return_value=[])
        skills.get_skills_index.return_value = ""
        skills.skills_for_moment.return_value = ""
        skills.refresh = AsyncMock()
        return await gateway._build_system_prompt("user-1", **kwargs)


def _text(blocks: list[dict]) -> str:
    return "\n".join(block["text"] for block in blocks)


class TestTheSpokenInstruction:
    async def test_a_reply_for_the_voice_asks_for_a_spoken_text(self, agent_db):
        blocks = await _prompt(_gateway(StreamingGateway), "app.llm.streaming", spoken=True)

        prompt = _text(blocks)
        assert SPOKEN_MARKER in prompt
        assert "[warmly]" in prompt and "[softly]" in prompt and "[reassuring]" in prompt

    async def test_it_carries_the_tone_the_owner_chose(self, agent_db):
        prompt = _text(await _prompt(_gateway(StreamingGateway), "app.llm.streaming", spoken=True))

        for expected in ("Bonjour monsieur", "vouvoie", "je vous propose", "je me suis permis", "jamais familière"):
            assert expected in prompt

    async def test_a_written_reply_does_not_get_it(self, agent_db):
        blocks = await _prompt(_gateway(StreamingGateway), "app.llm.streaming")

        assert SPOKEN_MARKER not in _text(blocks)
        assert "[warmly]" not in _text(blocks)

    async def test_it_stays_out_of_the_cached_prefix(self, agent_db):
        """The written chat shares that prefix: it must be the same text with or without the voice."""
        gateway = _gateway(StreamingGateway)

        spoken = await _prompt(gateway, "app.llm.streaming", spoken=True)
        written = await _prompt(gateway, "app.llm.streaming")

        assert spoken[0] == written[0]
        assert SPOKEN_MARKER not in spoken[0]["text"]

    async def test_a_proaction_read_aloud_gets_it_too(self, agent_db):
        blocks = await _prompt(
            _gateway(LLMGateway), "app.llm.gateway", preamble="\n\nTu es en mode proaction.", spoken=True
        )

        assert SPOKEN_MARKER in _text(blocks)
        assert "Tu es en mode proaction." in _text(blocks)


class TestWhoAsksForIt:
    @pytest.mark.parametrize("spoken", [True, False])
    @patch("app.api.routes.message_repo")
    @patch("app.api.routes.llm_gateway")
    def test_the_chat_route_hands_the_flag_to_the_gateway(self, mock_gateway, mock_msg_repo, spoken, authed_client):
        seen = {}

        async def chat(message, user_id, **kwargs):
            seen.update(kwargs)
            return {"response": "Bonjour monsieur.", "tool_calls": []}

        mock_gateway.chat = chat
        mock_msg_repo.create = AsyncMock(return_value=MagicMock(id="m1", to_dict=lambda: {}))

        response = authed_client.post("/chat", json={"message": "Bonjour", "spoken": spoken})

        assert response.status_code == 200
        assert seen["spoken"] is spoken

    @patch("app.api.routes.message_repo")
    @patch("app.api.routes.llm_gateway")
    def test_a_written_message_is_not_spoken_by_default(self, mock_gateway, mock_msg_repo, authed_client):
        seen = {}

        async def chat(message, user_id, **kwargs):
            seen.update(kwargs)
            return {"response": "Bonjour.", "tool_calls": []}

        mock_gateway.chat = chat
        mock_msg_repo.create = AsyncMock(return_value=MagicMock(id="m1", to_dict=lambda: {}))

        authed_client.post("/chat", json={"message": "Bonjour"})

        assert seen["spoken"] is False

    @pytest.mark.parametrize("spoken", [True, False])
    async def test_the_streamed_turn_carries_it_to_the_gateway(self, chat_db, spoken):
        seen = {}

        class Gateway:
            async def chat_stream(self, message, user_id, user_msg_id, **kwargs):
                seen.update(kwargs)
                await message_repo.create(
                    user_id=user_id, role="assistant", content="Bonjour.", message_id=answer_message_id(user_msg_id)
                )
                yield {"type": "RUN_FINISHED", "runId": "run-1"}

        with patch.object(routes, "streaming_gateway", Gateway()), patch.object(routes, "turn_runner", TurnRunner()):
            response = await routes.chat_stream(routes.ChatRequest(message="Bonjour", spoken=spoken), user_id="user-1")
            [chunk async for chunk in response.body_iterator]
            await asyncio.sleep(0)

        assert seen["spoken"] is spoken


class TestTheDrivingInstruction:
    """The owner is in the car (MAG-684): short answers for the ear, only in that context."""

    async def test_in_the_car_the_prompt_asks_for_short_answers_for_the_ear(self, agent_db):
        prompt = _text(await _prompt(_gateway(StreamingGateway), "app.llm.streaming", in_car=True))

        assert DRIVING_MARKER in prompt
        for expected in ("courtes", "markdown", "liste", "tableau", "adresse web"):
            assert expected in prompt

    async def test_without_the_car_the_instruction_is_not_there(self, agent_db):
        prompt = _text(await _prompt(_gateway(StreamingGateway), "app.llm.streaming"))

        assert DRIVING_MARKER not in prompt

    async def test_it_stays_out_of_the_cached_prefix(self, agent_db):
        gateway = _gateway(StreamingGateway)

        driving = await _prompt(gateway, "app.llm.streaming", in_car=True)
        written = await _prompt(gateway, "app.llm.streaming")

        assert driving[0] == written[0]
        assert DRIVING_MARKER not in driving[0]["text"]

    async def test_the_non_streamed_gateway_adds_it_too(self, agent_db):
        gateway = _gateway(LLMGateway)

        assert DRIVING_MARKER in _text(await _prompt(gateway, "app.llm.gateway", in_car=True))
        assert DRIVING_MARKER not in _text(await _prompt(gateway, "app.llm.gateway"))

    @pytest.mark.parametrize("in_car", [True, False])
    @patch("app.api.routes.message_repo")
    @patch("app.api.routes.llm_gateway")
    def test_the_chat_route_hands_the_context_to_the_gateway(self, mock_gateway, mock_msg_repo, in_car, authed_client):
        seen = {}

        async def chat(message, user_id, **kwargs):
            seen.update(kwargs)
            return {"response": "Bonjour.", "tool_calls": []}

        mock_gateway.chat = chat
        mock_msg_repo.create = AsyncMock(return_value=MagicMock(id="m1", to_dict=lambda: {}))

        authed_client.post("/chat", json={"message": "Bonjour", "in_car": in_car})

        assert seen["in_car"] is in_car

    @pytest.mark.parametrize("in_car", [True, False])
    async def test_the_streamed_turn_carries_it_to_the_gateway(self, chat_db, in_car):
        seen = {}

        class Gateway:
            async def chat_stream(self, message, user_id, user_msg_id, **kwargs):
                seen.update(kwargs)
                await message_repo.create(
                    user_id=user_id, role="assistant", content="Bonjour.", message_id=answer_message_id(user_msg_id)
                )
                yield {"type": "RUN_FINISHED", "runId": "run-1"}

        with patch.object(routes, "streaming_gateway", Gateway()), patch.object(routes, "turn_runner", TurnRunner()):
            response = await routes.chat_stream(routes.ChatRequest(message="Bonjour", in_car=in_car), user_id="user-1")
            [chunk async for chunk in response.body_iterator]
            await asyncio.sleep(0)

        assert seen["in_car"] is in_car
