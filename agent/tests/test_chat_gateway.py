"""`LLMGateway.chat` — the non-streamed chat path, built around its thread (MAG-13).

This path used to route no context at all: it loaded the last fifty messages of every
thread, appended the user's message a second time on top of the copy the route had just
stored, and left both halves of the exchange outside every thread. The streamed path is
covered in `test_streaming.py`; what is here is the same promises on the path the mobile
app and the A2A bridge take.
"""

from contextlib import ExitStack, contextmanager
from unittest.mock import AsyncMock, MagicMock, patch

import anthropic
import httpx

from app.db.context_model import ContextStatus
from app.llm.gateway import LLMGateway

ROUTED = {"action": "matched", "id": "ctx-1", "label": "Courses", "status": "active", "summary": None}


def _gateway() -> LLMGateway:
    gateway = LLMGateway()
    gateway.client = MagicMock()
    gateway.personality = MagicMock()
    gateway.personality.get_system_prompt = AsyncMock(return_value="Tu es Maggie.")
    gateway.agent_memory = MagicMock()
    gateway.agent_memory.get_memory_context = AsyncMock(return_value="")
    gateway.tool_router = MagicMock()
    gateway.tool_router.get_tool_definitions = AsyncMock(return_value=[])
    return gateway


class _Run:
    """Captures what `run_tool_loop` was handed, which is the only observable of a history."""

    def __init__(self, answer: str = "C'est noté."):
        self.answer = answer
        self.messages: list[dict] | None = None

    async def __call__(self, _system, messages, _tools, **_kwargs):
        self.messages = messages
        return {"response": self.answer, "tool_calls": []}


@contextmanager
def _stubs(run, history, *, resolution=ROUTED, threads=()):
    """Every collaborator of `chat()` but the one under test."""
    with ExitStack() as stack:
        for patcher in (
            patch("app.llm.gateway.run_tool_loop", run),
            patch("app.llm.gateway.route_message", AsyncMock(return_value=resolution)),
            patch("app.llm.gateway.build_history", history),
            patch("app.llm.gateway.skill_index", MagicMock(get_skills_index=MagicMock(return_value=""))),
            patch("app.llm.gateway.behavior_directives_section", AsyncMock(return_value="")),
            patch("app.llm.contexts.context_repo", MagicMock(find_active=AsyncMock(return_value=list(threads)))),
        ):
            stack.enter_context(patcher)
        yield


class TestTheHistoryFollowsTheThread:
    async def test_it_is_built_for_the_routed_thread(self):
        seen: dict = {}

        async def history(user_id, *, context_id=None, pending_message=None, **_rest):
            seen.update(user_id=user_id, context_id=context_id, pending_message=pending_message)
            return [{"role": "user", "content": "Il me faut de la farine"}]

        run = _Run()
        with _stubs(run, history):
            result = await _gateway().chat("Il me faut de la farine", "user-1", exclude_message_id="msg-1")

        assert seen == {"user_id": "user-1", "context_id": "ctx-1", "pending_message": None}
        assert result["context_id"] == "ctx-1"

    async def test_the_caller_that_stored_the_message_does_not_send_it_twice(self):
        """The route persists the user's message, so the history holds it already."""
        run = _Run()

        async def history(_user_id, *, context_id=None, pending_message=None, **_rest):
            assert pending_message is None
            return [{"role": "user", "content": "Il me faut de la farine"}]

        with _stubs(run, history):
            await _gateway().chat("Il me faut de la farine", "user-1", exclude_message_id="msg-1")

        assert run.messages == [{"role": "user", "content": "Il me faut de la farine"}]

    async def test_a_caller_that_stored_nothing_hands_its_message_over(self):
        """The A2A bridge calls this with no stored message, so the text only exists here."""
        seen: dict = {}

        async def history(_user_id, *, context_id=None, pending_message=None, **_rest):
            seen["pending_message"] = pending_message
            return [{"role": "user", "content": pending_message}]

        run = _Run()
        with _stubs(run, history):
            await _gateway().chat("Quel est mon agenda ?", "a2a", source="a2a")

        assert seen["pending_message"] == "Quel est mon agenda ?"

    async def test_a_caller_that_stored_nothing_opens_no_thread(self):
        """A thread is where a message and its answer live, and A2A stores neither.

        Routing there would buy a fast-model call per peer request and a context row
        nobody ever writes in, kept awake by its own `touch()`.
        """
        route = AsyncMock()

        async def history(_user_id, *, context_id=None, pending_message=None, **_rest):
            return [{"role": "user", "content": pending_message}]

        run = _Run()
        with (
            patch("app.llm.gateway.run_tool_loop", run),
            patch("app.llm.gateway.route_message", route),
            patch("app.llm.gateway.build_history", history),
            patch("app.llm.gateway.skill_index", MagicMock(get_skills_index=MagicMock(return_value=""))),
            patch("app.llm.gateway.behavior_directives_section", AsyncMock(return_value="")),
            patch("app.llm.contexts.context_repo", MagicMock(find_active=AsyncMock(return_value=[]))),
        ):
            result = await _gateway().chat("Quel est mon agenda ?", "a2a", source="a2a")

        route.assert_not_called()
        assert result["context_id"] is None

    async def test_the_message_being_answered_is_handed_to_the_history(self):
        """So a tag that could not be written does not make Maggie read the question as a neighbour's."""
        seen: dict = {}

        async def history(_user_id, *, context_id=None, pending_message=None, **rest):
            seen.update(rest)
            return [{"role": "user", "content": "Il me faut de la farine"}]

        run = _Run()
        with _stubs(run, history):
            await _gateway().chat("Il me faut de la farine", "user-1", exclude_message_id="msg-1")

        assert seen["current_message_id"] == "msg-1"
        # And the floor under a history that would not load at all.
        assert seen["fallback_message"] == "Il me faut de la farine"

    async def test_a_routing_failure_leaves_the_global_window(self):
        seen: dict = {}

        async def history(_user_id, *, context_id=None, pending_message=None, **_rest):
            seen["context_id"] = context_id
            return [{"role": "user", "content": "Bonjour"}]

        run = _Run()
        with _stubs(run, history, resolution=None):
            result = await _gateway().chat("Bonjour", "user-1", exclude_message_id="msg-1")

        assert seen["context_id"] is None
        assert result["context_id"] is None
        assert result["response"] == "C'est noté."

    async def test_the_thread_is_named_in_the_system_prompt(self):
        """What the model is sent *is* that thread's messages, so the prompt says which (MAG-13)."""
        systems: list = []

        async def run(system, _messages, _tools, **_kwargs):
            systems.append(system)
            return {"response": "C'est noté.", "tool_calls": []}

        async def history(_user_id, *, context_id=None, pending_message=None, **_rest):
            return [{"role": "user", "content": "Il me faut de la farine"}]

        thread = MagicMock()
        thread.id = "ctx-1"
        thread.label = "Courses"
        thread.summary = None
        thread.status = ContextStatus.ACTIVE
        thread.tool_calls_log = []

        with (
            patch("app.llm.gateway.run_tool_loop", run),
            patch("app.llm.gateway.route_message", AsyncMock(return_value=ROUTED)),
            patch("app.llm.gateway.build_history", history),
            patch("app.llm.gateway.skill_index", MagicMock(get_skills_index=MagicMock(return_value=""))),
            patch("app.llm.gateway.behavior_directives_section", AsyncMock(return_value="")),
            patch("app.llm.contexts.context_repo", MagicMock(find_active=AsyncMock(return_value=[thread]))),
        ):
            await _gateway().chat("Il me faut de la farine", "user-1", exclude_message_id="msg-1")

        assert "Courses (active) ← fil en cours" in systems[0][1]["text"]


