import json
from datetime import UTC, datetime, timedelta
from unittest.mock import AsyncMock, MagicMock, patch

from app.db.models import Message
from app.db.pending_action_model import PendingAction, PendingActionStatus

SCREENSHOT = "/9j/4AAQSkZJRgABAQ=="


class TestRoutes:
    def test_health_endpoint(self, client):
        """GET /health returns status ok and service name."""
        response = client.get("/health")

        assert response.status_code == 200

        data = response.json()
        assert data["status"] == "ok"
        assert data["service"] == "maggie-agent-hub"

    def test_chat_endpoint_requires_auth(self, client):
        """POST /chat without auth returns 401."""
        response = client.post("/chat", json={"message": "Hello"})
        assert response.status_code in (401, 403)

    @patch("app.api.routes.message_repo")
    @patch("app.api.routes.llm_gateway")
    def test_chat_endpoint_without_api_key(self, mock_gateway, mock_msg_repo, authed_client):
        """POST /chat without ANTHROPIC_API_KEY returns a not-configured message."""
        mock_gateway.client = None

        async def mock_chat(message, user_id, *, exclude_message_id=None, **_kwargs):
            assert exclude_message_id == "test"
            return {"response": "AI service is not configured.", "tool_calls": []}

        mock_gateway.chat = mock_chat

        fake_msg = Message(id="test", user_id="test-user", role="user", content="Hello")
        mock_msg_repo.create = AsyncMock(return_value=fake_msg)

        response = authed_client.post(
            "/chat",
            json={"message": "Hello"},
        )

        assert response.status_code == 200

        data = response.json()
        assert "not configured" in data["response"].lower()
        assert data["tool_calls"] == []

    @patch("app.api.routes.context_summarizer")
    @patch("app.api.routes.message_repo")
    @patch("app.api.routes.llm_gateway")
    def test_chat_stores_the_answer_in_the_routed_thread(
        self, mock_gateway, mock_msg_repo, mock_summarizer, authed_client
    ):
        """Before MAG-13 both halves of this exchange lived outside every thread."""
        mock_gateway.chat = AsyncMock(
            return_value={"response": "C'est noté.", "tool_calls": [], "context_id": "ctx-1"}
        )
        stored = Message(id="msg-1", user_id="test-user", role="user", content="Hello")
        mock_msg_repo.create = AsyncMock(return_value=stored)
        mock_summarizer.maybe_summarize = AsyncMock(return_value=None)

        response = authed_client.post("/chat", json={"message": "Il me faut de la farine"})

        assert response.status_code == 200
        mock_msg_repo.create.assert_awaited_with(
            user_id="test-user", role="assistant", content="C'est noté.", context_id="ctx-1", blocks=None
        )
        # And the thread is re-summarized, so the reply to it is routed against a summary
        # that knows about this exchange — what `POST /agent/proaction` already does.
        mock_summarizer.maybe_summarize.assert_awaited_once_with("ctx-1")

    @patch("app.api.routes.context_summarizer")
    @patch("app.api.routes.message_repo")
    @patch("app.api.routes.llm_gateway")
    def test_chat_without_a_thread_summarizes_nothing(
        self, mock_gateway, mock_msg_repo, mock_summarizer, authed_client
    ):
        mock_gateway.chat = AsyncMock(return_value={"response": "C'est noté.", "tool_calls": []})
        stored = Message(id="msg-1", user_id="test-user", role="user", content="Hello")
        mock_msg_repo.create = AsyncMock(return_value=stored)
        mock_summarizer.maybe_summarize = AsyncMock()

        assert authed_client.post("/chat", json={"message": "Bonjour"}).status_code == 200

        mock_msg_repo.create.assert_awaited_with(
            user_id="test-user", role="assistant", content="C'est noté.", context_id=None, blocks=None
        )
        mock_summarizer.maybe_summarize.assert_not_awaited()

    @patch("app.api.routes.context_summarizer")
    @patch("app.api.routes.message_repo")
    @patch("app.api.routes.llm_gateway")
    def test_a_turn_the_model_never_answered_is_not_summarized(
        self, mock_gateway, mock_msg_repo, mock_summarizer, authed_client
    ):
        """The summary would be a second doomed call, with the user still waiting on this one."""
        mock_gateway.chat = AsyncMock(
            return_value={
                "response": "Désolé, une erreur est survenue. Réessaie.",
                "tool_calls": [],
                "context_id": "ctx-1",
                "error": True,
            }
        )
        stored = Message(id="msg-1", user_id="test-user", role="user", content="Hello")
        mock_msg_repo.create = AsyncMock(return_value=stored)
        mock_summarizer.maybe_summarize = AsyncMock()

        assert authed_client.post("/chat", json={"message": "Bonjour"}).status_code == 200

        # Still stored in the thread — the question is already tagged, and an answer left
        # out would be an orphan the next summary reads as half an exchange.
        mock_msg_repo.create.assert_awaited_with(
            user_id="test-user",
            role="assistant",
            content="Désolé, une erreur est survenue. Réessaie.",
            context_id="ctx-1",
            blocks=None,
        )
        mock_summarizer.maybe_summarize.assert_not_awaited()

    def test_an_empty_message_is_refused(self, authed_client):
        """A 422 naming the field beats « Désolé, une erreur est survenue » (MAG-13).

        An empty message has nothing to route and nothing to answer, and the model refuses
        a conversation whose only turn is an empty string — so neither chat route should
        reach it.
        """
        assert authed_client.post("/chat", json={"message": ""}).status_code == 422
        assert authed_client.post("/chat/stream", json={"message": ""}).status_code == 422

    @patch("app.api.routes.context_summarizer")
    @patch("app.api.routes.message_repo")
    @patch("app.api.routes.llm_gateway")
    def test_the_screen_context_is_not_stored_in_the_question(
        self, mock_gateway, mock_msg_repo, mock_summarizer, authed_client
    ):
        """Recette refused the opposite: the shop page came back as the user's own bubble.

        The stored message is what `GET /messages`, the Mercure echo and the web chat all
        read, so the block has to stay out of it — and reach the model another way.
        """
        mock_gateway.chat = AsyncMock(return_value={"response": "C'est un concert.", "tool_calls": []})
        mock_msg_repo.create = AsyncMock(
            return_value=Message(id="msg-1", user_id="test-user", role="user", content="x")
        )
        mock_summarizer.maybe_summarize = AsyncMock()
        block = "[Contexte de l'écran]\nPage : https://dice.fm/event/x?utm_source=spam"

        response = authed_client.post(
            "/chat",
            json={"message": "De quoi parle cette page ?", "screen_context": block},
        )

        assert response.status_code == 200
        mock_msg_repo.create.assert_any_await(
            user_id="test-user", role="user", content="De quoi parle cette page ?", has_image=False
        )
        assert mock_gateway.chat.await_args.kwargs["screen_context"] == block
        assert mock_gateway.chat.await_args.args[0] == "De quoi parle cette page ?"

    @patch("app.api.routes.context_summarizer")
    @patch("app.api.routes.message_repo")
    @patch("app.api.routes.llm_gateway")
    def test_a_client_glueing_the_block_to_the_message_still_stores_the_question(
        self, mock_gateway, mock_msg_repo, mock_summarizer, authed_client
    ):
        """The installed app sends one string, and its copy must not dirty the history."""
        mock_gateway.chat = AsyncMock(return_value={"response": "Ok.", "tool_calls": []})
        mock_msg_repo.create = AsyncMock(
            return_value=Message(id="msg-1", user_id="test-user", role="user", content="x")
        )
        mock_summarizer.maybe_summarize = AsyncMock()
        block = "[Contexte de l'écran]\nApplication : Chrome (com.android.chrome)"

        assert (
            authed_client.post("/chat", json={"message": f"{block}\n\najoute ça à mon agenda"}).status_code
            == 200
        )

        mock_msg_repo.create.assert_any_await(user_id="test-user", role="user", content="ajoute ça à mon agenda", has_image=False)
        assert mock_gateway.chat.await_args.kwargs["screen_context"] == block

    @patch("app.api.routes.message_repo")
    @patch("app.api.routes.streaming_gateway")
    def test_the_streamed_route_stores_the_question_alone_too(
        self, mock_streaming, mock_msg_repo, authed_client
    ):
        """The overlay streams, so this is the route the refused bubble came from."""

        async def no_events(*_args, **_kwargs):
            if False:
                yield {}

        mock_streaming.chat_stream = MagicMock(side_effect=no_events)
        mock_msg_repo.create = AsyncMock(
            return_value=Message(id="msg-1", user_id="test-user", role="user", content="x")
        )
        block = "[Contexte de l'écran]\nPage : https://dice.fm/event/x?utm_source=spam"

        with authed_client.stream(
            "POST", "/chat/stream", json={"message": "c'est quoi ce produit ?", "screen_context": block}
        ) as response:
            assert response.status_code == 200
            response.read()

        # Published as it is stored, so the Mercure echo the other clients draw is clean too.
        stored = mock_msg_repo.create.await_args.kwargs
        assert (stored["role"], stored["content"]) == ("user", "c'est quoi ce produit ?")
        assert stored["has_image"] is False
        assert mock_streaming.chat_stream.call_args.kwargs["screen_context"] == block

    @patch("app.api.routes.context_summarizer")
    @patch("app.api.routes.message_repo")
    @patch("app.api.routes.llm_gateway")
    def test_a_screenshot_reaches_the_model_and_is_not_stored(
        self, mock_gateway, mock_msg_repo, mock_summarizer, authed_client
    ):
        """The picture lives for the turn (MAG-214): the stored row only says one was there."""
        mock_gateway.chat = AsyncMock(return_value={"response": "Une cafetière.", "tool_calls": []})
        stored = Message(id="msg-1", user_id="test-user", role="user", content="c'est quoi ?", has_image=True)
        mock_msg_repo.create = AsyncMock(return_value=stored)
        mock_summarizer.maybe_summarize = AsyncMock()

        response = authed_client.post(
            "/chat", json={"message": "c'est quoi ?", "image": {"media_type": "image/jpeg", "data": SCREENSHOT}}
        )

        assert response.status_code == 200
        mock_msg_repo.create.assert_any_await(user_id="test-user", role="user", content="c'est quoi ?", has_image=True)
        image = mock_gateway.chat.await_args.kwargs["image"]
        assert (image.media_type, image.data) == ("image/jpeg", SCREENSHOT)
        assert response.json()["messages"][0]["hasImage"] is True

    @patch("app.api.routes.message_repo")
    @patch("app.api.routes.streaming_gateway")
    def test_the_streamed_route_takes_the_screenshot_too(self, mock_streaming, mock_msg_repo, authed_client):
        async def no_events(*_args, **_kwargs):
            if False:
                yield {}

        mock_streaming.chat_stream = MagicMock(side_effect=no_events)
        mock_msg_repo.create = AsyncMock(
            return_value=Message(id="msg-1", user_id="test-user", role="user", content="x")
        )
        body = {"message": "c'est quoi ?", "image": {"media_type": "image/png", "data": SCREENSHOT}}

        with authed_client.stream("POST", "/chat/stream", json=body) as response:
            assert response.status_code == 200
            response.read()

        stored = mock_msg_repo.create.await_args.kwargs
        assert (stored["content"], stored["has_image"]) == ("c'est quoi ?", True)
        assert mock_streaming.chat_stream.call_args.kwargs["image"].media_type == "image/png"

    @patch("app.api.routes.message_repo")
    def test_a_bad_screenshot_is_refused_before_anything_is_stored(self, mock_msg_repo, authed_client):
        mock_msg_repo.create = AsyncMock()
        bad = [
            {"media_type": "image/gif", "data": SCREENSHOT},
            {"media_type": "image/jpeg", "data": "pas du base64 !"},
            {"media_type": "image/jpeg", "data": ""},
        ]

        for image in bad:
            for url in ("/chat", "/chat/stream"):
                assert authed_client.post(url, json={"message": "c'est quoi ?", "image": image}).status_code == 422

        mock_msg_repo.create.assert_not_awaited()

    def test_a_screenshot_needs_a_signed_in_user(self, client):
        body = {"message": "c'est quoi ?", "image": {"media_type": "image/jpeg", "data": SCREENSHOT}}

        assert client.post("/chat/stream", json=body).status_code in (401, 403)

    def test_proactions_endpoint_requires_auth(self, client):
        """GET /proactions without auth returns 401."""
        response = client.get("/proactions")
        assert response.status_code in (401, 403)

    def test_messages_endpoint_requires_auth(self, client):
        """GET /messages without auth returns 401."""
        response = client.get("/messages")
        assert response.status_code in (401, 403)

    @patch("app.api.routes.message_repo")
    def test_messages_with_before_param(self, mock_msg_repo, authed_client):
        """GET /messages?before=<id> calls find_before and returns messages."""
        fake_msg = Message(id="msg-1", user_id="test-user", role="user", content="Older message")
        mock_msg_repo.find_before = AsyncMock(return_value=[fake_msg])

        response = authed_client.get("/messages", params={"before": "msg-2"})

        assert response.status_code == 200
        data = response.json()
        assert len(data) == 1
        assert data[0]["id"] == "msg-1"
        mock_msg_repo.find_before.assert_called_once_with("test-user", before_id="msg-2", limit=20)

    @patch("app.api.routes.message_repo")
    def test_messages_with_limit_param(self, mock_msg_repo, authed_client):
        """GET /messages?limit=5 passes custom limit."""
        mock_msg_repo.find_recent = AsyncMock(return_value=[])

        response = authed_client.get("/messages", params={"limit": 5})

        assert response.status_code == 200
        mock_msg_repo.find_recent.assert_called_once_with("test-user", limit=5)

    def test_messages_search_requires_auth(self, client):
        """GET /messages/search without auth returns 401."""
        response = client.get("/messages/search", params={"q": "hello"})
        assert response.status_code in (401, 403)

    @patch("app.api.routes.message_repo")
    def test_messages_search(self, mock_msg_repo, authed_client):
        """GET /messages/search?q=hello returns matching messages."""
        fake_msg = Message(id="msg-1", user_id="test-user", role="assistant", content="Hello there!")
        mock_msg_repo.search = AsyncMock(return_value=[fake_msg])

        response = authed_client.get("/messages/search", params={"q": "hello"})

        assert response.status_code == 200
        data = response.json()
        assert len(data) == 1
        assert data[0]["content"] == "Hello there!"
        mock_msg_repo.search.assert_called_once_with("test-user", "hello", limit=20)

    def test_messages_search_without_query_returns_422(self, authed_client):
        """GET /messages/search without q param returns 422."""
        response = authed_client.get("/messages/search")
        assert response.status_code == 422

    def test_messages_context_requires_auth(self, client):
        """GET /messages/context without auth returns 401."""
        response = client.get("/messages/context", params={"around": "msg-1"})
        assert response.status_code in (401, 403)

    @patch("app.api.routes.message_repo")
    def test_messages_context(self, mock_msg_repo, authed_client):
        """GET /messages/context?around=<id> returns messages with targetIndex."""
        fake_msgs = [
            Message(id="msg-0", user_id="test-user", role="user", content="Before"),
            Message(id="msg-1", user_id="test-user", role="assistant", content="Target"),
            Message(id="msg-2", user_id="test-user", role="user", content="After"),
        ]
        mock_msg_repo.find_around = AsyncMock(return_value={"messages": fake_msgs, "targetIndex": 1})

        response = authed_client.get("/messages/context", params={"around": "msg-1"})

        assert response.status_code == 200
        data = response.json()
        assert len(data["messages"]) == 3
        assert data["targetIndex"] == 1
        assert data["messages"][1]["id"] == "msg-1"

    def test_messages_context_without_around_returns_422(self, authed_client):
        """GET /messages/context without around param returns 422."""
        response = authed_client.get("/messages/context")
        assert response.status_code == 422


