"""A Maggie message sent out of a conversation notifies the user (MAG-687)."""

from unittest.mock import AsyncMock, MagicMock, patch

from app.proactions.api import Proaction
from app.queue.message_notification import message_preview, notify_message
from app.queue.proaction_consumer import execute_proaction

PROACTION = Proaction.from_api(
    {
        "id": "01JPROACTION0000000000000A",
        "prompt": "Rappel",
        "summary": "Rappel",
        "scheduledAt": "2037-02-20T09:00:00+01:00",
        "status": "running",
        "userId": "user-1",
    }
)


class TestPreview:
    def test_delivery_tags_are_removed_and_markdown_kept(self):
        assert message_preview("[warmly] Pense au **plombier**.") == "Pense au **plombier**."

    def test_a_long_text_is_cut_with_an_ellipsis(self):
        preview = message_preview("a" * 500)
        assert len(preview) == 200
        assert preview.endswith("…")

    def test_a_voice_message_without_text_is_announced_as_such(self):
        assert message_preview("", has_audio=True) == "Message vocal"
        assert message_preview("[whispers]", has_audio=True) == "Message vocal"


async def test_the_notification_points_at_the_message():
    with patch("app.queue.message_notification.mcp_client") as mcp:
        mcp.call_tool = AsyncMock()
        await notify_message("user-1", "msg-1", "Bonjour")

    mcp.call_tool.assert_awaited_once_with(
        "manage_notifications",
        {
            "action": "create",
            "type": "message",
            "title": "Maggie",
            "body": "Bonjour",
            "relatedEntityIri": "/chat?message=msg-1",
        },
        user_id="user-1",
    )


async def test_a_digest_notification_opens_the_digest():
    with patch("app.queue.message_notification.mcp_client") as mcp:
        mcp.call_tool = AsyncMock()
        await notify_message("user-1", "msg-1", "Bonjour", screen="digest")

    assert mcp.call_tool.await_args.args[1]["relatedEntityIri"] == "/digest?message=msg-1"


async def test_a_failing_notification_is_logged_not_raised():
    with patch("app.queue.message_notification.mcp_client") as mcp:
        mcp.call_tool = AsyncMock(side_effect=RuntimeError("api down"))
        await notify_message("user-1", "msg-1", "Bonjour")