class TestWhenTheModelRefuses:
    @staticmethod
    def _api_error() -> anthropic.APIStatusError:
        request = httpx.Request("POST", "https://api.anthropic.com/v1/messages")
        response = httpx.Response(500, request=request, json={"error": {"message": "boom"}})
        return anthropic.APIStatusError("boom", response=response, body=None)

    async def test_an_api_error_still_lands_in_the_thread(self):
        """The user's message is already tagged, so an answer left out would be an orphan question."""

        async def history(_user_id, *, context_id=None, pending_message=None, **_rest):
            return [{"role": "user", "content": "Bonjour"}]

        with (
            patch("app.llm.gateway.run_tool_loop", AsyncMock(side_effect=self._api_error())),
            patch("app.llm.gateway.route_message", AsyncMock(return_value=ROUTED)),
            patch("app.llm.gateway.build_history", history),
            patch("app.llm.gateway.skill_index", MagicMock(get_skills_index=MagicMock(return_value=""))),
            patch("app.llm.gateway.behavior_directives_section", AsyncMock(return_value="")),
            patch("app.llm.contexts.context_repo", MagicMock(find_active=AsyncMock(return_value=[]))),
        ):
            result = await _gateway().chat("Bonjour", "user-1", exclude_message_id="msg-1")

        assert "AI service error" in result["response"]
        # The next summary would otherwise read half an exchange, and the error sentence is
        # what the user was actually shown — the streamed path stores its own in the thread too.
        assert result["context_id"] == "ctx-1"

    async def test_a_connection_error_says_so(self):
        async def history(_user_id, *, context_id=None, pending_message=None, **_rest):
            return [{"role": "user", "content": "Bonjour"}]

        request = httpx.Request("POST", "https://api.anthropic.com/v1/messages")
        with (
            patch(
                "app.llm.gateway.run_tool_loop",
                AsyncMock(side_effect=anthropic.APIConnectionError(request=request)),
            ),
            patch("app.llm.gateway.route_message", AsyncMock(return_value=ROUTED)),
            patch("app.llm.gateway.build_history", history),
            patch("app.llm.gateway.skill_index", MagicMock(get_skills_index=MagicMock(return_value=""))),
            patch("app.llm.gateway.behavior_directives_section", AsyncMock(return_value="")),
            patch("app.llm.contexts.context_repo", MagicMock(find_active=AsyncMock(return_value=[]))),
        ):
            result = await _gateway().chat("Bonjour", "user-1", exclude_message_id="msg-1")

        assert "Unable to reach" in result["response"]
        assert result["context_id"] == "ctx-1"

    async def test_no_client_configured_routes_nothing(self):
        route = AsyncMock()
        with patch("app.llm.gateway.route_message", route):
            gateway = _gateway()
            gateway.client = None
            result = await gateway.chat("Bonjour", "user-1")

        assert "not configured" in result["response"]
        route.assert_not_called()