def held_action(**overrides) -> PendingAction:
    """A pending action of `test-user`, with the fields the tests vary."""
    fields = {
        "id": "act-1",
        "user_id": "test-user",
        "tool_name": "delete_event",
        "arguments": {"eventId": "evt-1"},
        "source": "chat",
        "context_id": "ctx-1",
        "status": PendingActionStatus.PENDING,
        "created_at": datetime.now(UTC),
        "expires_at": datetime.now(UTC) + timedelta(hours=23),
    }
    fields.update(overrides)
    return PendingAction(**fields)


def answered(action: PendingAction, status: PendingActionStatus, result: str | None = None) -> PendingAction:
    """The same action as the repository hands it back once the answer is written."""
    return held_action(
        id=action.id,
        arguments=action.arguments,
        status=status,
        result=result,
        decided_at=datetime.now(UTC),
        expires_at=action.expires_at,
    )


class TestApprovals:
    def test_list_requires_auth(self, client):
        assert client.get("/approvals").status_code in (401, 403)

    def test_approve_requires_auth(self, client):
        assert client.post("/approvals/act-1/approve").status_code in (401, 403)

    def test_deny_requires_auth(self, client):
        assert client.post("/approvals/act-1/deny").status_code in (401, 403)

    @patch("app.api.routes.pending_action_repo")
    def test_list_returns_the_users_pending_actions(self, mock_repo, authed_client):
        mock_repo.find_pending = AsyncMock(return_value=[held_action(), held_action(id="act-2")])

        response = authed_client.get("/approvals", params={"status": "pending"})

        assert response.status_code == 200
        assert [a["id"] for a in response.json()] == ["act-1", "act-2"]
        assert response.json()[0]["toolName"] == "delete_event"
        mock_repo.find_pending.assert_awaited_once_with("test-user")

    @patch("app.api.routes.pending_action_repo")
    def test_list_serves_the_readable_summary_and_falls_back_to_the_action_label(self, mock_repo, authed_client):
        mock_repo.find_pending = AsyncMock(
            return_value=[
                held_action(summary="Supprimer l'événement « Test validation » — le 8 octobre à 10:00"),
                held_action(id="act-2", arguments={"id": "01KNZ8J6AGQ0K5D3WXYZ123456"}),
            ]
        )

        body = authed_client.get("/approvals", params={"status": "pending"}).json()

        assert body[0]["summary"] == "Supprimer l'événement « Test validation » — le 8 octobre à 10:00"
        assert body[1]["summary"] == "Supprimer l'événement"
        assert "01KNZ8J6AGQ0K5D3WXYZ123456" not in body[1]["summary"]

    def test_list_refuses_a_status_it_does_not_serve(self, authed_client):
        assert authed_client.get("/approvals", params={"status": "approved"}).status_code == 422

    @patch("app.api.routes.llm_gateway")
    @patch("app.api.routes.pending_action_repo")
    def test_approve_another_users_action_is_a_404(self, mock_repo, mock_gateway, authed_client):
        mock_repo.get_for_user = AsyncMock(return_value=None)
        mock_gateway.tool_router.call_tool = AsyncMock()

        response = authed_client.post("/approvals/act-1/approve")

        assert response.status_code == 404
        mock_repo.get_for_user.assert_awaited_once_with("test-user", "act-1")
        mock_gateway.tool_router.call_tool.assert_not_awaited()

    @patch("app.api.routes.pending_action_repo")
    def test_deny_another_users_action_is_a_404(self, mock_repo, authed_client):
        mock_repo.get_for_user = AsyncMock(return_value=None)
        mock_repo.decide = AsyncMock()

        assert authed_client.post("/approvals/act-1/deny").status_code == 404
        mock_repo.decide.assert_not_awaited()

    @patch("app.api.routes.llm_gateway")
    @patch("app.api.routes.pending_action_repo")
    def test_approve_an_already_decided_action_is_a_409(self, mock_repo, mock_gateway, authed_client):
        mock_repo.get_for_user = AsyncMock(return_value=held_action(status=PendingActionStatus.DENIED))
        mock_gateway.tool_router.call_tool = AsyncMock()

        response = authed_client.post("/approvals/act-1/approve")

        assert response.status_code == 409
        mock_gateway.tool_router.call_tool.assert_not_awaited()

    @patch("app.api.routes.pending_action_repo")
    def test_deny_an_already_decided_action_is_a_409(self, mock_repo, authed_client):
        mock_repo.get_for_user = AsyncMock(return_value=held_action(status=PendingActionStatus.APPROVED))

        assert authed_client.post("/approvals/act-1/deny").status_code == 409

    @patch("app.api.routes.llm_gateway")
    @patch("app.api.routes.pending_action_repo")
    def test_approve_an_action_claimed_meanwhile_is_a_409_and_runs_nothing(self, mock_repo, mock_gateway, authed_client):
        """Two tabs clicking Autoriser: the one that loses the claim must not run the call."""
        mock_repo.get_for_user = AsyncMock(return_value=held_action())
        mock_repo.decide = AsyncMock(return_value=None)
        mock_gateway.tool_router.call_tool = AsyncMock()

        assert authed_client.post("/approvals/act-1/approve").status_code == 409
        mock_gateway.tool_router.call_tool.assert_not_awaited()

    @patch("app.api.routes.llm_gateway")
    @patch("app.api.routes.pending_action_repo")
    def test_approve_an_expired_action_is_a_410(self, mock_repo, mock_gateway, authed_client):
        overdue = held_action(expires_at=datetime.now(UTC) - timedelta(minutes=1))
        mock_repo.get_for_user = AsyncMock(return_value=overdue)
        mock_repo.decide = AsyncMock()
        mock_gateway.tool_router.call_tool = AsyncMock()

        response = authed_client.post("/approvals/act-1/approve")

        assert response.status_code == 410
        mock_repo.decide.assert_not_awaited()
        mock_gateway.tool_router.call_tool.assert_not_awaited()

    @patch("app.api.routes.llm_gateway")
    @patch("app.api.routes.pending_action_repo")
    def test_approve_an_action_the_scheduler_expired_is_a_410(self, mock_repo, mock_gateway, authed_client):
        mock_repo.get_for_user = AsyncMock(return_value=held_action(status=PendingActionStatus.EXPIRED))
        mock_gateway.tool_router.call_tool = AsyncMock()

        assert authed_client.post("/approvals/act-1/approve").status_code == 410
        mock_gateway.tool_router.call_tool.assert_not_awaited()

    @patch("app.api.routes.pending_action_repo")
    def test_deny_an_expired_action_is_a_410(self, mock_repo, authed_client):
        overdue = held_action(expires_at=datetime.now(UTC) - timedelta(minutes=1))
        mock_repo.get_for_user = AsyncMock(return_value=overdue)

        assert authed_client.post("/approvals/act-1/deny").status_code == 410

    @patch("app.api.routes.context_summarizer")
    @patch("app.api.routes.message_repo")
    @patch("app.api.routes.run_tool_loop")
    @patch("app.api.routes.llm_gateway")
    @patch("app.api.routes.pending_action_repo")
    def test_approve_runs_the_frozen_arguments_records_the_outcome_and_announces_it(
        self, mock_repo, mock_gateway, mock_loop, mock_msg_repo, mock_summarizer, authed_client
    ):
        pending = held_action()
        approved = answered(pending, PendingActionStatus.APPROVED)
        settled = answered(pending, PendingActionStatus.APPROVED, '{"deleted": true}')
        mock_repo.get_for_user = AsyncMock(return_value=pending)
        mock_repo.decide = AsyncMock(return_value=approved)
        mock_repo.settle = AsyncMock(return_value=settled)
        mock_gateway.client = MagicMock()
        mock_gateway.tool_router.call_tool = AsyncMock(return_value='{"deleted": true}')
        mock_gateway._build_system_prompt = AsyncMock(return_value=[{"type": "text", "text": "system"}])
        mock_loop.return_value = {"response": "J'ai supprimé l'événement.", "tool_calls": []}
        mock_msg_repo.create = AsyncMock()
        mock_summarizer.maybe_summarize = AsyncMock()

        response = authed_client.post("/approvals/act-1/approve")

        assert response.status_code == 200
        assert response.json()["status"] == "approved"
        assert response.json()["result"] == '{"deleted": true}'
        mock_gateway.tool_router.call_tool.assert_awaited_once_with(
            "delete_event", {"eventId": "evt-1"}, "test-user", source="approval"
        )
        # Claimed before the call runs, outcome written after it.
        mock_repo.decide.assert_awaited_once_with("act-1", PendingActionStatus.APPROVED)
        mock_repo.settle.assert_awaited_once_with("act-1", PendingActionStatus.APPROVED, '{"deleted": true}')

        # The announcement: no tools, the validated call and its result in the prompt.
        mock_loop.assert_awaited_once()
        loop_args, loop_kwargs = mock_loop.call_args
        assert loop_args[2] is None
        prompt = loop_args[1][0]["content"]
        assert "L'utilisateur a validé delete_event" in prompt
        assert "evt-1" in prompt
        assert '{"deleted": true}' in prompt
        assert loop_kwargs["source"] == "approval"
        # Stored in the thread the action was asked in; message_repo publishes it on the chat topic.
        mock_msg_repo.create.assert_awaited_once_with(
            user_id="test-user", role="assistant", content="J'ai supprimé l'événement.", context_id="ctx-1"
        )
        mock_summarizer.maybe_summarize.assert_awaited_once_with("ctx-1")

    @patch("app.api.routes.message_repo")
    @patch("app.api.routes.run_tool_loop")
    @patch("app.api.routes.llm_gateway")
    @patch("app.api.routes.pending_action_repo")
    def test_approve_marks_the_action_failed_when_the_tool_returns_an_error(
        self, mock_repo, mock_gateway, mock_loop, mock_msg_repo, authed_client
    ):
        error = json.dumps({"error": "Event not found"})
        pending = held_action(context_id=None)
        mock_repo.get_for_user = AsyncMock(return_value=pending)
        mock_repo.decide = AsyncMock(return_value=answered(pending, PendingActionStatus.APPROVED))
        mock_repo.settle = AsyncMock(return_value=answered(pending, PendingActionStatus.FAILED, error))
        mock_gateway.client = MagicMock()
        mock_gateway.tool_router.call_tool = AsyncMock(return_value=error)
        mock_gateway._build_system_prompt = AsyncMock(return_value=[])
        mock_loop.return_value = {"response": "Je n'ai pas pu le supprimer.", "tool_calls": []}
        mock_msg_repo.create = AsyncMock()

        response = authed_client.post("/approvals/act-1/approve")

        assert response.status_code == 200
        assert response.json()["status"] == "failed"
        mock_repo.settle.assert_awaited_once_with("act-1", PendingActionStatus.FAILED, error)
        # The failure is announced too: the user clicked and is owed an answer.
        mock_msg_repo.create.assert_awaited_once()

    @patch("app.api.routes.message_repo")
    @patch("app.api.routes.run_tool_loop")
    @patch("app.api.routes.llm_gateway")
    @patch("app.api.routes.pending_action_repo")
    def test_approve_marks_the_action_failed_when_the_call_raises(
        self, mock_repo, mock_gateway, mock_loop, mock_msg_repo, authed_client
    ):
        pending = held_action()
        mock_repo.get_for_user = AsyncMock(return_value=pending)
        mock_repo.decide = AsyncMock(return_value=answered(pending, PendingActionStatus.APPROVED))
        mock_repo.settle = AsyncMock(return_value=answered(pending, PendingActionStatus.FAILED, "x"))
        mock_gateway.client = None
        mock_gateway.tool_router.call_tool = AsyncMock(side_effect=RuntimeError("boom"))

        response = authed_client.post("/approvals/act-1/approve")

        assert response.status_code == 200
        status = mock_repo.settle.await_args.args[1]
        assert status == PendingActionStatus.FAILED
        assert "boom" in mock_repo.settle.await_args.args[2]

    @patch("app.api.routes.message_repo")
    @patch("app.api.routes.run_tool_loop")
    @patch("app.api.routes.llm_gateway")
    @patch("app.api.routes.pending_action_repo")
    def test_a_failing_announcement_does_not_fail_the_approval(
        self, mock_repo, mock_gateway, mock_loop, mock_msg_repo, authed_client
    ):
        pending = held_action()
        mock_repo.get_for_user = AsyncMock(return_value=pending)
        mock_repo.decide = AsyncMock(return_value=answered(pending, PendingActionStatus.APPROVED))
        mock_repo.settle = AsyncMock(return_value=answered(pending, PendingActionStatus.APPROVED, "{}"))
        mock_gateway.client = MagicMock()
        mock_gateway.tool_router.call_tool = AsyncMock(return_value="{}")
        mock_gateway._build_system_prompt = AsyncMock(return_value=[])
        mock_loop.side_effect = RuntimeError("API down")
        mock_msg_repo.create = AsyncMock()

        assert authed_client.post("/approvals/act-1/approve").status_code == 200
        mock_msg_repo.create.assert_not_awaited()

    @patch("app.api.routes.run_tool_loop")
    @patch("app.api.routes.llm_gateway")
    @patch("app.api.routes.pending_action_repo")
    def test_deny_records_the_refusal_without_running_anything(
        self, mock_repo, mock_gateway, mock_loop, authed_client
    ):
        pending = held_action()
        mock_repo.get_for_user = AsyncMock(return_value=pending)
        mock_repo.decide = AsyncMock(return_value=answered(pending, PendingActionStatus.DENIED))
        mock_gateway.tool_router.call_tool = AsyncMock()
        mock_loop.return_value = {"response": "x", "tool_calls": []}

        response = authed_client.post("/approvals/act-1/deny")

        assert response.status_code == 200
        assert response.json()["status"] == "denied"
        mock_repo.decide.assert_awaited_once_with("act-1", PendingActionStatus.DENIED)
        mock_gateway.tool_router.call_tool.assert_not_awaited()
        mock_loop.assert_not_awaited()