class TestConsumer:
    @classmethod
    async def _run(cls, result, **kwargs):
        outcome, mcp, _ = await cls._run_keeping(result, **kwargs)
        return outcome, mcp

    @staticmethod
    async def _run_keeping(result, *, dry_run=False, audio=None, notify_error=None):
        gateway = MagicMock()
        if isinstance(result, Exception):
            gateway.proaction = AsyncMock(side_effect=result)
        else:
            gateway.proaction = AsyncMock(return_value=result)
        stored = MagicMock(id="msg-9", audio=audio)
        with (
            patch("app.queue.proaction_consumer.message_repo") as messages,
            patch("app.queue.proaction_consumer.proactions_api") as proactions,
            patch("app.queue.proaction_consumer.publisher") as publisher,
            patch("app.queue.proaction_consumer.context_summarizer") as summarizer,
            patch("app.queue.message_notification.mcp_client") as mcp,
        ):
            messages.create = AsyncMock(return_value=stored)
            proactions.record = AsyncMock(return_value=PROACTION)
            publisher.publish = AsyncMock()
            summarizer.maybe_summarize = AsyncMock()
            mcp.call_tool = AsyncMock(side_effect=notify_error)
            outcome = await execute_proaction(gateway, PROACTION, dry_run=dry_run)
        return outcome, mcp, messages

    async def test_the_message_is_notified_with_a_stripped_preview(self):
        _, mcp = await self._run({"response": "[warmly] Pense au plombier.", "tool_calls": [], "context_id": "c1"})

        args = mcp.call_tool.await_args
        assert args.args[0] == "manage_notifications"
        assert args.args[1]["body"] == "Pense au plombier."
        assert args.args[1]["relatedEntityIri"] == "/chat?message=msg-9"
        assert args.kwargs["user_id"] == "user-1"

    async def test_a_digest_run_stores_its_message_on_the_digest_and_the_notification_opens_it(self):
        vocal = {
            "name": "send_voice_message",
            "input": {"text": "Bonjour", "screen": "digest"},
            "result": '{"sent": true, "messageId": "voc-1", "durationMs": 900, "screen": "digest"}',
        }
        _, mcp, messages = await self._run_keeping(
            {"response": "Votre journée…", "tool_calls": [vocal], "context_id": "c1"}
        )

        assert messages.create.await_args.kwargs["screen"] == "digest"
        assert mcp.call_tool.await_args.args[1]["relatedEntityIri"] == "/digest?message=msg-9"

    async def test_a_vocal_that_failed_still_leaves_the_written_digest_on_the_digest(self):
        vocal = {
            "name": "send_voice_message",
            "input": {"text": "Bonjour", "screen": "digest"},
            "result": '{"error": "Le vocal n\'a pas pu être enregistré"}',
        }
        _, mcp, messages = await self._run_keeping({"response": "Votre journée…", "tool_calls": [vocal]})

        assert messages.create.await_args.kwargs["screen"] == "digest"
        assert mcp.call_tool.await_args.args[1]["relatedEntityIri"] == "/digest?message=msg-9"

    async def test_a_vocal_without_a_screen_leaves_the_message_in_the_thread(self):
        vocal = {"name": "send_voice_message", "input": {"text": "Bonjour"}, "result": '{"sent": true}'}
        _, mcp, messages = await self._run_keeping({"response": "Voilà.", "tool_calls": [vocal]})

        assert messages.create.await_args.kwargs["screen"] is None
        assert mcp.call_tool.await_args.args[1]["relatedEntityIri"] == "/chat?message=msg-9"

    async def test_a_voice_message_without_text_reads_message_vocal(self):
        _, mcp = await self._run({"response": "[whispers]", "tool_calls": []}, audio={"type": "audio/mpeg"})

        assert mcp.call_tool.await_args.args[1]["body"] == "Message vocal"

    async def test_a_dry_run_notifies_nothing(self):
        _, mcp = await self._run({"response": "Salut", "tool_calls": []}, dry_run=True)

        mcp.call_tool.assert_not_awaited()

    async def test_a_failed_proaction_notifies_nothing(self):
        outcome, mcp = await self._run(RuntimeError("LLM timeout"))

        assert outcome["status"] == "failed"
        mcp.call_tool.assert_not_awaited()

    async def test_a_silent_trip_run_notifies_nothing(self):
        proaction = Proaction.from_api(
            {
                "id": "x",
                "prompt": "p",
                "summary": "Replanifier les trajets",
                "scheduledAt": "2037-02-20T09:00:00+01:00",
                "status": "running",
                "userId": "user-1",
            }
        )
        gateway = MagicMock()
        gateway.proaction = AsyncMock(return_value={"response": "Rien.", "tool_calls": []})
        with (
            patch("app.queue.proaction_consumer.message_repo") as messages,
            patch("app.queue.proaction_consumer.proactions_api") as proactions,
            patch("app.queue.proaction_consumer.publisher") as publisher,
            patch("app.queue.message_notification.mcp_client") as mcp,
        ):
            messages.create = AsyncMock()
            proactions.record = AsyncMock(return_value=proaction)
            publisher.publish = AsyncMock()
            mcp.call_tool = AsyncMock()
            await execute_proaction(gateway, proaction)

        mcp.call_tool.assert_not_awaited()

    async def test_a_notification_that_fails_does_not_fail_the_proaction(self):
        outcome, _ = await self._run(
            {"response": "Salut", "tool_calls": []}, notify_error=RuntimeError("api down")
        )

        assert outcome["status"] == "completed"
